package app.dsh.asr

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** 模型在本地磁盘上的状态 */
enum class ModelState { MISSING, PARTIAL, READY }

class DownloadCancelled : Exception("download cancelled")

/**
 * 模型仓库：负责落地路径、状态判断、下载（断点续传 + 体积校验）、删除。
 *
 * 落盘位置：`<外部私有目录>/models/<modelId>/<file>`，外部私有目录不可用时退回内部 filesDir。
 * 说明：外部私有目录无需任何存储权限（应用专属目录），且方便用户用文件管理器查看/清理。
 */
class ModelStore(context: Context) {

    private val appCtx = context.applicationContext

    /**
     * 当前生效的下载源（v0.5.0）。
     * 按界面语言推断（中文→hf-mirror，其它→HuggingFace 官方），设置页可手动覆盖。
     */
    private fun source(): ModelCatalog.Source =
        Prefs(appCtx).modelSource(java.util.Locale.getDefault().toLanguageTag())

    val root: File = run {
        val base = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        File(base, "models").also { it.mkdirs() }
    }

    fun dirOf(model: AsrModel): File = File(root, model.id)

    fun fileOf(model: AsrModel, file: ModelFile): File = File(dirOf(model), file.name)

    private fun partOf(model: AsrModel, file: ModelFile): File = File(dirOf(model), file.name + ".part")

    /** 单个文件是否完整（存在且字节数精确匹配目录中登记的实测大小） */
    fun fileReady(model: AsrModel, file: ModelFile): Boolean {
        val f = fileOf(model, file)
        return f.isFile && f.length() == file.bytes
    }

    /** 已完整落盘的文件集合 */
    fun readyFiles(model: AsrModel): List<ModelFile> = model.files.filter { fileReady(model, it) }

    fun state(model: AsrModel): ModelState {
        val ready = readyFiles(model).size
        if (ready == model.files.size) return ModelState.READY
        val hasPartial = model.files.any { partOf(model, it).isFile && partOf(model, it).length() > 0 }
        return if (ready > 0 || hasPartial) ModelState.PARTIAL else ModelState.MISSING
    }

    /** 已占用/已下载字节数（含 .part 断点） */
    fun storedBytes(model: AsrModel): Long = model.files.sumOf { f ->
        val done = if (fileReady(model, f)) f.bytes else 0L
        done + (partOf(model, f).takeIf { it.isFile }?.length() ?: 0L)
    }

    fun delete(model: AsrModel) {
        val dir = dirOf(model)
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    /** 当前已完整下载的模型 */
    fun readyModels(): List<AsrModel> = ModelCatalog.MODELS.filter { state(it) == ModelState.READY }

    /**
     * 下载整个模型（多文件串行）。
     *
     * @param onProgress (已完成字节, 总字节, 当前文件说明)
     * @param cancel 外部取消标志（UI 上的「取消」）
     * @return true = 全部文件校验通过
     */
    fun download(
        model: AsrModel,
        cancel: AtomicBoolean,
        onProgress: (Long, Long, String) -> Unit,
    ): Boolean {
        var doneBase = 0L
        val total = model.totalBytes
        for (file in model.files) {
            if (cancel.get()) throw DownloadCancelled()
            val ok = downloadFile(model, file, cancel) { inFile ->
                onProgress(doneBase + inFile, total, file.name)
            }
            if (!ok) return false
            doneBase += file.bytes
        }
        return true
    }

    /**
     * 单文件下载：支持 HTTP Range 断点续传。
     * `.part` 累积 → 校验长度 == 目录登记字节数 → 原子改名成正式文件。
     */
    private fun downloadFile(
        model: AsrModel,
        file: ModelFile,
        cancel: AtomicBoolean,
        onBytes: (Long) -> Unit,
    ): Boolean {
        val dest = fileOf(model, file)
        if (fileReady(model, file)) {
            onBytes(file.bytes)
            return true
        }
        dirOf(model).mkdirs()
        val part = partOf(model, file)

        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            if (cancel.get()) throw DownloadCancelled()

            var existing = if (part.isFile) part.length() else 0L
            if (existing > file.bytes) {           // 本地比远端大：污染，从头来
                part.delete()
                existing = 0L
            }
            if (existing == file.bytes) {
                if (part.renameTo(dest)) { onBytes(file.bytes); return true }
                part.delete()
                existing = 0L
            }

            var conn: HttpURLConnection? = null
            try {
                val url = URL(ModelCatalog.url(model, file, source()))
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "dsh-asr-service/0.1 (Android)")
                    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    Log.w(TAG, "HTTP $code for ${file.name} (attempt $attempt)")
                    attempt--
                    if (attempt >= MAX_ATTEMPTS) return false
                    sleepBackoff(attempt)
                    continue
                }
                val append = code == HttpURLConnection.HTTP_PARTIAL

                RandomAccessFile(part, "rw").use { raf ->
                    if (!append) raf.setLength(0)
                    raf.seek(if (append) existing else 0L)
                    var written = if (append) existing else 0L
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancel.get()) throw DownloadCancelled()
                            val n = input.read(buf)
                            if (n <= 0) break
                            raf.write(buf, 0, n)
                            written += n
                            onBytes(written)
                        }
                    }
                }
            } catch (e: DownloadCancelled) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download ${file.name} attempt $attempt failed: ${e.message}")
                if (attempt >= MAX_ATTEMPTS) return false
                sleepBackoff(attempt)
                continue
            } finally {
                conn?.disconnect()
            }

            // 下载完成 → 严格校验字节数
            val got = part.length()
            if (got == file.bytes) {
                if (!part.renameTo(dest)) {
                    // 改名失败（极少见）：直接把内容搬过去
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                Log.i(TAG, "downloaded ok: ${model.id}/${file.name} = $got bytes")
                return true
            }
            Log.w(TAG, "size mismatch ${file.name}: got=$got expected=${file.bytes}, retry")
            part.delete()
            sleepBackoff(attempt)
        }
        return false
    }

    private fun sleepBackoff(attempt: Int) {
        try {
            Thread.sleep(800L * attempt)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val TAG = "ModelStore"
        private const val MAX_ATTEMPTS = 5
    }
}
