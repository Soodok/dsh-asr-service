package app.dsh.asr

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.TextView
import java.io.File

class MainActivity : Activity(), DownloadBus.Listener {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvServiceState: TextView
    private lateinit var tvModelState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServiceState = findViewById(R.id.tvServiceState)
        tvModelState = findViewById(R.id.tvModelState)

        // 可用性检测（v0.3.0）：主人问「怎么确认能不能正常使用」——
        // 一键跑四层检测（服务注册 / 系统默认 / 模型加载 / 完整链路），逐项给结论
        findViewById<android.view.View>(R.id.rowRunCheck).setOnClickListener { runCheck() }
        // 「设为系统默认」（v0.4.0）：三级尝试
        //  ① Shizuku（主人要求：尝试申请 SHZ 权限）—— 有装就用它，成功率最高
        //  ② root / 直接写
        //  ③ 都不行 → 明确告知改用「调用方指定服务」
        findViewById<android.view.View>(R.id.rowSetDefault).setOnClickListener {
            setDefaultFlow()
        }
        findViewById<android.view.View>(R.id.rowOpenSettings).setOnClickListener {
            // 多路径兜底：国产 ROM 的设置入口很乱，逐条尝试直到真的跳走
            if (!SelfCheck.openVoiceInputSettings(this)) {
                Ui.toast(this, getString(R.string.check_open_fail))
            }
        }

        findViewById<android.view.View>(R.id.cardModels).setOnClickListener {
            startActivity(Intent(this, ModelsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.cardSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        probeShizuku()
        ensureMicPermission()
    }

    override fun onResume() {
        super.onResume()
        DownloadBus.register(this)
        refreshStatus()
    }

    override fun onPause() {
        DownloadBus.unregister(this)
        super.onPause()
    }

    // ------------------------------------------------------------------ 状态

    private fun refreshStatus() {
        val mine = ComponentName(this, AsrRecognitionService::class.java)

        val declared = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SERVICES)
        }.getOrNull()?.services?.any { it.name == AsrRecognitionService::class.java.name } == true

        val allServices = runCatching {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(Intent(RecognitionServiceAction), 0)
        }.getOrDefault(emptyList())
        val mineIndex = allServices.indexOfFirst { it.serviceInfo.name == mine.className }

        val current = Settings.Secure.getString(contentResolver, "voice_recognition_service").orEmpty()
        val isDefault = current.startsWith(packageName)

        tvServiceState.text = buildString {
            append(if (declared) "服务注册：已声明 RecognitionService ✓\n" else "服务注册：未在清单中找到 ✗\n")
            append("系统可见识别服务：${allServices.size} 个")
            if (mineIndex >= 0) append("（本机在列表中 ✓）") else append("（本机未被系统枚举 ✗）")
            append("\n")
            append(
                if (isDefault) "系统默认识别服务：本应用 ✓（其它 App 直接调用即可）"
                else "系统默认识别服务：${if (current.isBlank()) "未设置" else current}"
            )
        }
        tvServiceState.setTextColor(Ui.statusColor(declared && mineIndex >= 0, warn = declared))

        val prefs = Prefs(this)
        val store = ModelStore(this)
        val ready = store.readyModels()
        val def = prefs.defaultModel()
        val used = ModelCatalog.MODELS.sumOf { store.storedBytes(it) }
        tvModelState.text = buildString {
            append("默认模型：${def?.name ?: "-"}（${def?.let { ModelCatalog.human(it.totalBytes) } ?: "-"}）\n")
            append("已就绪模型：${if (ready.isEmpty()) "无（先去模型管理下载）" else ready.joinToString("、") { it.name }}\n")
            append("已占用空间：${ModelCatalog.human(used)}\n")
            append("语言偏好：${prefs.language} · 流式优先：${if (prefs.preferStreaming) "开" else "关"}")
        }
        tvModelState.setTextColor(Ui.statusColor(ready.isNotEmpty(), warn = ready.isEmpty()))
    }

