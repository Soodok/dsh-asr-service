package app.dsh.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 可用性自检（v0.3.0）。
 *
 * ## 为什么需要
 * 主人问「我怎么确认它能不能正常使用呢」—— 语音识别涉及**四层**，任何一层断都表现为
 * "点了没反应"，用户无从判断是哪一层的问题：
 *   ① 服务是否被系统注册/可见
 *   ② 是否被设为系统默认识别服务（国产 ROM 常把设置页藏起来，用户设不了）
 *   ③ 模型是否已下载且能真正加载（文件缺一个就加载失败）
 *   ④ 完整链路：`SpeechRecognizer` → 本服务 → 引擎 → 回调（**真跑一次**）
 *
 * 本类逐层检测并给出**明确结论 + 下一步该怎么办**，把"玄学"变成可操作项。
 *
 * ## 第 ④ 项怎么在没有麦克风时也能验
 * 用 `SpeechRecognizer` 真实绑定本服务并发起一次识别（不喂音频），
 * 只要走到 `onReadyForSpeech` 就证明**绑定 + 服务 + 引擎初始化**整条链路通了；
 * 超时/报错也能区分是哪一环（如 ERROR_CLIENT = 绑定失败、ERROR_AUDIO = 录音打不开）。
 */
object SelfCheck {

    private const val TAG = "SelfCheck"

    /** 单项结果 */
    data class Item(
        val title: String,
        val ok: Boolean,
        /** 补充说明（失败时给"该怎么办"） */
        val detail: String = "",
        /** 无法判定（如需要用户操作才能验的项） */
        val warn: Boolean = false,
    )

    /** 完整检测报告 */
    data class Report(val items: List<Item>) {
        /** 关键项（①②③）全过就算"可用"；④ 失败会单独提示 */
        val usable: Boolean get() = items.filter { !it.warn }.all { it.ok }
        val summary: String
            get() = when {
                items.all { it.ok } -> "一切正常，可以直接使用 ✓"
                items.any { !it.ok } -> "有问题，见下方逐项说明"
                else -> "基本可用，有个别提示"
            }
    }

