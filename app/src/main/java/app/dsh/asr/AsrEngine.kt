package app.dsh.asr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

const val SAMPLE_RATE = 16_000

/** 音频小工具：WAV 读取、PCM16 → float、能量计算 */
object AudioUtil {

    /** 读 16bit PCM WAV → 16kHz 单声道 float（用于离线自检，不依赖麦克风） */
    fun readWavFloats(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size > 44) { "wav too small" }
        var pos = 12
        var channels = 1
        var bits = 16
        var rate = SAMPLE_RATE
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = le32(bytes, pos + 4)
            if (id == "fmt " && pos + 8 + 16 <= bytes.size) {
                channels = le16(bytes, pos + 10)
                rate = le32(bytes, pos + 12)
                bits = le16(bytes, pos + 22)
            } else if (id == "data") {
                dataOff = pos + 8
                dataLen = minOf(size, bytes.size - dataOff)
            }
            pos += 8 + size + (size and 1)
        }
        require(dataOff > 0 && dataLen > 0) { "wav data chunk not found" }
        require(bits == 16) { "only 16-bit PCM wav supported, got $bits" }
        val frames = dataLen / 2 / channels
        val mono = FloatArray(frames)
        var p = dataOff
        for (i in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) {
                acc += (le16(bytes, p).toShort()).toInt()
                p += 2
            }
            mono[i] = acc.toFloat() / channels / 32768f
        }
        return if (rate == SAMPLE_RATE) mono else resample(mono, rate, SAMPLE_RATE)
    }

    /** 线性插值重采样（测试音频用，够用） */
    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return input
        val outLen = (input.size.toLong() * to / from).toInt()
        val out = FloatArray(outLen)
        val ratio = from.toDouble() / to
        for (i in 0 until outLen) {
            val src = i * ratio
            val i0 = src.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (src - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }

    fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size).toFloat()
    }

    fun db(rms: Float): Float = if (rms <= 1e-6f) -120f else 20f * log10(rms)

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}

/** 一次识别请求的参数（由 RecognitionService 收到的 Intent 解析而来） */
data class RequestParams(
    val langHint: String,       // auto | zh | en
    val partialsWanted: Boolean,
    val preferOffline: Boolean,
    val maxResults: Int,
    val silenceMs: Int,
    val maxUtteranceMs: Int,
    val noSpeechTimeoutMs: Int,
)

/**
 * 引擎缓存：同一进程内复用 sherpa 识别器（加载 240MB 模型代价高，不能每次会话重建）。
 * 注意：sherpa 的 Recognizer 不是线程安全的，调用方需自行保证串行（本应用单会话串行）。
 */
object EngineHolder {

    private const val TAG = "EngineHolder"

    private var cachedKey: String? = null
    private var offline: OfflineRecognizer? = null
    private var online: OnlineRecognizer? = null

    @Synchronized
    fun offline(ctx: Context, model: AsrModel, lang: String): OfflineRecognizer {
        val key = "offline:${model.id}|$lang"
        if (key == cachedKey) offline?.let { return it }
        clearLocked()
        val t0 = SystemClock.elapsedRealtime()
        val r = buildOffline(ctx, model, lang)
        Log.i(TAG, "offline recognizer ready in ${SystemClock.elapsedRealtime() - t0} ms (${model.id}, lang=$lang)")
        cachedKey = key
        offline = r
        return r
    }

    @Synchronized
    fun online(ctx: Context, model: AsrModel): OnlineRecognizer {
        val key = "online:${model.id}"
        if (key == cachedKey) online?.let { return it }
        clearLocked()
        val t0 = SystemClock.elapsedRealtime()
        val r = buildOnline(ctx, model)
        Log.i(TAG, "online recognizer ready in ${SystemClock.elapsedRealtime() - t0} ms (${model.id})")
        cachedKey = key
        online = r
        return r
    }

    @Synchronized
    fun clear() = clearLocked()

    private fun clearLocked() {
        runCatching { offline?.release() }
        runCatching { online?.release() }
        offline = null
        online = null
        cachedKey = null
    }

    private fun pathOf(ctx: Context, model: AsrModel, prefix: String): String {
        val store = ModelStore(ctx)
        val f = model.files.firstOrNull { it.name.startsWith(prefix) }
            ?: throw IllegalStateException("model ${model.id} missing file *$prefix*")
        return store.fileOf(model, f).absolutePath
    }

