package app.dsh.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import java.io.File

/**
 * 流式识别会话 —— 把「手表不断送上来的 PCM 分片」边喂边出字。
 *
 * ## 为什么需要它
 * `DecodeService` 原来走 `EngineSelfTest.decodeWav()`：等整段 WAV 收齐才识别，
 * 用户说完要干等「上传完 + 整段识别」。而本机模型
 * `zipformer-bilingual-zh-en-int8` 的 kind 是 `STREAMING_TRANSDUCER`，
 * 天生支持边说边出字（`note = 边说边出字（流式）`）。
 * 这里把 `AsrEngine.runStreaming()` 里「喂块 → decode → 取文本」那段抽出来，
 * 去掉 `AudioRecord` 依赖，改成外部推块。
 *
 * ## 用法
 * ```
 * val s = StreamSession(ctx, model)
 * s.accept(pcmBytes) { partial -> ... }   // 每收一片就喂，文本有变化时回调
 * s.finish()                               // 收尾，返回最终文本
 * ```
 *
 * ## 线程约束
 * sherpa 的 Recognizer **不是线程安全的**。本类内部用 [lock] 串行化所有操作，
 * 调用方可以从不同线程 accept（网络回调线程 / 拼装线程），但内部不会并发进引擎。
 */
class StreamSession(
    private val ctx: Context,
    private val model: AsrModel,
    /**
     * ★ v0.7.1（2026-11-03 23:1x）：输入 PCM 的真实采样率，由调用方传入。
     *
     * 真机实测：手表 @system.record 请求 sampleRate=16000，但实际只产出
     * 约 6400~7100 B/s（≈3200~3550 采样/秒），历史纯采集探针也只有 14160 B/s
     * （≈7080 采样/秒，接近官方默认 8000），证明引擎不采纳 16000。
     * 若这里仍写死 16k 喂给 sherpa，音频会被当成 2 倍速，识别结果全错。
     */
    private val sampleRate: Int = 16_000,
) {
    companion object {
        private const val TAG = "StreamSession"

        /** 默认采样率（仅在调用方没传时用） */
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SAMPLE = 2
    }

    private val lock = Any()
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var lastText = ""
    private var totalSamples = 0L
    private var closed = false

    /** 左半字节用来处理「奇数字节」的跨块拼接（PCM16 必须 2 字节对齐） */
    private var pendingByte: Int = -1

    /** 每次 decode 最多喂多少采样（约 200ms），按实际采样率算 */
    private val feedChunkSamples: Int = (sampleRate / 5).coerceAtLeast(160)

    /**
     * ★★★ v0.7.1（2026-11-03 23:3x）：**sherpa 的 zipformer 模型是按 16kHz 训练的**，
     * `acceptWaveform(samples, sampleRate)` 的第二个参数必须等于**这份音频自己的
     * 采样率**，不是模型采样率。若直接把 8k 音频标成 8k 喂进去，sherpa 会按 8k 做
     * 特征提取 —— 但模型特征配置是 FeatureConfig(16000, 80, 0)（AsrEngine.kt:201），
     * 二者不匹配 → 流式识别必然全错。
     *
     * 所以必须**先把 8k 重采样到 16k** 再喂，且 cross-chunk 状态要保留：
     * 线性插值在块边界需要上一块的最后一个采样点，否则每 200ms 会产生一个小跳变。
     */
    private val modelRate: Int = 16_000   // sherpa 模型训练采样率（AsrEngine.SAMPLE_RATE 同值）
    /** 重采样相位（跨块累积，避免每块都从 0 开始导致累积误差） */
    private var resamplePos: Double = 0.0
    /** 上一块的最后一个采样（跨块线性插值用） */
    private var lastSample: Float = 0f

    /**
     * 跨块线性插值重采样到 [modelRate]。
     *
     * 与整段识别的 `AsrEngine.resample` 不同：这里保留 [resamplePos] 与
     * [lastSample]，让相位跨越块边界连续，避免每 200ms 出现一次相位跳变
     * （跳变会在特征里产生宽带噪声，直接影响识别）。
     */
    private fun resampleCrossChunk(input: List<Float>): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        val srcRate = sampleRate
        val ratio = srcRate.toDouble() / modelRate
        // 上一块最后一点 + 本块，构成可插值序列（索引 0 是 lastSample）
        val n = input.size
        var pos = resamplePos
        val out = ArrayList<Float>((n / ratio).toInt() + 2)
        while (pos < n) {
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val a = if (i0 <= 0) lastSample else input[i0 - 1]
            val b = if (i0 < n) input[i0] else a
            out.add(a * (1 - frac) + b * frac)
            pos += ratio
        }
        // 相位折回本块坐标系（减去 n，保留小数部分）
        resamplePos = pos - n
        lastSample = input[n - 1]
        return out.toFloatArray()
    }

    /** 懒初始化：第一次 accept 时才建引擎（建引擎约 200~600ms） */
    private fun ensure(): Pair<OnlineRecognizer, OnlineStream>? = synchronized(lock) {
        if (closed) return null
        recognizer?.let { r -> stream?.let { s -> return r to s } }
        return try {
            val t0 = System.currentTimeMillis()
            val r = EngineHolder.online(ctx, model)
            val s = r.createStream("")
            recognizer = r
            stream = s
            Log.i(TAG, "流式会话就绪，建引擎 ${System.currentTimeMillis() - t0}ms")
            r to s
        } catch (t: Throwable) {
            Log.e(TAG, "建流式会话失败", t)
            null
        }
    }

    /**
     * 喂入一段裸 PCM16 LE 单声道数据。
     *
     * @param pcm 原始字节（可从任意分片边界切开，本类自行处理奇数字节）
     * @param onPartial 文本有变化时回调（在调用线程同步执行，不要做重活）
     * @return 当前累积的完整文本
     */
    fun accept(pcm: ByteArray, onPartial: ((String) -> Unit)? = null): String {
        val pair = ensure() ?: return lastText
        val (recognizer, stream) = pair

        // 1) 拼成 short 序列（处理跨块奇数字节）
        val shorts = ArrayList<Float>(pcm.size / 2 + 1)
        synchronized(lock) {
            var i = 0
            if (pendingByte >= 0 && pcm.isNotEmpty()) {
                val lo = pcm[0].toInt() and 0xFF
                val v = (pendingByte or (lo shl 8)).toShort()
                shorts.add(v / 32768f)
                pendingByte = -1
                i = 1
            }
            while (i + 1 < pcm.size) {
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt() and 0xFF
                val v = (lo or (hi shl 8)).toShort()
                shorts.add(v / 32768f)
                i += 2
            }
            if (i < pcm.size) pendingByte = pcm[i].toInt() and 0xFF
        }
        if (shorts.isEmpty()) return lastText

        // ★ 8k → 16k：sherpa 模型按 16k 训练，必须先转成模型采样率
        val samples = if (sampleRate == modelRate) shorts.toFloatArray()
                      else resampleCrossChunk(shorts)

        // 2) 分块喂进引擎并 decode
        var text = lastText
        var off = 0
        while (off < samples.size) {
            val n = minOf(feedChunkSamples, samples.size - off)
            val chunk = FloatArray(n) { samples[off + it] }
            off += n
            text = synchronized(lock) {
                try {
                    stream.acceptWaveform(chunk, modelRate)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    totalSamples += n
                    runCatching { recognizer.getResult(stream).text.trim() }.getOrDefault(lastText)
                } catch (t: Throwable) {
                    Log.w(TAG, "喂块失败：${t.message}")
                    lastText
                }
            }
        }

        if (text.isNotEmpty() && text != lastText) {
            lastText = text
            runCatching { onPartial?.invoke(text) }
        }
        return text
    }

    /** 收尾：把残留的半个字节丢掉，返回最终文本。 */
    fun finish(): String = synchronized(lock) {
        closed = true
        val s = stream
        val r = recognizer
        var text = lastText
        if (s != null && r != null) {
            runCatching {
                s.inputFinished()
                while (r.isReady(s)) r.decode(s)
                val t = r.getResult(s).text.trim()
                if (t.isNotEmpty()) text = t
            }.onFailure { Log.w(TAG, "收尾失败：${it.message}") }
            runCatching { s.release() }
        }
        stream = null
        recognizer = null
        pendingByte = -1
        Log.i(TAG, "流式会话结束：${totalSamples} 采样（${totalSamples * 1000 / sampleRate}ms）文本=${text.length}字")
        text
    }

    /** 放弃：不取结果，直接释放。 */
    fun abort() = synchronized(lock) {
        closed = true
        runCatching { stream?.release() }
        stream = null
        recognizer = null
        pendingByte = -1
    }

    val samplesFed: Long get() = synchronized(lock) { totalSamples }
    val currentText: String get() = synchronized(lock) { lastText }
}

/** 把裸 PCM16 LE 写成 WAV（流式收尾后存档/兜底用） */
fun writeWavFromPcm(pcm: ByteArray, out: File, sampleRate: Int = StreamSession.SAMPLE_RATE): File {
    out.parentFile?.mkdirs()
    val byteRate = sampleRate * 1 * StreamSession.BYTES_PER_SAMPLE
    val dataLen = pcm.size
    java.io.BufferedOutputStream(out.outputStream()).use { o ->
        fun le32(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF) }
        fun le16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
        o.write("RIFF".toByteArray()); le32(36 + dataLen); o.write("WAVE".toByteArray())
        o.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(sampleRate); le32(byteRate); le16(StreamSession.BYTES_PER_SAMPLE); le16(16)
        o.write("data".toByteArray()); le32(dataLen); o.write(pcm)
    }
    return out
}