    private fun openVoiceInputSettings() {
        val ok = runCatching {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isSuccess
        if (!ok) Ui.toast(this, "系统没有「语音输入」设置页，请到 系统设置 → 语言和输入法 里找")
    }

    // ------------------------------------------------------------------ 自检：走 SpeechRecognizer 全链路


    // ------------------------------------------------------------------ 自检：WAV 解码（无麦克风也能验）


    // ------------------------------------------------------------------ 设为系统默认

    /** Shizuku 可用性探测（onCreate 时记一次，供 requestPermission 用） */
    private fun probeShizuku() {
        ShizukuHelper.markInstalled(ShizukuHelper.isInstalled(this))
    }

    /**
     * 设为系统默认识别服务：Shizuku 优先 → root/直接写 → 明确失败提示。
     */
    private fun setDefaultFlow() {
        val spec = "${packageName}/${AsrRecognitionService::class.java.name}"
        val tv = findViewById<TextView>(R.id.tvCheckResult)
        tv.visibility = android.view.View.VISIBLE
        tv.setTextColor(Ui.TEXT_DIM)

        when {
            // ① Shizuku 已授权 → 直接执行
            ShizukuHelper.isAuthorized() -> {
                tv.text = getString(R.string.set_via_shizuku)
                Thread({
                    val ok = ShizukuHelper.setDefaultViaShizuku(this, spec)
                    runOnUiThread {
                        tv.text = getString(if (ok) R.string.check_set_ok else R.string.set_shizuku_failed)
                        tv.setTextColor(if (ok) Ui.OK else Ui.ERR)
                        refreshStatus()
                    }
                }, "shizuku-set").apply { isDaemon = true; start() }
            }
            // ② 装了 Shizuku 但没授权 → 申请权限，成功后自动继续
            ShizukuHelper.isInstalled(this) -> {
                tv.text = getString(R.string.set_requesting_shizuku)
                ShizukuHelper.requestPermission { granted ->
                    runOnUiThread {
                        if (granted) {
                            setDefaultFlow()   // 授权成功 → 重走流程（这次会走 ①）
                        } else {
                            tv.text = getString(R.string.set_shizuku_denied)
                            tv.setTextColor(Ui.WARN)
                            fallbackSetDefault(spec, tv)
                        }
                    }
                }
            }
            // ③ 没装 Shizuku → root/直接写，再不行给指引
            else -> {
                tv.text = getString(R.string.set_no_shizuku)
                Thread({
                    val ok = SelfCheck.trySetAsDefault(this)
                    runOnUiThread {
                        if (ok) {
                            tv.text = getString(R.string.check_set_ok)
                            tv.setTextColor(Ui.OK)
                        } else {
                            tv.text = getString(R.string.set_all_failed)
                            tv.setTextColor(Ui.ERR)
                        }
                        refreshStatus()
                    }
                }, "set-default").apply { isDaemon = true; start() }
            }
        }
    }

    /** Shizuku 被拒后的兜底：root / 直接写 */
    private fun fallbackSetDefault(spec: String, tv: TextView) {
        Thread({
            val ok = SelfCheck.trySetAsDefault(this)
            runOnUiThread {
                if (ok) { tv.text = getString(R.string.check_set_ok); tv.setTextColor(Ui.OK) }
                refreshStatus()
            }
        }, "fallback-set").apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------------ 可用性检测

    /**
     * 一键检测：后台跑（第 ④ 项要等识别回调），结果回主线程逐项显示。
     * 每项都带「该怎么办」，把玄学问题变成可操作项。
     */
    private fun runCheck() {
        val tv = findViewById<TextView>(R.id.tvCheckResult)
        tv.visibility = android.view.View.VISIBLE
        tv.text = getString(R.string.check_running)
        tv.setTextColor(Ui.TEXT_DIM)

        Thread({
            val report = SelfCheck.run(this) { stage ->
                runOnUiThread { tv.text = stage }
            }
            runOnUiThread {
                tv.text = buildString {
                    append(report.summary).append("\n\n")
                    report.items.forEach { item ->
                        append(if (item.ok) "✓ " else if (item.warn) "! " else "✗ ")
                        append(item.title)
                        if (item.detail.isNotEmpty()) append("：").append(item.detail)
                        append("\n")
                    }
                }
                tv.setTextColor(if (report.usable) Ui.OK else Ui.ERR)
                refreshStatus()
            }
        }, "self-check").apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------------ 工具

    private fun ensureMicPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            android.widget.Toast.makeText(this, "麦克风权限被拒绝：识别服务将无法工作",
                android.widget.Toast.LENGTH_LONG).show()
        }
        refreshStatus()
    }


    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        12 -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        13 -> "ERROR_LANGUAGE_UNAVAILABLE"
        else -> "code $code"
    }


    override fun onProgress(modelId: String, done: Long, total: Long) = Unit

    override fun onFinished(modelId: String, ok: Boolean, message: String?) {
        refreshStatus()
    }

    companion object {
        private const val RecognitionServiceAction = "android.speech.RecognitionService"
    }
}
