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

    /**
     * 模型下载源（v0.5.0）。
     *
     * 主人要求「英文环境下切换到国外源下载」：国内镜像对境外用户慢甚至不可达，
     * HuggingFace 官方源则相反。默认值**按界面语言推断**（中文→镜像，其它→官方），
     * 用户可在设置页手动覆盖 —— 覆盖后以用户选择为准，不再跟随语言。
     *
     * 空字符串 = 未手动设置 → 跟随语言。
     */
    var modelSourceOverride: String
        get() = sp.getString(K_SOURCE, "") ?: ""
        set(value) = sp.edit().putString(K_SOURCE, value).apply()

    /**
     * 解析出**当前生效**的下载源。
     * @param langTag 当前界面语言标签（如 `zh-CN` / `en-US`）
     */
    fun modelSource(langTag: String?): ModelCatalog.Source {
        val override = modelSourceOverride
        return if (override.isBlank()) ModelCatalog.defaultSource(langTag)
        else ModelCatalog.sourceFrom(override)
    }

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
        private const val K_SOURCE = "model_source"
    }
}
