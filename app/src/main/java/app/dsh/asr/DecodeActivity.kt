package app.dsh.asr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * 文件识别入口 —— 让**外部 App**（如 databank-companion）把一段音频文件丢进来，
 * 拿回识别文本。
 *
 * ## 为什么需要这个入口
 * 系统 `RecognitionService` 协议只能由 `SpeechRecognizer` 调用，音频源固定是**本机麦克风**，
 * 无法喂入外部音频文件。而本项目要的链路是：
 *
 *   Xiaomi Watch S5 录音 → PCM 分片回传手机 → 手机识别 → 文本回手环
 *
 * 音频来自手表、不是手机麦克风，所以必须走文件入口。底层能力早就有了
 * （[EngineSelfTest.decodeWav] 原本用于无麦克风的模拟器自检），只是没对外暴露。
 *
 * ## 调用契约
 * ```
 * Intent("app.dsh.asr.DECODE_FILE")
 *     putExtra("wav", "/sdcard/xxx.wav")     // 必填，绝对路径
 *     putExtra("lang", "zh")                 // 可选，默认 zh
 *     putExtra("wait", true)                 // 可选，默认 true（等结果再返回）
 * ```
 * 结果放在返回的 Intent 里：
 * - `text`：识别文本（失败时为空串）
 * - `ok`：是否成功
 * - `err`：失败原因（成功时为 null）
 * - `ms`：推理耗时
 *
 * ## 为什么用 `startActivityForResult` 而不是 Service
 * 引擎（sherpa）只在本进程内可用，且加载模型需要几十~几百毫秒；Activity 能直接被外部
 * App 以 `startActivityForResult` 拉起、进程随用随起，不需要常驻。
 * 识别本身跑在单线程池上，避免占用主线程导致 ANR。
 */
class DecodeActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val wavPath = intent.getStringExtra("wav")
        val wavBase64 = intent.getStringExtra("wavBase64")
        val lang = intent.getStringExtra("lang")?.ifBlank { null } ?: "zh"

        // ★ 两种投递方式，优先 base64：
        //   文件路径只在「同 uid 或路径本身可读」时可行。手表录音链路里音频先落在
        //   调用方 App（vela.Databank）的私有目录，本服务是另一个 uid，读不到；
        //   若放 /sdcard 又会碰到分区存储（targetSdk 28 无外部读权限，实测 EACCES）。
        //   所以调用方直接 base64 塞进 Intent，落盘到本应用自己的缓存目录再解。
        val wav: File = when {
            !wavBase64.isNullOrBlank() -> {
                val bytes = runCatching {
                    android.util.Base64.decode(wavBase64, android.util.Base64.DEFAULT)
                }.getOrNull()
                if (bytes == null || bytes.size <= 44) {
                    finishWith(false, "", null, 0, "音频数据无效（${bytes?.size ?: 0} 字节）")
                    return
                }
                val f = File(cacheDir, "decode_in_${System.currentTimeMillis()}.wav")
                runCatching { f.writeBytes(bytes) }.onFailure {
                    finishWith(false, "", null, 0, "写入缓存失败：${it.message}")
                    return
                }
                Log.i(TAG, "收到 base64 音频 ${bytes.size}B → ${f.name}")
                f
            }
            !wavPath.isNullOrBlank() -> {
                val f = File(wavPath)
                if (!f.exists() || f.length() <= 44) {
                    // 44 字节 = WAV 文件头本身；比它还小说明根本没录到音频，
                    // 这里直接报清楚，而不是让引擎抛一个看不懂的格式错误。
                    finishWith(false, "", null, 0, "音频文件不存在或为空（${f.length()} 字节）")
                    return
                }
                f
            }
            else -> {
                finishWith(false, "", null, 0, "缺少 wav 或 wavBase64 参数")
                return
            }
        }

        worker.execute {
            val result = runCatching {
                val prefs = Prefs(this)
                val store = ModelStore(this)
                val model = ModelPicker.pick(prefs, store, lang)
                    ?: return@execute finishWith(false, "", null, 0, "没有可用模型，请先在 app.dsh.asr 内下载")
                Log.i(TAG, "文件识别 开始：model=${model.id} wav=${wav.name} size=${wav.length()}B lang=$lang")
                EngineSelfTest.decodeWav(this, model, wav, lang)
            }.getOrElse { t ->
                Log.e(TAG, "文件识别异常", t)
                finishWith(false, "", t.message ?: t.javaClass.simpleName, 0, t.message ?: "识别异常")
                return@execute
            }
            Log.i(
                TAG,
                "文件识别 完成：ok=${result.ok} ${result.decodeMs}ms audio=${result.audioSec}s " +
                    "text=${result.text}",
            )
            // 自己落盘的临时文件用完即删（只删 cacheDir 下自己建的，不碰外部路径）
            if (wav.absolutePath.startsWith(cacheDir.absolutePath)) runCatching { wav.delete() }
            finishWith(result.ok, result.text, result.error, result.decodeMs, result.error)
        }
    }

    private fun finishWith(ok: Boolean, text: String, err: String?, ms: Long, msg: String?) {
        runOnUiThread {
            if (!ok) Log.w(TAG, "文件识别失败：$msg")
            // 广播回传：调用方通常是后台 App（不是 Activity），
            // 拿不到 startActivityForResult 的返回值，只能靠这条广播。
            val replyAction = intent.getStringExtra("replyAction")
            if (!replyAction.isNullOrBlank()) {
                val reply = Intent(replyAction).apply {
                    setPackage(intent.getStringExtra("replyPackage"))
                    putExtra("ok", ok)
                    putExtra("text", text)
                    putExtra("err", err)
                    putExtra("ms", ms)
                }
                runCatching { sendBroadcast(reply) }
                    .onFailure { Log.w(TAG, "回传广播失败：${it.message}") }
            }
            val data = Intent().apply {
                putExtra("ok", ok)
                putExtra("text", text)
                putExtra("err", err)
                putExtra("ms", ms)
            }
            setResult(if (ok) RESULT_OK else RESULT_CANCELED, data)
            finish()
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AsrDecode"
    }
}
