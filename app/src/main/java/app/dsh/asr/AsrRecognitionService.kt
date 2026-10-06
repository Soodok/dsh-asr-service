package app.dsh.asr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 系统级离线语音识别服务。
 *
 * 任何应用通过 `android.speech.SpeechRecognizer`（默认识别服务）就能调用本服务，
 * 识别全程在本机完成（sherpa-onnx + 本地模型），不联网、不上传音频。
 */
class AsrRecognitionService : RecognitionService() {

    @Volatile
    private var session: CaptureSession? = null

    private val lock = Any()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        Log.i(TAG, "service created")
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val prefs = Prefs(this)
        val params = parseParams(recognizerIntent, prefs)
        val store = ModelStore(this)
        val model = ModelPicker.pick(prefs, store, params.langHint)
        Log.i(TAG, "onStartListening lang=${params.langHint} partials=${params.partialsWanted} model=${model?.id}")

        if (model == null) {
            // 一个模型都没下载：明确告诉调用方"语言/模型不可用"，并提示用户去下载
            Notifications.notifyNoModel(this)
            runCatching { listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }
            return
        }

        val langCode = langCodeFor(params.langHint, model)
        val newSession = CaptureSession(
            ctx = applicationContext,
            callback = listener,
            model = model,
            params = params,
            langCode = langCode,
        ) {
            synchronized(lock) { if (session === it) session = null }
            stopListeningNotification()
        }
        synchronized(lock) {
            session?.cancel()
            session = newSession
        }
        startListeningNotification(model)
        newSession.start()
    }

    override fun onStopListening(listener: Callback) {
        Log.i(TAG, "onStopListening")
        synchronized(lock) { session }?.requestStop()
    }

    override fun onCancel(listener: Callback) {
        Log.i(TAG, "onCancel")
        synchronized(lock) { session?.cancel(); session = null }
        stopListeningNotification()
    }

    /** API 33+：告知调用方本服务支持的语言（已安装=本地就绪的模型语言） */
    override fun onCheckRecognitionSupport(recognizerIntent: Intent, supportCallback: SupportCallback) {
        val prefs = Prefs(this)
        val store = ModelStore(this)
        val ready = store.readyModels()
        if (ready.isEmpty()) {
            runCatching { supportCallback.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }
            return
        }
        val installed = LinkedHashSet<String>()
        ready.forEach { m ->
            if (m.langs.contains("中")) installed.add("zh-CN")
            if (m.langs.contains("英")) installed.add("en-US")
        }
        if (installed.isEmpty()) installed.add("zh-CN")
        val support = RecognitionSupport.Builder()
            .setInstalledOnDeviceLanguages(installed.toList())
            .build()
        runCatching { supportCallback.onSupportResult(support) }
    }

    /** 单会话：同时只允许一路识别，避免抢麦 */
    override fun getMaxConcurrentSessionsCount(): Int = 1

    override fun onDestroy() {
        synchronized(lock) { session?.cancel(); session = null }
        EngineHolder.clear()
        stopListeningNotification()
        super.onDestroy()
        Log.i(TAG, "service destroyed")
    }

    // ------------------------------------------------------------------ 解析

    private fun parseParams(intent: Intent?, prefs: Prefs): RequestParams {
        val forced = prefs.language
        val asked = intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        val hint = when {
            forced == "zh" || forced == "en" -> forced
            asked.isNullOrBlank() -> "auto"
            asked.lowercase().startsWith("zh") || asked.lowercase().startsWith("cmn") -> "zh"
            asked.lowercase().startsWith("en") -> "en"
            else -> "auto"
        }
        val silenceExtra = intent?.getIntExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, -1,
        ) ?: -1
        val possiblyComplete = intent?.getIntExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, -1,
        ) ?: -1
        val silence = listOf(silenceExtra, possiblyComplete).filter { it in 200..5000 }.minOrNull()
            ?: prefs.silenceMs
        return RequestParams(
            langHint = hint,
            partialsWanted = intent?.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) ?: true,
            preferOffline = intent?.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) ?: true,
            maxResults = intent?.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1) ?: 1,
            silenceMs = silence,
            maxUtteranceMs = prefs.maxUtteranceMs,
            noSpeechTimeoutMs = prefs.noSpeechTimeoutMs,
        )
    }

    private fun langCodeFor(hint: String, model: AsrModel): String = when (model.kind) {
        ModelKind.SENSE_VOICE -> when (hint) {
            "zh" -> "zh"
            "en" -> "en"
            else -> "auto"
        }
        else -> hint
    }

    // ------------------------------------------------------------------ 通知

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_listening), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.channel_listening_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    /**
     * 识别期间显示状态通知。
     * 优先前台服务；本应用刻意不申请 FOREGROUND_SERVICE（保持只有 RECORD_AUDIO + INTERNET），
     * 在没有该权限的设备上自动退化为普通通知。
     */
    private fun startListeningNotification(model: AsrModel) {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_listening_title))
            .setContentText(getString(R.string.notif_listening_text, model.name))
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        val foregroundOk = runCatching { startForeground(NOTIF_ID, n) }.isSuccess
        if (!foregroundOk) {
            Notifications.notifyListening(this, n)
        }
    }

    private fun stopListeningNotification() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
        Notifications.cancelListening(this)
    }

    companion object {
        private const val TAG = "AsrService"
        const val CHANNEL = "dsh_asr_listening"
        const val NOTIF_ID = 1001

        /** 组件的完整名称，用于在系统设置里选择本服务 */
        fun componentName(ctx: Context): String = "${ctx.packageName}/${AsrRecognitionService::class.java.name}"
    }
}

