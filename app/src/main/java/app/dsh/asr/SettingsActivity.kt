package app.dsh.asr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

/**
 * 识别设置。
 * 同时作为系统「语音输入 → 识别服务 → 设置」的入口（manifest 里 settingsActivity 指向本页）。
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var store: ModelStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = Prefs(this)
        store = ModelStore(this)

        buildModelList()
        buildLanguage()
        buildSource()
        buildStreamingAndEndpoints()
        buildServiceInfo()

        findViewById<TextView>(R.id.btnOpenModels).setOnClickListener {
            startActivity(Intent(this, ModelsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        buildModelList()
        buildServiceInfo()
    }

    // ------------------------------------------------------------------ 模型

    private fun buildModelList() {
        val group = findViewById<RadioGroup>(R.id.rgModel)
        group.removeAllViews()
        val currentId = prefs.defaultModel()?.id
        for (model in ModelCatalog.MODELS) {
            val state = store.state(model)
            val rb = RadioButton(this).apply {
                id = ViewIdBase + ModelCatalog.MODELS.indexOf(model)
                text = buildString {
                    append(model.name)
                    append(" · ")
                    append(ModelCatalog.human(model.totalBytes))
                    append(
                        when (state) {
                            ModelState.READY -> " · 已就绪"
                            ModelState.PARTIAL -> " · 未完成"
                            ModelState.MISSING -> " · 未下载"
                        },
                    )
                    if (model.recommended) append(" · 推荐")
                }
                textSize = 14f
                setTextColor(if (state == ModelState.READY) Ui.TEXT else Ui.TEXT_DIM)
                isEnabled = state == ModelState.READY
                isChecked = model.id == currentId
                setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
            }
            rb.setOnClickListener {
                prefs.defaultModelId = model.id
                buildServiceInfo()
            }
            group.addView(rb)
        }
    }

    // ------------------------------------------------------------------ 语言

    private fun buildLanguage() {
        val rg = findViewById<RadioGroup>(R.id.rgLang)
        when (prefs.language) {
            "zh" -> rg.check(R.id.rbZh)
            "en" -> rg.check(R.id.rbEn)
            else -> rg.check(R.id.rbAuto)
        }
        rg.setOnCheckedChangeListener { _, checkedId ->
            prefs.language = when (checkedId) {
                R.id.rbZh -> "zh"
                R.id.rbEn -> "en"
                else -> "auto"
            }
            buildServiceInfo()
        }
    }

    // ------------------------------------------------------------------ 下载源

    /**
     * 模型下载源（v0.5.0）。
     *
     * 默认「自动」= 跟随界面语言（中文→国内镜像，其它→HuggingFace 官方），
     * 主人要求「英文环境下切换到国外源下载」即由此实现；
     * 也可手动锁定某一源，覆盖语言推断。
     */
    private fun buildSource() {
        val rg = findViewById<RadioGroup>(R.id.rgSource)
        when (prefs.modelSourceOverride) {
            ModelCatalog.Source.MIRROR.name -> rg.check(R.id.rbSourceMirror)
            ModelCatalog.Source.OFFICIAL.name -> rg.check(R.id.rbSourceOfficial)
            else -> rg.check(R.id.rbSourceAuto)
        }
        rg.setOnCheckedChangeListener { _, checkedId ->
            prefs.modelSourceOverride = when (checkedId) {
                R.id.rbSourceMirror -> ModelCatalog.Source.MIRROR.name
                R.id.rbSourceOfficial -> ModelCatalog.Source.OFFICIAL.name
                else -> ""   // 空 = 自动（跟随语言）
            }
            buildServiceInfo()
        }
    }

    // ------------------------------------------------------------------ 流式 / 端点

    private fun buildStreamingAndEndpoints() {
        val sw = findViewById<Switch>(R.id.swStreaming)
        sw.isChecked = prefs.preferStreaming
        sw.setOnCheckedChangeListener { _, checked -> prefs.preferStreaming = checked }

        val tvSilence = findViewById<TextView>(R.id.tvSilence)
        val sbSilence = findViewById<SeekBar>(R.id.sbSilence)
        sbSilence.max = 26
        sbSilence.progress = ((prefs.silenceMs - 400) / 100).coerceIn(0, 26)
        fun showSilence() {
            tvSilence.text = getString(R.string.settings_silence, String.format(Locale.US, "%.1f", prefs.silenceMs / 1000f))
        }
        showSilence()
        sbSilence.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.silenceMs = 400 + progress * 100
                showSilence()
            }
        })

        val tvMaxLen = findViewById<TextView>(R.id.tvMaxLen)
        val sbMaxLen = findViewById<SeekBar>(R.id.sbMaxLen)
        sbMaxLen.max = 11
        sbMaxLen.progress = ((prefs.maxUtteranceMs - 10_000) / 5_000).coerceIn(0, 11)
        fun showMaxLen() {
            tvMaxLen.text = getString(R.string.settings_maxlen, (prefs.maxUtteranceMs / 1000).toString())
        }
        showMaxLen()
        sbMaxLen.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.maxUtteranceMs = 10_000 + progress * 5_000
                showMaxLen()
            }
        })

        val tvNoSpeech = findViewById<TextView>(R.id.tvNoSpeech)
        val sbNoSpeech = findViewById<SeekBar>(R.id.sbNoSpeech)
        sbNoSpeech.max = 17
        sbNoSpeech.progress = (prefs.noSpeechTimeoutMs / 1000 - 3).coerceIn(0, 17)
        fun showNoSpeech() {
            tvNoSpeech.text = getString(R.string.settings_nospeech, (prefs.noSpeechTimeoutMs / 1000).toString())
        }
        showNoSpeech()
        sbNoSpeech.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.noSpeechTimeoutMs = (3 + progress) * 1000
                showNoSpeech()
            }
        })
    }

    // ------------------------------------------------------------------ 服务信息

    private fun buildServiceInfo() {
        val ready = store.readyModels()
        val used = ModelCatalog.MODELS.sumOf { store.storedBytes(it) }
        findViewById<TextView>(R.id.tvServiceInfo).text = buildString {
            append(getString(R.string.settings_engine))
            append('\n')
            append(getString(R.string.settings_component, AsrRecognitionService.componentName(this@SettingsActivity)))
            append('\n')
            append(getString(R.string.settings_default_model_line, prefs.defaultModel()?.name ?: "-"))
            append('\n')
            append(
                getString(
                    R.string.settings_models_ready,
                    if (ready.isEmpty()) "无" else ready.joinToString("、") { it.name },
                ),
            )
            append('\n')
            append(getString(R.string.settings_models_used, ModelCatalog.human(used)))
            append('\n')
            append(
                getString(
                    R.string.settings_source_line,
                    prefs.modelSource(LocaleHelper.langTag(this@SettingsActivity)).base,
                ),
            )
        }
    }

    private open class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    companion object {
        private const val ViewIdBase = 10_000
    }
}