    /**
     * 跑完整自检。**阻塞**（第 ④ 项要等识别回调），请在后台线程调用。
     * @param onStage 进度回调（主线程），让界面能实时显示"正在测哪一项"
     */
    fun run(ctx: Context, onStage: (String) -> Unit = {}): Report {
        val items = mutableListOf<Item>()

        // ① 服务是否被系统注册且可见
        onStage("检查服务注册…")
        val mine = ComponentName(ctx, AsrRecognitionService::class.java)
        val declared = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SERVICES)
        }.getOrNull()?.services?.any { it.name == AsrRecognitionService::class.java.name } == true

        val all = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.queryIntentServices(Intent(ACTION_RECOGNITION_SERVICE), 0)
        }.getOrDefault(emptyList())
        val listed = all.any { it.serviceInfo.name == mine.className }

        items += Item(
            "服务已注册", declared && listed,
            if (declared && listed) "系统枚举到 ${all.size} 个识别服务，本应用在列表中"
            else if (!declared) "清单里没有声明 RecognitionService（应用损坏，请重装）"
            else "系统未枚举到本应用（可能被 ROM 拦截，试试重启手机）",
        )

        // ② 是否被设为系统默认（这项**决定其它 App 能否直接用**）
        onStage("检查系统默认设置…")
        val current = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")
        }.getOrNull().orEmpty()
        val isDefault = current.startsWith(ctx.packageName)
        items += Item(
            "已设为系统默认识别服务", isDefault,
            if (isDefault) "其它 App 直接调用即可"
            else "当前是：${current.ifBlank { "未设置" }}。点下方「设为系统默认」一键设置；"
                + "若失败，说明系统限制较严，可在调用方 App 里手动指定本服务",
            warn = !isDefault,
        )

        // ③ 模型是否就绪且能加载
        onStage("检查模型…")
        val store = ModelStore(ctx)
        val ready = store.readyModels()
        val prefs = Prefs(ctx)
        val target = prefs.defaultModel() ?: ready.firstOrNull()
        var modelOk = false
        var modelDetail = ""
        if (target == null) {
            modelDetail = "还没有可用模型，先去「识别模型」下载一个（建议先下体积最小的）"
        } else {
            modelOk = runCatching {
                // 真正构建一次识别器 —— 文件缺一个就会在这里失败
                if (target.streaming) {
                    EngineHolder.online(ctx, target)
                } else {
                    EngineHolder.offline(ctx, target, prefs.language)
                }
                true
            }.getOrElse {
                modelDetail = "模型加载失败：${it.message}（可能下载不完整，删掉重下）"
                false
            }
            if (modelOk) modelDetail = "已加载「${target.name}」（${ModelCatalog.human(target.totalBytes)}）"
        }
        items += Item("模型可用", modelOk, modelDetail)

        // ④ 完整链路：真的用 SpeechRecognizer 调一次（能到 readyForSpeech 就算通）
        onStage("测试完整链路（绑定 → 引擎 → 回调）…")
        val chain = testChain(ctx)
        items += chain

        onStage("完成")
        return Report(items)
    }

    /**
     * 用 `SpeechRecognizer` 真实绑定本服务，发起一次识别。
     * 只要回调走到 `onReadyForSpeech`，就说明**绑定 + 服务 + 引擎初始化**全通。
     * 不给音频，几秒后主动取消 —— 目的是验证链路，不是验证识别内容。
     */
    private fun testChain(ctx: Context): Item {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            return Item("完整链路", false, "系统没有可用的识别服务（先完成上面几项）")
        }
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        var reachedReady = false
        var errCode = 0
        var errMsg = ""

        handler.post {
            runCatching {
                val r = SpeechRecognizer.createSpeechRecognizer(
                    ctx, ComponentName(ctx, AsrRecognitionService::class.java)
                )
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        reachedReady = true
                        latch.countDown()
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onResults(results: Bundle?) { latch.countDown() }
                    override fun onError(error: Int) {
                        errCode = error
                        latch.countDown()
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                r.startListening(intent)
                // 6 秒后无论如何都收工（验证链路即可，不等识别内容）
                handler.postDelayed({
                    runCatching { r.cancel() }
                    runCatching { r.destroy() }
                    latch.countDown()
                }, 6_000)
            }.onFailure {
                errMsg = it.message.orEmpty()
                latch.countDown()
            }
        }

        latch.await(9, TimeUnit.SECONDS)

        return when {
            reachedReady -> Item(
                "完整链路", true,
                "已成功绑定本服务并进入录音状态（说明服务/引擎都正常）",
            )
            errCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Item(
                "完整链路", false, "权限不足 —— 请确认已授予麦克风权限",
            )
            errCode == SpeechRecognizer.ERROR_CLIENT -> Item(
                "完整链路", false, "绑定被拒（ERROR_CLIENT）—— 可能是系统限制，试试重启手机",
            )
            errCode == SpeechRecognizer.ERROR_AUDIO -> Item(
                "完整链路", false, "录音打不开（ERROR_AUDIO）—— 检查是否有其它应用正在占用麦克风",
            )
            errCode != 0 -> Item("完整链路", false, "识别返回错误码 $errCode")
            errMsg.isNotEmpty() -> Item("完整链路", false, errMsg)
            else -> Item("完整链路", false, "等待超时，未收到任何回调（服务可能没响应）")
        }
    }

    const val ACTION_RECOGNITION_SERVICE = "android.speech.RecognitionService"

    /**
     * 尝试把本应用设为系统默认识别服务。
     *
     * 普通应用**没有** `WRITE_SECURE_SETTINGS` 权限，正常写不了这个键。
     * 三条路径，按成功率排序：
     *  ① 直接写（已 root / 有权限时可用）
     *  ② 用 root 写（`su -c settings put`）
     *  ③ 都不行 → 返回 false，由界面引导用户去系统设置（或改用调用方指定服务）
     *
     * @return 是否设置成功
     */
    fun trySetAsDefault(ctx: Context): Boolean {
        val spec = "${ctx.packageName}/${AsrRecognitionService::class.java.name}"
        // ① 直接写
        val direct = runCatching {
            Settings.Secure.putString(ctx.contentResolver, "voice_recognition_service", spec)
        }.isSuccess && isDefaultNow(ctx)
        if (direct) return true

        // ② root
        val rooted = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put secure voice_recognition_service $spec"))
            p.waitFor(4, TimeUnit.SECONDS)
            p.exitValue() == 0
        }.getOrDefault(false) && isDefaultNow(ctx)
        if (rooted) return true

        Log.w(TAG, "cannot set default service without WRITE_SECURE_SETTINGS or root")
        return false
    }

    private fun isDefaultNow(ctx: Context): Boolean = runCatching {
        Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")
            .orEmpty().startsWith(ctx.packageName)
    }.getOrDefault(false)

    /**
     * 打开系统「语音输入」设置页 —— **多路径兜底**。
     *
     * 主人反馈「有些国产系统设置入口很乱，都找不到」。这里按可靠性依次尝试：
     *  ① 标准 `ACTION_VOICE_INPUT_SETTINGS`
     *  ② 直接跳到「语言和输入法」设置
     *  ③ 直接跳到本应用的系统设置详情页（那里至少能看到权限）
     * 每步都验证"是否真的切走了"，全失败才提示手动路径。
     */
    fun openVoiceInputSettings(ctx: Context): Boolean {
        // ⚠️ 顺序很重要（v0.3.1 修正）：
        // 参考 Sayboard 的做法 —— 它用的是 **INPUT_METHOD_SETTINGS（语言和输入法）**，
        // 而不是 VOICE_INPUT_SETTINGS。原因：后者在**国产 ROM 上常被重定向到数字助理页**
        // （主人真机实测："打开系统设置打开的选项是数字助理应用"），根本设不了识别服务。
        // 而「语言和输入法」页几乎每个 ROM 都有，且里面有语音输入子项。
        val candidates = listOf(
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),   // 语言和输入法（首选）
            Intent("android.settings.INPUT_METHOD_SUBTYPE_SETTINGS"),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),    // 标准语音输入页（部分 ROM 可用）
            Intent(Settings.ACTION_SETTINGS),                // 系统设置主页兜底
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${ctx.packageName}")),
        )
        for (it in candidates) {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { ctx.startActivity(it); true }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}