    private fun buildOffline(ctx: Context, model: AsrModel, lang: String): OfflineRecognizer {
        val store = ModelStore(ctx)
        val tokens = model.files.first { it.name == "tokens.txt" }
        val mc = OfflineModelConfig().apply {
            this.tokens = store.fileOf(model, tokens).absolutePath
            numThreads = 2
            debug = false
            provider = "cpu"
        }
        when (model.kind) {
            ModelKind.SENSE_VOICE -> {
                mc.modelType = "sensevoice"
                mc.senseVoice = OfflineSenseVoiceModelConfig().apply {
                    this.model = pathOf(ctx, model, "model")
                    this.language = lang
                    useInverseTextNormalization = true
                }
            }
            ModelKind.PARAFORMER -> {
                mc.modelType = "paraformer"
                mc.paraformer = OfflineParaformerModelConfig().apply {
                    this.model = pathOf(ctx, model, "model")
                }
            }
            else -> throw IllegalArgumentException("${model.id} is not an offline model")
        }
        val cfg = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig(SAMPLE_RATE, 80, 0.0f)
            modelConfig = mc
            decodingMethod = "greedy_search"
        }
        // 模型放在外部私有目录（SD 卡路径）：必须传 null AssetManager，
        // 否则 sherpa 原生层会拒绝加载并 abort 进程（file-utils.cc: ReadFile）。
        return OfflineRecognizer(null, cfg)
    }

    private fun buildOnline(ctx: Context, model: AsrModel): OnlineRecognizer {
        val store = ModelStore(ctx)
        val tokens = model.files.first { it.name == "tokens.txt" }
        val transducer = OnlineTransducerModelConfig().apply {
            encoder = pathOf(ctx, model, "encoder")
            decoder = pathOf(ctx, model, "decoder")
            joiner = pathOf(ctx, model, "joiner")
        }
        val mc = OnlineModelConfig().apply {
            this.transducer = transducer
            this.tokens = store.fileOf(model, tokens).absolutePath
            numThreads = 2
            debug = false
            provider = "cpu"
            modelType = "zipformer"
        }
        val cfg = OnlineRecognizerConfig().apply {
            featConfig = FeatureConfig(SAMPLE_RATE, 80, 0.0f)
            modelConfig = mc
            decodingMethod = "greedy_search"
            enableEndpoint = true
            // 端点判定参数（v0.4.1 提速，主人反馈"不够实时"）：
            //  sherpa 的 EndpointRule(必须含非静音, 尾部静音秒数, 最小长度秒数)
            //  旧值 rule1=2.4s 太保守 —— 说完话要等 2.4 秒才判定结束，感觉拖沓。
            //  新值 1.2s：正常语速下句间停顿约 0.5~1s，1.2s 既不会切句又明显更快。
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 1.2f, 0.0f),   // 说完后的静音判定
                rule2 = EndpointRule(true, 0.8f, 0.0f),    // 一直没说话时的判定
                rule3 = EndpointRule(false, 0.0f, 20.0f),  // 超长兜底
            )
        }
        // 同上：绝对路径 + null assetManager
        return OnlineRecognizer(null, cfg)
    }
}

