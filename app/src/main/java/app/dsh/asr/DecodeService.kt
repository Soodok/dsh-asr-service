package app.dsh.asr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * 外部音频文件识别服务 —— 供其他 App（如《资料库》伴侣端）喂入 WAV 并取回文本。
 *
 * ## 为什么是 Service 而不是 Activity
 * 首发实现用的是 `DecodeActivity`，真机失败于 **Android 10+ 的后台启动限制**：
 * ```
 * Background activity launch blocked! ... callingUidProcState: RECEIVER;
 * callingUidHasVisibleActivity: false ... BAL_BLOCK
 * ```
 * 调用方（手表触发识别的那一刻）必然处于后台，而 `startActivity` 从后台被系统拦死，
 * 连 Activity 都进不去。`bindService` 不受 BAL 约束，因此改为绑定服务。
 *
 * ## 契约
 * - action `app.dsh.asr.DECODE_FILE`
 * - 入参：`wavBase64`（首选，跨 uid 无需共享文件）或 `wav`（同为 app.dsh.asr 可读的绝对路径）
 * - 入参：`lang`（默认 `zh`）、`replyAction`、`replyPackage`
 * - 出口：识别完成后 `sendBroadcast(replyAction)`，携带 `ok` / `text` / `err` / `ms`
 *
 * ## 为什么结果走广播
 * `bindService` 能拿到 Binder，但为了一次性识别专门定义 AIDL 并不划算；
 * 而跨进程广播已经够用（调用方注册动态接收器即可）。
 *
 * ## 为什么服务里还要 startForeground
 * 识别 3 秒音频在本机约 0.5~2 秒，但低端机可能更久；OPPO 的 `hans` 冻结管理器
 * 会在几秒内冻结后台进程。挂前台保住进程态，避免识别中途被杀。
 * `app.dsh.asr` 在系统允许列表里（日志 `code:SYSTEM_ALLOW_LISTED`），从后台
 * 启动前台服务是被放行的。
 */