/** 模型选择策略（v0.4.1 修正优先级，见 pick 注释） */
object ModelPicker {

    /**
     * 选模型。
     *
     * ## 修的是什么（v0.4.1）
     * 旧逻辑**只要默认模型语言匹配就直接返回**，于是"用户把离线模型设成默认" →
     * 流式偏好被完全跳过 → 识别不实时（主人反馈「感觉不够实时」）。
     * 实测日志佐证：`model=paraformer-zh-small-int8`（离线）而非流式 zipformer。
     *
     * ## 新优先级
     *  ① 默认模型**本身就是流式** → 用它（尊重用户显式选择）
     *  ② 开启了"流式优先" → 语言匹配的流式模型优先（实时性优先）
     *  ③ 默认模型（语言匹配）→ ④ 推荐 → 第一个
     */
    fun pick(prefs: Prefs, store: ModelStore, langHint: String): AsrModel? {
        val ready = store.readyModels()
        if (ready.isEmpty()) return null
        val preferred = prefs.defaultModel()?.takeIf { m -> ready.any { it.id == m.id } }

        // ① 默认模型就是流式 → 直接用
        if (preferred != null && preferred.streaming &&
            (langHint == "auto" || matches(preferred, langHint))
        ) return preferred

        // ② 开了流式优先 → 语言匹配的流式模型优先（治"不实时"）
        if (prefs.preferStreaming) {
            val pool = ready.filter { matches(it, langHint) }.ifEmpty { ready }
            pool.firstOrNull { it.streaming }?.let { return it }
        }

        // ③ 默认模型（语言匹配）
        if (preferred != null && (langHint == "auto" || matches(preferred, langHint))) return preferred
        val langPool = ready.filter { matches(it, langHint) }.ifEmpty { ready }
        if (preferred != null && langPool.any { it.id == preferred.id }) return preferred
        return langPool.firstOrNull { it.recommended } ?: langPool.first()
    }

    fun matches(model: AsrModel, langHint: String): Boolean = when (langHint) {
        "zh" -> model.langs.contains("中")
        "en" -> model.langs.contains("英")
        else -> true
    }
}

/** 通知小工具（模型缺失提醒等） */
object Notifications {

    private const val CHANNEL_MODEL = "dsh_asr_model"

    fun notifyNoModel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_MODEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MODEL, ctx.getString(R.string.channel_model), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val tap = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, ModelsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CHANNEL_MODEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(ctx.getString(R.string.notif_nomodel_title))
            .setContentText(ctx.getString(R.string.notif_nomodel_text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(1002, n) }
    }

    fun notifyListening(ctx: Context, notification: Notification) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(AsrRecognitionService.NOTIF_ID, notification) }
    }

    fun cancelListening(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(AsrRecognitionService.NOTIF_ID) }
    }
}
