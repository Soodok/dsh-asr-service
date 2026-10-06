package app.dsh.asr

import android.content.Context

/**
 * 界面语言工具（v0.5.1）。
 *
 * ## 为什么不直接用 Locale.getDefault()
 * Android 上 `Locale.getDefault()` 是 JVM 缓存的静态值：系统语言变更、
 * 甚至 `Resources` 配置更新后，它都可能还是旧值。实测把模拟器切到 zh-CN 后，
 * 用 `Locale.getDefault()` 仍解析出 en-US，导致下载源判定错误。
 *
 * `resources.configuration.locales` 才是**当前应用真实生效**的语言
 * （系统语言变更或 attachBaseContext 包裹后都会同步刷新），故统一从这里读。
 */
object LocaleHelper {

    /**
     * 当前生效语言的 BCP-47 标签（如 `zh-CN` / `en-US`）。
     *
     * 兼容两种读法：API 24+ 用 `configuration.locales`（有序列表，取首个），
     * 更老的回退到已废弃的 `configuration.locale`。
     */
    fun langTag(context: Context): String {
        val cfg = context.resources.configuration
        val locale = if (android.os.Build.VERSION.SDK_INT >= 24) {
            cfg.locales.takeIf { !it.isEmpty }?.get(0)
        } else {
            @Suppress("DEPRECATION")
            cfg.locale
        }
        return locale?.toLanguageTag().orEmpty()
    }
}