/** 一次识别会话：录音 → 送入引擎 → 回调结果。运行在独立线程，避免阻塞服务主线程。 */
class CaptureSession(
    private val ctx: Context,
    private val callback: android.speech.RecognitionService.Callback,
    private val model: AsrModel,
    private val params: RequestParams,
    private val langCode: String,
    private val onDone: (CaptureSession) -> Unit,
) {

    private val stopFlag = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)
    private var thread: Thread? = null
    private var released = false

    fun start() {
        thread = Thread({ safeRun() }, "asr-capture").apply {
            isDaemon = true
            start()
        }
    }

    /** 调用方 stopListening()：把已有音频当作"用户说完"处理，返回最终结果 */
    fun requestStop() = stopFlag.set(true)

    /** 调用方 cancel()：丢弃结果，立即结束 */
    fun cancel() {
        cancelFlag.set(true)
        stopFlag.set(true)
    }

    private fun safeRun() {
        try {
            run()
        } catch (t: Throwable) {
            Log.e(TAG, "session failed: ${t.message}", t)
            if (!cancelFlag.get()) safeError(errorFor(t))
        } finally {
            released = true
            onDone(this)
        }
    }

    private fun errorFor(t: Throwable): Int = when (t) {
        is SecurityException -> android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        is IllegalStateException -> android.speech.SpeechRecognizer.ERROR_AUDIO
        else -> android.speech.SpeechRecognizer.ERROR_AUDIO
    }

    private fun run() {
        Log.i(TAG, "session start: model=${model.id} streaming=${model.streaming} lang=$langCode params=$params")
        val recorder = openRecorder()
        var ok = false
        try {
            recorder.startRecording()
            ok = true
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            runCatching { recorder.release() }
            safeError(android.speech.SpeechRecognizer.ERROR_AUDIO)
            return
        }
        try {
            safe { callback.readyForSpeech(android.os.Bundle()) }
            if (model.streaming) runStreaming(recorder) else runOffline(recorder)
        } finally {
            if (ok) {
                runCatching { recorder.stop() }
            }
            runCatching { recorder.release() }
            Log.i(TAG, "session end: model=${model.id}")
        }
    }

    // ------------------------------------------------------------------ 离线

    private fun runOffline(recorder: AudioRecord) {
        val recognizer = EngineHolder.offline(ctx, model, langCode)
        val buf = FloatBuffer()
        val chunk = ShortArray(CHUNK_SHORTS)
        // sherpa Recognizer 非线程安全：中间解码线程与最终解码共用一把锁串行化
        val engineLock = Any()
        val partial = PartialDecoder { samples -> synchronized(engineLock) { decodeOffline(recognizer, samples) } }
        var noiseFloor = 0.004f
        var speechStarted = false
        var voiceRun = 0
        var lastVoiceAt = 0L
        var lastPartialAt = 0L
        val startAt = SystemClock.elapsedRealtime()
        var reason = "loop"
        partial.start()

        while (true) {
            if (cancelFlag.get()) { reason = "cancel"; break }
            val n = recorder.read(chunk, 0, chunk.size)
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                    throw IllegalStateException("AudioRecord.read error $n")
                }
                continue
            }
            val now = SystemClock.elapsedRealtime()
            val samples = FloatArray(n) { chunk[it] / 32768f }
            buf.append(samples)

            val level = AudioUtil.rms(samples)
            if (level < noiseFloor) noiseFloor = noiseFloor * 0.92f + level * 0.08f
            val threshold = max(0.012f, noiseFloor * 3.0f)
            if (level > threshold) {
                voiceRun++
                lastVoiceAt = now
                if (!speechStarted && voiceRun >= 2) {
                    speechStarted = true
                    Log.i(TAG, "speech detected (level=$level floor=$noiseFloor)")
                    safe { callback.beginningOfSpeech() }
                }
            } else {
                voiceRun = 0
            }
            if (speechStarted) safe { callback.rmsChanged(AudioUtil.db(level)) }

            if (stopFlag.get()) { reason = "stop"; break }
            if (!speechStarted && now - startAt > params.noSpeechTimeoutMs) { reason = "no-speech"; break }
            if (speechStarted && now - lastVoiceAt > params.silenceMs) { reason = "silence"; break }
            if (now - startAt > params.maxUtteranceMs) { reason = "max-length"; break }

            if (params.partialsWanted && speechStarted && buf.size > SAMPLE_RATE && now - lastPartialAt > PARTIAL_INTERVAL_MS) {
                lastPartialAt = now
                partial.submit(buf.snapshot())
            }
        }

        // 先等中间解码器退出，再独占 recognizer 做最终解码（sherpa recognizer 非线程安全）
        partial.stopAndJoin(8_000)

        if (cancelFlag.get()) return

        if (reason == "no-speech") {
            Log.i(TAG, "no speech detected within ${params.noSpeechTimeoutMs} ms")
            safeError(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            return
        }
        val all = buf.toFloatArray()
        val padded = FloatArray(all.size + SAMPLE_RATE / 3)      // 尾部补 300ms 静音，帮模型收尾
        System.arraycopy(all, 0, padded, 0, all.size)
        val text = if (padded.size > SAMPLE_RATE / 2) runCatching {
            synchronized(engineLock) { decodeOffline(recognizer, padded) }
        }.onFailure { Log.e(TAG, "final decode failed", it) }.getOrDefault("")
        else ""
        Log.i(TAG, "final text ($reason): $text")
        deliver(text, speechStarted)
    }

    private fun decodeOffline(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            runCatching { stream.release() }
        }
    }

    // ------------------------------------------------------------------ 流式

    private fun runStreaming(recorder: AudioRecord) {
        val recognizer = EngineHolder.online(ctx, model)
        val stream = recognizer.createStream("")
        val chunk = ShortArray(CHUNK_SHORTS)
        var noiseFloor = 0.004f
        var speechStarted = false
        var voiceRun = 0
        var lastVoiceAt = 0L
        var lastPartial = ""
        val startAt = SystemClock.elapsedRealtime()
        var reason = "loop"
        try {
            while (true) {
                if (cancelFlag.get()) { reason = "cancel"; break }
                val n = recorder.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val now = SystemClock.elapsedRealtime()
                val samples = FloatArray(n) { chunk[it] / 32768f }
                stream.acceptWaveform(samples, SAMPLE_RATE)

                val level = AudioUtil.rms(samples)
                if (level < noiseFloor) noiseFloor = noiseFloor * 0.92f + level * 0.08f
                val threshold = max(0.012f, noiseFloor * 3.0f)
                if (level > threshold) {
                    voiceRun++
                    lastVoiceAt = now
                    if (!speechStarted && voiceRun >= 2) {
                        speechStarted = true
                        safe { callback.beginningOfSpeech() }
                    }
                } else {
                    voiceRun = 0
                }

                while (recognizer.isReady(stream)) recognizer.decode(stream)

                val text = runCatching { recognizer.getResult(stream).text.trim() }.getOrDefault("")
                if (params.partialsWanted && text.isNotEmpty() && text != lastPartial) {
                    lastPartial = text
                    safe { callback.partialResults(resultBundle(text)) }
                }

                if (recognizer.isEndpoint(stream)) { reason = "endpoint"; break }
                if (stopFlag.get()) { reason = "stop"; break }
                if (!speechStarted && now - startAt > params.noSpeechTimeoutMs) { reason = "no-speech"; break }
                if (speechStarted && now - lastVoiceAt > params.silenceMs + 800) { reason = "silence"; break }
                if (now - startAt > params.maxUtteranceMs) { reason = "max-length"; break }
            }

            if (cancelFlag.get()) return
            if (reason == "no-speech") {
                safeError(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                return
            }
            runCatching { stream.inputFinished() }
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val text = runCatching { recognizer.getResult(stream).text.trim() }.getOrDefault("")
            Log.i(TAG, "final text ($reason): $text")
            deliver(text, speechStarted)
        } finally {
            runCatching { stream.release() }
        }
    }

    // ------------------------------------------------------------------ 公共

    private fun deliver(text: String, speechStarted: Boolean) {
        if (text.isEmpty()) {
            safeError(android.speech.SpeechRecognizer.ERROR_NO_MATCH)
            return
        }
        if (speechStarted) safe { callback.endOfSpeech() }
        safe { callback.results(resultBundle(text)) }
    }

    private fun resultBundle(text: String): android.os.Bundle = android.os.Bundle().apply {
        putStringArrayList(
            android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
            arrayListOf(text),
        )
        putFloatArray(android.speech.SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(-1f))
        putString(EXTRA_ENGINE, "${model.name} · sherpa-onnx")
    }

    private fun safeError(code: Int) {
        Log.i(TAG, "error($code)")
        safe { callback.error(code) }
    }

    private fun safe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "callback failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ 录音

    private fun openRecorder(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) throw IllegalStateException("getMinBufferSize=$minBuf")
        val bufferBytes = max(minBuf, SAMPLE_RATE * 2 * 4)   // 约 4 秒余量
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val source = MediaRecorder.AudioSource.VOICE_RECOGNITION

        // API 31+：按官方建议把麦克风访问归因到调用方（识别失败则退回普通上下文）
        if (Build.VERSION.SDK_INT >= 31) {
            val attributed = runCatching {
                val attribCtx = ctx.createContext(
                    android.content.ContextParams.Builder()
                        .setNextAttributionSource(callback.callingAttributionSource)
                        .build(),
                )
                AudioRecord.Builder()
                    .setContext(attribCtx)
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            }.getOrNull()
            if (attributed != null && attributed.state == AudioRecord.STATE_INITIALIZED) return attributed
            attributed?.release()
        }

        val rec = runCatching {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }.getOrElse {
            @Suppress("DEPRECATION")
            AudioRecord(
                source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes,
            )
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            throw IllegalStateException("AudioRecord not initialized")
        }
        return rec
    }

    companion object {
        private const val TAG = "CaptureSession"
        private const val CHUNK_SHORTS = SAMPLE_RATE / 10          // 100ms
        private const val PARTIAL_INTERVAL_MS = 1_200L
        const val EXTRA_ENGINE = "app.dsh.asr.ENGINE"
    }
}