class DecodeService : Service() {

    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "asr-decode").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        dlog("服务创建")
    }

    /** 同时写 logcat 与 `files/decode.log`：真机 logcat 被系统刷屏，按 tag 过滤常捞不到。 */
    private fun dlog(msg: String) {
        Log.i(TAG, msg)
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        runCatching { File(filesDir, "decode.log").appendText(ts + " " + msg + System.lineSeparator()) }
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent == null) return null
        val action = intent.action ?: return null
        if (action != ACTION_DECODE) return null

        // ★★★ v0.6.5 真机根因（2026-11-03 11:02 对照实验实锤）：
        //   OPPO 的 hans 会冻结「只有 bind 连接、没有 startService 记录」的服务进程，
        //   冻结时机极早 —— 进程刚 attach 就被 `Flag disabled. Ignoring
        //   finishAttachApplication` 挡住（见 AM 日志），onCreate 跑完、onStartCommand
        //   永远不跑，此后所有 startService 投递一律返回 `Error: Not found`，
        //   直到 `kill -9` 清掉那个半启动进程才恢复。
        //   诱发路径：MainActivity 启动时 MicForegroundService 会做一次「常驻绑定」
        //   （FileAsrClient.ensureKeepAlive），把服务进程拉进这个状态；
        //   之后手表真正发语音时，流式三帧全部投递失败。
        //   对照实验：只 kill asr 不动 App → 流式立刻跑通（648ms 出「感冒」）。
        //   修法：onBind 保持正常返回（FileAsrClient 的整段识别仍依赖 Binder.service()），
        //   但把「常驻预热」从 bindService 换成 startForegroundService（见
        //   MicForegroundService.onStartCommand 与 FileAsrClient.ensureKeepAlive）。
        //   由 startService 拉起的进程不会再进这个半启动冻结态（对照实验已证）。
        enterForeground()
        // ★★★ v0.5.7 关键修复（2026-11-03 00:45 真机实锤）：
        //   **业务处理不能放在 onBind 里。**
        //   MicForegroundService 为了保活，已经用同一个 ComponentName 绑过一次；
        //   Android 规定「同一客户端对同一服务再次 bindService 时不再调用 onBind」，
        //   于是第二次及以后的请求参数（wavBase64）永远送不进来 ——
        //   服务端 decode.log 一条记录都没有，客户端只能干等 12s 超时，
        //   用户看到的就是「取消之后再点语音，说什么都回答没听见」。
        //   现在 onBind 只负责建前景通知与返回句柄，真正的请求走 onStartCommand。
        return Binder(this)
    }

    /**
     * 真正处理识别请求（由 startService 触达）。
     *
     * 为什么用 startService 而不是 bindService 传参：见 onBind 顶部注释，
     * 重复 bind 不会重新调 onBind，参数必然丢。startService 每次都会新建一次
     * onStartCommand 调用，参数完整可达。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        // ★ 诊断：确认 intent 到底有没有到服务（流式三动作调试期保留，稳定后删）
        dlog("onStartCommand action=${intent.action} startId=$startId")
        enterForeground()
        when (intent.action) {
            ACTION_PREWARM -> dlog("预热到达（进程已在跑）")
            ACTION_DECODE -> handleDecode(intent)
            // 全部丢到单线程池：建引擎 0.8s 也不阻塞主线程，分片不会丢
            ACTION_STREAM_START -> streamExec.execute { runCatching { handleStreamStart(intent) }
                .onFailure { Log.e(TAG, "STREAM_START 异常", it) } }
            ACTION_STREAM_CHUNK -> streamExec.execute { runCatching { handleStreamChunk(intent) }
                .onFailure { Log.e(TAG, "STREAM_CHUNK 异常", it) } }
            ACTION_STREAM_END -> streamExec.execute { runCatching { handleStreamEnd(intent) }
                .onFailure { Log.e(TAG, "STREAM_END 异常", it) } }
            else -> return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    // ══════════════════════════════════════════════════════════════
    //  流式识别（v0.6.0）
    // ══════════════════════════════════════════════════════════════
    //
    // 手表端边录边传，手机端边收边喂引擎，文本一变就回一帧 partial。
    // 这样用户说话时手表上就能看到字在长，而不是等录完再等整段识别。

    private fun handleStreamStart(intent: Intent) {
        val seq = intent.getIntExtra("seq", 0)
        val lang = intent.getStringExtra("lang")?.ifBlank { null } ?: "zh"
        val messenger = readMessenger(intent)
        dlog("流式开始：seq=$seq")

        // 上一段没结束就来了新的 → 先把它彻底作废，别让两个会话抢引擎。
        streamSession?.let {
            dlog("上一流式会话未结束，先中止")
            runCatching { it.abort() }
        }
        streamSession = null
        streamSeq = seq
        streamReplyMessenger = messenger
        streamReplyAction = intent.getStringExtra("replyAction")
        streamReplyPackage = intent.getStringExtra("replyPackage")
        streamAccum = java.io.ByteArrayOutputStream(64 * 1024)
        streamLastPartial = ""
        streamChunks = 0
        streamFirstChunkAt = 0
        if (messenger == null && streamReplyAction.isNullOrBlank()) {
            dlog("流式开始：没有回传通道（Messenger/广播都没有），partial 无法送到调用方")
        }
        // 模型选择与整段识别一致：按语言挑第一个可用的
        val model = ModelPicker.pick(Prefs(this), ModelStore(this), lang)
        if (model == null) {
            dlog("流式开始：没有可用的识别模型，会话作废")
            cleanupStreamReply()
            sendResult("", 0)
            stopSelfSafely()
            return
        }
        if (!model.streaming) {
            // 非流式模型也能跑（sherpa 的 OnlineRecognizer 只认流式模型），
            // 这里如实记录，失败时日志里能看出是模型选错而非链路问题。
            dlog("流式开始：警告 model=${model.id} 不是流式模型（kind=${model.kind}），可能无法出字")
        }
        dlog("流式开始：model=${model.id} streaming=${model.streaming}")
        streamSession = StreamSession(this, model)
    }

    private fun handleStreamChunk(intent: Intent) {
        val seq = intent.getIntExtra("seq", 0)
        val sess = streamSession
        if (sess == null) {
            dlog("流式分片到达但没有活跃会话（seq=$seq），丢弃")
            return
        }
        if (seq != streamSeq) {
            dlog("流式分片 seq=$seq 与当前 ${streamSeq} 不符，丢弃")
            return
        }
        val pcm = intent.getByteArrayExtra("pcm")
            ?: runCatching { android.util.Base64.decode(intent.getStringExtra("pcmBase64") ?: "", android.util.Base64.DEFAULT) }.getOrNull()
        if (pcm == null || pcm.isEmpty()) {
            dlog("流式分片为空（seq=$seq），跳过")
            return
        }
        if (streamFirstChunkAt == 0L) streamFirstChunkAt = System.currentTimeMillis()
        streamChunks++
        streamAccum?.write(pcm)

        // ★ 关键：喂引擎并回 partial。文本没变不重复回，避免刷屏。
        val text = sess.accept(pcm) { partial ->
            if (partial.isNotEmpty() && partial != streamLastPartial) {
                streamLastPartial = partial
                sendPartial(partial)
            }
        }
        if (text.isNotEmpty() && text != streamLastPartial) {
            streamLastPartial = text
            sendPartial(text)
        }
    }

    private fun handleStreamEnd(intent: Intent) {
        doStreamEnd(intent.getIntExtra("seq", 0))
    }

    /** 流式收尾的真实实现，startService 与 Binder 两条路径共用。 */
    private fun doStreamEnd(seq: Int) {
        val sess = streamSession
        if (sess == null) {
            dlog("流式结束但没有活跃会话（seq=$seq）")
            return
        }
        val t0 = streamFirstChunkAt
        val elapsed = if (t0 > 0) System.currentTimeMillis() - t0 else 0L
        val finalText = runCatching { sess.finish() }.getOrDefault(streamLastPartial)
        streamSession = null

        // 存档一份 WAV：识别出问题时能直接回放定责，也让「存档兜底」可用于二次识别。
        val pcm = streamAccum?.toByteArray() ?: ByteArray(0)
        streamAccum = null
        val saved = if (pcm.isNotEmpty()) runCatching {
            writeWavFromPcm(
                pcm,
                File(cacheDir, "stream_${seq}_${System.currentTimeMillis()}.wav"),
                streamRate,
            )
        }.getOrNull() else null

        val samples = pcm.size / StreamSession.BYTES_PER_SAMPLE
        val audioMs = samples * 1000L / streamRate
        dlog(
            "流式结束：seq=$seq 分片=${streamChunks} 采样=$samples（${audioMs}ms）" +
                " 墙钟=${elapsed}ms 文本=${finalText.length}字[$finalText]" +
                (saved?.let { " wav=${it.name}(${pcm.size}B)" } ?: "")
        )

        sendResult(finalText, elapsed)
        runCatching { cleanupStreamReply() }
        // ★★★ v0.6.6 真机根因（2026-11-03 11:12 三轮对照实验实锤）：
        //   **绝不能在这里 stopSelf()。**
        //   实测：第 1 轮成功（587ms 出「感冒」）；第 2、3 轮服务端 decode.log
        //   一条 onStartCommand 都没有 —— 因为第 1 轮结束时 stopSelf() 把服务停了，
        //   第 2 轮的 startService 需要**重建进程**，而 OPPO hans 会把刚拉起、
        //   尚未 finishAttach 的进程冻结，此后所有投递被静默丢弃
        //   （AM 日志：`Flag disabled. Ignoring finishAttachApplication from uid: 10477`）。
        //   只有 kill -9 换新进程才能恢复。
        //
        //   故流式结束只回收会话与结果通道，服务进程保持前台存活 ——
        //   后续每一轮都在同一个已稳定 attach 的进程里投递，不再触发重建。
        //   代价：常驻一个 dataSync 前台服务（通知栏常驻），这是必要成本；
        //   进程最终由系统内存压力回收，回收后下一次请求又会走一遍冷启动（可接受）。
        // ★★★ v0.6.7 补充（同轮实验第二步）：光「不 stopSelf」还不够。
        //   曾试过 STOP_FOREGROUND_DETACH（撤通知但留服务），结果服务一退出前台
        //   就被 hans 立刻冻住 —— 第 2 轮照样投不进来。dumpsys 里
        //   `isForeground` 一为空，紧接着就是 `proxyService ... DECODE_FILE`。
        //   故此处**保持前台通知常驻**，一行代码都不做。
        //   代价是通知栏长期有一条「正在识别手表音频」，这是让链路可用的必要成本。
        Unit
    }

    private fun cleanupStreamReply() {
        streamCb = null
        streamReplyMessenger = null
        streamReplyAction = null
        streamReplyPackage = null
        streamLastPartial = ""
        streamChunks = 0
        streamFirstChunkAt = 0
    }

    /** 回一帧中间结果（边说边出字） */
    private fun sendPartial(text: String) {
        // ★ v0.7.0：优先 AIDL 回调（binder 直呼，不受 hans 冻结影响）
        val cb0 = streamCb
        if (cb0 != null) {
            val ok = runCatching { cb0.onPartial(streamSeq, text) }.isSuccess
            if (ok) return
        }
        val messenger = streamReplyMessenger
        if (messenger != null) {
            val ok = runCatching {
                messenger.send(android.os.Message.obtain(null, MSG_PARTIAL).apply {
                    data = android.os.Bundle().apply { putString("text", text) }
                })
            }.isSuccess
            if (ok) return
        }
        val act = streamReplyAction ?: return
        runCatching {
            sendBroadcast(Intent(act).apply {
                streamReplyPackage?.let { setPackage(it) }
                putExtra("partial", true)
                putExtra("text", text)
            })
        }
    }

    /** 回最终结果（沿用既有 MSG_RESULT 契约） */
    private fun sendResult(text: String, ms: Long) {
        val cb0 = streamCb
        if (cb0 != null) {
            val ok = runCatching { cb0.onResult(streamSeq, true, text, null, ms) }.isSuccess
            if (ok) {
                dlog("结果已回传（AIDL）ok=true 文本=${text.length}字")
                return
            }
        }
        val messenger = streamReplyMessenger
        if (messenger != null) {
            val ok = runCatching {
                messenger.send(android.os.Message.obtain(null, MSG_RESULT).apply {
                    data = android.os.Bundle().apply {
                        putBoolean("ok", true)
                        putString("text", text)
                        putLong("ms", ms)
                    }
                })
            }.isSuccess
            if (ok) {
                dlog("结果已回传（Messenger）ok=true")
                return
            }
        }
        val act = streamReplyAction ?: return
        runCatching {
            sendBroadcast(Intent(act).apply {
                streamReplyPackage?.let { setPackage(it) }
                putExtra("ok", true)
                putExtra("text", text)
                putExtra("ms", ms)
            })
            dlog("结果已回传（广播兜底）ok=true")
        }
    }

    private fun readMessenger(intent: Intent): android.os.Messenger? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("replyMessenger", android.os.Messenger::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("replyMessenger") as? android.os.Messenger
    }.getOrNull()

    /** 识别主流程（原 onBind 的业务体，抽出以便两条路径复用） */
    private fun handleDecode(intent: Intent) {

        val replyAction = intent.getStringExtra("replyAction")
        val replyPackage = intent.getStringExtra("replyPackage")
        // ★ Messenger 回传（首选）：跨进程广播在国产 ROM 上会被冻结管理器挂起/延迟，
        //   实测识别 144ms 就完成、广播却始终收不到（调用方 12s 超时）。
        //   Messenger 是纯 Binder 调用，不经过广播队列，最可靠。
        val replyMessenger = runCatching {
            if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra("replyMessenger", android.os.Messenger::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra("replyMessenger") as? android.os.Messenger
        }.getOrNull()
        val lang = intent.getStringExtra("lang")?.ifBlank { null } ?: "zh"

        // 源：优先 base64（跨 uid 可用），其次路径
        val src: File? = run {
            val b64 = intent.getStringExtra("wavBase64")
            if (!b64.isNullOrBlank()) {
                runCatching {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    if (bytes.size <= 44) return@run null
                    val f = File(cacheDir, "decode_in_${System.currentTimeMillis()}.wav")
                    f.writeBytes(bytes)
                    f
                }.getOrElse {
                    finishWith(replyAction, replyPackage, replyMessenger, false, "", "写入缓存失败：${it.message}", 0)
                    return@run null
                }
            } else {
                val p = intent.getStringExtra("wav")
                if (p.isNullOrBlank()) {
                    finishWith(replyAction, replyPackage, replyMessenger, false, "", "缺少 wav 或 wavBase64 参数", 0)
                    null
                } else File(p)
            }
        }

        if (src == null) {
            stopSelfSafely()
            return
        }
        if (!src.exists() || src.length() <= 44L) {
            finishWith(
                replyAction, replyPackage, replyMessenger, false, "",
                "音频文件不存在或为空（${if (src.exists()) src.length() else -1} 字节）", 0
            )
            stopSelfSafely()
            return
        }

        pool.execute {
            val t0 = System.currentTimeMillis()
            var ok = false
            var text = ""
            var err: String? = null
            try {
                val model = ModelPicker.pick(Prefs(this), ModelStore(this), lang)
                if (model == null) {
                    err = "没有可用的识别模型，请先在 app.dsh.asr 里下载模型"
                } else {
                    dlog("开始识别：model=${model.id} wav=${src.name} size=${src.length()}B lang=$lang")
                    val r = EngineSelfTest.decodeWav(this, model, src, lang)
                    ok = r.ok
                    text = r.text
                    err = r.error
                }
            } catch (t: Throwable) {
                err = "识别异常：${t.message}"
                Log.e(TAG, "识别异常", t)
            }
            val ms = System.currentTimeMillis() - t0
            if (ms < 0) Log.w(TAG, "时间戳异常")
            dlog("完成：ok=$ok ${ms}ms text=${text.length} 字[$text]${err?.let { " err=$it" } ?: ""}")
            finishWith(replyAction, replyPackage, replyMessenger, ok, text, err, ms)
            runCatching { src.delete() }
            stopSelfSafely()
        }
    }

    /** 只用作「已绑定」的句柄，调用方不需要通过它通信（结果走广播）。 */
    /**
     * ★★★ v0.7.0（2026-11-03 11:24 真机定案）：流式三动作改走 **Binder 直呼**，
     * 不再用 startService。
     *
     * 根因：OPPO hans 按 uid 冻结进程（`/sys/fs/cgroup/apps/uid_10477/cgroup.freeze = 1`）。
     * 被冻后 `startService` 的 intent 停在 AM 队列里**不投递、也不报错** ——
     * 客户端以为发成功了（打「流式会话已启动」），服务端 onStartCommand 一条都收不到，
     * 直到 kill -9 换新进程。手工解冻后立刻恢复，是这一判断的决定性证据。
     *
     * 而 binder 事务（transact）会**穿透冻结**：冻结只拦「进程调度」，不拦内核 binder 驱动
     * 的同步事务。所以经常驻 bindService 拿到的这个 Binder 对象，直接同步调用即可。
     *
     * 顺带好处：省掉每片一次的 intent 跨进程打包（原来 15 片 = 15 次 startService），
     * 一次直接方法调用就完事，延迟更低。
     */
    inner class Binder(private val svc: DecodeService) : IStreamAsr.Stub() {
        fun service(): DecodeService = svc

        override fun beginStream(seq: Int, lang: String?, sampleRate: Int, cb: IStreamCallback?) {
            svc.streamBegin(seq, lang, sampleRate, cb)
        }

        override fun feedPcm(seq: Int, pcm: ByteArray?) {
            svc.streamFeed(seq, pcm ?: return)
        }

        override fun endStream(seq: Int) {
            svc.streamEnd(seq)
        }
    }

    // 供 Binder 调用的三个入口：与 handleStream* 共用同一实现，只是不走 Intent。
    /** 本次流式会话的输入采样率（写存档 WAV 与日志用） */
    @Volatile private var streamRate: Int = 16_000

    private fun streamBegin(seq: Int, lang: String?, sampleRate: Int, cb: IStreamCallback?) {
        streamExec.execute {
            runCatching {
                streamSession?.let { runCatching { it.abort() } }
                streamSession = null
                streamSeq = seq
                streamCb = cb
                streamAccum = java.io.ByteArrayOutputStream(64 * 1024)
                streamLastPartial = ""
                streamChunks = 0
                streamFirstChunkAt = 0
                val m = ModelPicker.pick(Prefs(this), ModelStore(this), lang ?: "zh")
                if (m == null) {
                    dlog("流式开始(binder)：没有可用模型，会话作废")
                    cleanupStreamReply(); sendResult("", 0)
                    return@runCatching
                }
                val sr = if (sampleRate in 6000..48000) sampleRate else 16_000
                streamRate = sr
                dlog("流式开始(binder)：seq=$seq 模型=${m.id} 流式=${m.streaming} 采样率=${sr}Hz")
                streamSession = StreamSession(this, m, sr)
            }.onFailure { Log.e(TAG, "beginStream 异常", it) }
        }
    }

    private fun streamFeed(seq: Int, pcm: ByteArray) {
        streamExec.execute {
            runCatching {
                val sess = streamSession ?: return@runCatching
                if (seq != streamSeq) return@runCatching
                if (pcm.isEmpty()) return@runCatching
                if (streamFirstChunkAt == 0L) streamFirstChunkAt = System.currentTimeMillis()
                streamChunks++
                streamAccum?.write(pcm)
                val text = sess.accept(pcm) { partial ->
                    if (partial.isNotEmpty() && partial != streamLastPartial) {
                        streamLastPartial = partial
                        sendPartial(partial)
                    }
                }
                if (text.isNotEmpty() && text != streamLastPartial) {
                    streamLastPartial = text
                    sendPartial(text)
                }
            }.onFailure { Log.e(TAG, "feedPcm 异常", it) }
        }
    }

    private fun streamEnd(seq: Int) {
        streamExec.execute {
            runCatching { doStreamEnd(seq) }.onFailure { Log.e(TAG, "endStream 异常", it) }
        }
    }

    /**
     * ★★★ v0.7.3 崩溃修复（2026-11-03 23:48 真机）：
     *
     * 真机日志：
     *   `java.lang.RuntimeException: invalid channel for service notification:
     *    Notification(channel=dsh_asr_listening ...)` → 进程直接崩。
     *
     * 根因：`Notification.Builder(this, CHANNEL)` 用的渠道由
     * `AsrRecognitionService` 在其 onCreate 里创建，而本服务
     * （DecodeService）**可以不经 AsrRecognitionService 就被拉起**
     * ——尤其是「卸载重装」之后，通知渠道随应用数据一起被删除，
     * 而系统又因为旧的 bind 记录尝试重新拉起本服务，
     * 于是渠道不存在 → startForeground 抛异常 → 崩。
     *
     * 修法：在自己进前台之前确保渠道存在（幂等，创建已存在是空操作）。
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(AsrRecognitionService.CHANNEL) != null) return
            val ch = NotificationChannel(
                AsrRecognitionService.CHANNEL,
                getString(R.string.notif_listening_title),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            nm.createNotificationChannel(ch)
            Log.i(TAG, "通知渠道已补建：${AsrRecognitionService.CHANNEL}")
        }.onFailure { Log.w(TAG, "补建通知渠道失败：${it.message}") }
    }

    private fun enterForeground() {
        // ★ 必须先确保渠道存在，否则 startForeground 抛
        //   `invalid channel for service notification` 直接崩进程。
        ensureChannel()
        runCatching {
            val n = Notification.Builder(this, AsrRecognitionService.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(getString(R.string.notif_listening_title))
                .setContentText("正在识别手表音频")
                .setOngoing(true)
                .build()
            // ★ 类型必须是 dataSync 不能是 microphone：本服务只解码已有音频文件，
            //   完全不碰麦克风。用 microphone 会被系统按 while-in-use 规则拒绝：
            //   真机日志 `Foreground service started from background can not have
            //   location/camera/microphone access: service app.dsh.asr/.DecodeService`。
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    AsrRecognitionService.NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(AsrRecognitionService.NOTIF_ID, n)
            }
            foregroundOk = true
        }.onFailure {
            // ★★★ v0.7.4 崩溃修复（2026-11-03 23:50 真机）：
            //
            //   用户在这台 OPPO 上把本应用的通知总开关关了（`importance=NONE`），
            //   而 `pm grant POST_NOTIFICATIONS` 又被系统拒（SecurityException，
            //   连 root 都不给）。通知被禁用时系统认为任何渠道都无效，于是
            //   `startForeground` 抛 `invalid channel for service notification`
            //   → 进程崩 → 识别服务永远起不来 → 宝宝看到「语音识别应用打不开」。
            //
            //   但**本服务的核心功能（解码音频文件）根本不需要通知**，通知只是
            //   为了拿「前台服务」这个身份让进程不被冻结。所以：
            //     · 进前台失败不该崩，降级为普通后台服务继续干活；
            //     · 只要 binder 已经通过常驻绑定连着，前台身份并非必需
            //       （这正是之前 AIDL 直呼能穿透冻结的原因）。
            Log.w(TAG, "进前台失败（降级为后台服务继续）：${it.message}")
            foregroundOk = false
        }
    }

    /** 是否成功取得前台身份（false 时服务仍可工作，只是更容易被冻结） */
    @Volatile private var foregroundOk = false

    private fun stopSelfSafely() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
        runCatching { Notifications.cancelListening(this) }
        stopSelf()
    }

    private fun finishWith(
        replyAction: String?, replyPackage: String?, replyMessenger: android.os.Messenger?,
        ok: Boolean, text: String, err: String?, ms: Long
    ) {
        // 优先 Messenger（Binder 直达），广播只作兜底
        if (replyMessenger != null) {
            val sent = runCatching {
                val data = android.os.Bundle().apply {
                    putBoolean("ok", ok)
                    putString("text", text)
                    putString("err", err)
                    putLong("ms", ms)
                }
                replyMessenger.send(android.os.Message.obtain(null, MSG_RESULT).apply { this.data = data })
            }
            if (sent.isSuccess) {
                dlog("结果已回传（Messenger）ok=$ok")
                return
            }
            dlog("Messenger 回传失败：${sent.exceptionOrNull()?.message}")
        }
        if (replyAction.isNullOrBlank()) return
        runCatching {
            val i = Intent(replyAction).apply {
                replyPackage?.let { setPackage(it) }
                putExtra("ok", ok)
                putExtra("text", text)
                putExtra("err", err)
                putExtra("ms", ms)
            }
            sendBroadcast(i)
            dlog("结果已回传（广播兜底）ok=$ok")
        }.onFailure { Log.w(TAG, "回传广播失败：${it.message}") }
    }

    override fun onDestroy() {
        runCatching { pool.shutdownNow() }
        runCatching { streamExec.shutdownNow() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AsrDecode"
        const val ACTION_DECODE = "app.dsh.asr.DECODE_FILE"

        // ── 流式识别（v0.6.0）──────────────────────────────────────────
        // 与整段识别并存：整段仍走 ACTION_DECODE，流式走下面三个。
        // 手表端每收一片 PCM 就 STREAM_CHUNK 一次，手机边喂边回 partial。
        /** ★ v0.6.5：只做「把进程拉起来并置前台」的预热动作，无业务。
         *  调用方（vela.Databank 的 MicForegroundService）用它替代过去的 bindService 常驻绑定，
         *  避免服务进程落入 OPPO hans 的半启动冻结态（见 onBind 顶部根因）。 */
        const val ACTION_PREWARM = "app.dsh.asr.PREWARM"
        const val ACTION_STREAM_START = "app.dsh.asr.STREAM_START"
        const val ACTION_STREAM_CHUNK = "app.dsh.asr.STREAM_CHUNK"
        const val ACTION_STREAM_END = "app.dsh.asr.STREAM_END"

        /** 回传最终结果的消息 what 值 */
        const val MSG_RESULT = 1
        /** 回传中间结果（边说边出字）的消息 what 值 */
        const val MSG_PARTIAL = 2
    }

    // ── 流式会话状态 ────────────────────────────────────────────────
    // 同一时刻只允许一个流式会话（sherpa Recognizer 非线程安全）。
    @Volatile private var streamSession: StreamSession? = null
    @Volatile private var streamSeq: Int = 0
    @Volatile private var streamReplyMessenger: android.os.Messenger? = null
    @Volatile private var streamReplyAction: String? = null
    @Volatile private var streamReplyPackage: String? = null
    @Volatile private var streamAccum: java.io.ByteArrayOutputStream? = null
    @Volatile private var streamLastPartial: String = ""
    @Volatile private var streamFirstChunkAt: Long = 0
    @Volatile private var streamChunks: Int = 0
    /** ★ v0.7.0：AIDL 回调（替代 v0.6.x 的 Messenger/广播双通道）。 */
    @Volatile private var streamCb: IStreamCallback? = null

    // ★★★ v0.6.3 真机根因（2026-11-03 10:03 logcat 实锤）：
    //   `EngineHolder.online()` 建引擎要 811ms，而 `handleStreamChunk` 是**同步**跑的，
    //   于是 onStartCommand 在主线程上被卡住 0.8 秒 —— 这期间手表送来的下一片
    //   `STREAM_CHUNK` 投递被系统丢掉（日志里只有 startId=2，没有 3）。
    //   录音分片 94ms 一片，0.8 秒卡顿至少丢 8 片，等于把开头那段音频整段吞掉。
    //   修法：流式处理全部丢到**单线程池**，onStartCommand 立刻返回。
    //   单线程保证 sherpa（非线程安全）的调用天然串行。
    private val streamExec: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "asr-stream").apply { isDaemon = true }
        }
}
