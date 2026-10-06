package app.dsh.asr

import android.content.Context

/** 应用设置（SharedPreferences） */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("dsh_asr", Context.MODE_PRIVATE)

    /** 默认模型 id（未设置时取目录里推荐的第一个） */
    var defaultModelId: String
        get() = sp.getString(K_MODEL, "") ?: ""
        set(value) = sp.edit().putString(K_MODEL, value).apply()

    /** auto / zh / en —— 识别语言偏好，auto 时按调用方 Intent 的 EXTRA_LANGUAGE 推断 */
    var language: String
        get() = sp.getString(K_LANG, "auto") ?: "auto"
        set(value) = sp.edit().putString(K_LANG, value).apply()

    /** 是否优先使用流式模型（有流式模型可用时） */
    var preferStreaming: Boolean
        get() = sp.getBoolean(K_STREAMING, true)
        set(value) = sp.edit().putBoolean(K_STREAMING, value).apply()

    /** 说话结束后静音多久判定一句话结束 */
    var silenceMs: Int
        get() = sp.getInt(K_SILENCE, 1200)
        set(value) = sp.edit().putInt(K_SILENCE, value).apply()

    /** 单次会话最长时长 */
    var maxUtteranceMs: Int
        get() = sp.getInt(K_MAX_UTTER, 30_000)
        set(value) = sp.edit().putInt(K_MAX_UTTER, value).apply()

    /** 一直没人说话时的等待上限（超时返回 ERROR_SPEECH_TIMEOUT） */
    var noSpeechTimeoutMs: Int
        get() = sp.getInt(K_NO_SPEECH, 8000)
        set(value) = sp.edit().putInt(K_NO_SPEECH, value).apply()

    /** 当前默认模型（目录对象） */
    fun defaultModel(): AsrModel? =
        ModelCatalog.byId(defaultModelId) ?: ModelCatalog.MODELS.firstOrNull { it.recommended }

    companion object {
        private const val K_MODEL = "default_model"
        private const val K_LANG = "language"
        private const val K_STREAMING = "prefer_streaming"
        private const val K_SILENCE = "silence_ms"
        private const val K_MAX_UTTER = "max_utterance_ms"
        private const val K_NO_SPEECH = "no_speech_timeout_ms"
    }
}