/** 只增不改的 float 环形缓冲（会话内音频累积） */
class FloatBuffer(initialCapacity: Int = SAMPLE_RATE * 8) {
    private var data = FloatArray(initialCapacity)
    var size = 0
        private set

    fun append(samples: FloatArray) {
        if (size + samples.size > data.size) {
            var cap = data.size * 2
            while (cap < size + samples.size) cap *= 2
            data = data.copyOf(cap)
        }
        System.arraycopy(samples, 0, data, size, samples.size)
        size += samples.size
    }

    fun snapshot(): FloatArray = data.copyOf(size)

    fun toFloatArray(): FloatArray = data.copyOf(size)
}

/**
 * 离线模型的"中间结果"解码线程：只处理最新快照，避免阻塞录音线程丢音频。
 * 同一个 Recognizer 不能被并发调用，本线程在会话结束前会被 join。
 */
class PartialDecoder(private val decode: (FloatArray) -> String) {

    private val lock = Object()
    private var pending: FloatArray? = null
    private var stopped = false
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ loop() }, "asr-partial").apply {
            isDaemon = true
            start()
        }
    }

    fun submit(samples: FloatArray) {
        synchronized(lock) {
            pending = samples
            lock.notifyAll()
        }
    }

    fun stopAndJoin(timeoutMs: Long) {
        synchronized(lock) {
            stopped = true
            lock.notifyAll()
        }
        runCatching { thread?.join(timeoutMs) }
    }

    private fun loop() {
        while (true) {
            var snap: FloatArray? = null
            synchronized(lock) {
                while (!stopped && pending == null) {
                    runCatching { lock.wait(500) }
                }
                if (stopped) return
                snap = pending
                pending = null
            }
            val samples = snap ?: continue
            val text = runCatching { decode(samples) }
                .onFailure { Log.w("PartialDecoder", "partial decode failed: ${it.message}") }
                .getOrDefault("")
            if (text.isNotEmpty()) Log.i("PartialDecoder", "partial: $text")
        }
    }
}

/** 离线引擎自检：直接用 WAV 文件跑推理，用于在没有麦克风的模拟器上验证链路 */
object EngineSelfTest {

    data class Result(val text: String, val decodeMs: Long, val audioSec: Float, val ok: Boolean, val error: String?)

    fun decodeWav(ctx: Context, model: AsrModel, wav: File, lang: String): Result {
        return try {
            val samples = AudioUtil.readWavFloats(wav)
            val engine = if (model.streaming) null else EngineHolder.offline(ctx, model, lang)
            val t0 = SystemClock.elapsedRealtime()
            val text = if (model.streaming) {
                val recognizer = EngineHolder.online(ctx, model)
                val stream: OnlineStream = recognizer.createStream("")
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    recognizer.getResult(stream).text.trim()
                } finally {
                    runCatching { stream.release() }
                }
            } else {
                val stream = engine!!.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    engine.decode(stream)
                    engine.getResult(stream).text.trim()
                } finally {
                    runCatching { stream.release() }
                }
            }
            val ms = SystemClock.elapsedRealtime() - t0
            Result(text, ms, samples.size.toFloat() / SAMPLE_RATE, true, null)
        } catch (t: Throwable) {
            Log.e("EngineSelfTest", "failed", t)
            Result("", 0, 0f, false, t.message ?: t.javaClass.simpleName)
        }
    }
}
