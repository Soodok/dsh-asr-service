package app.dsh.asr

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

/** 模型管理页：列出全部模型，支持下载（断点续传+校验）、取消、删除、设为默认 */
class ModelsActivity : Activity(), DownloadBus.Listener {

    private lateinit var store: ModelStore
    private lateinit var prefs: Prefs
    private lateinit var adapter: ModelAdapter

    private val progress = HashMap<String, Pair<Long, Long>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_models)

        store = ModelStore(this)
        prefs = Prefs(this)
        adapter = ModelAdapter(
            ctx = this,
            onDownload = { model -> toggleDownload(model) },
            onDelete = { model -> confirmDelete(model) },
            onUse = { model ->
                prefs.defaultModelId = model.id
                Ui.toast(this, "默认模型：${model.name}")
                adapter.notifyDataSetChanged()
            },
        )

        findViewById<ListView>(R.id.listModels).adapter = adapter
        findViewById<TextView>(R.id.tvDir).text = getString(R.string.models_dir, store.root.absolutePath)
    }

    override fun onResume() {
        super.onResume()
        DownloadBus.register(this)
        adapter.notifyDataSetChanged()
    }

    override fun onPause() {
        DownloadBus.unregister(this)
        super.onPause()
    }

    private fun toggleDownload(model: AsrModel) {
        if (DownloadBus.isRunning(model.id)) {
            DownloadBus.cancel(model.id)
            Ui.toast(this, "正在取消…")
        } else {
            DownloadBus.start(this, model)
            Ui.toast(this, "开始下载 ${model.name}")
        }
        adapter.notifyDataSetChanged()
    }

    private fun confirmDelete(model: AsrModel) {
        AlertDialog.Builder(this)
            .setTitle("删除 ${model.name}？")
            .setMessage("将删除本地模型文件（${ModelCatalog.human(store.storedBytes(model))}）")
            .setPositiveButton("删除") { _, _ ->
                DownloadBus.cancel(model.id)
                store.delete(model)
                progress.remove(model.id)
                Ui.toast(this, getString(R.string.toast_deleted, model.name))
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onProgress(modelId: String, done: Long, total: Long) {
        progress[modelId] = done to total
        adapter.notifyDataSetChanged()
    }

    override fun onFinished(modelId: String, ok: Boolean, message: String?) {
        progress.remove(modelId)
        val model = ModelCatalog.byId(modelId)
        if (ok) {
            Ui.toast(this, getString(R.string.toast_download_done, model?.name ?: modelId))
        } else if (message != "已取消") {
            Ui.toast(this, getString(R.string.toast_download_failed, message ?: "未知错误"))
        }
        adapter.notifyDataSetChanged()
    }

    private inner class ModelAdapter(
        private val ctx: Context,
        private val onDownload: (AsrModel) -> Unit,
        private val onDelete: (AsrModel) -> Unit,
        private val onUse: (AsrModel) -> Unit,
    ) : BaseAdapter() {

        override fun getCount(): Int = ModelCatalog.MODELS.size
        override fun getItem(position: Int): Any = ModelCatalog.MODELS[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: LayoutInflater.from(ctx).inflate(R.layout.item_model, parent, false)

            val model = ModelCatalog.MODELS[position]
            val state = store.state(model)
            val running = DownloadBus.isRunning(model.id)
            val isDefault = prefs.defaultModel()?.id == model.id

            view.findViewById<TextView>(R.id.tvName).text = buildString {
                append(model.name)
                if (isDefault) append("  ·  默认")
            }
            view.findViewById<TextView>(R.id.tvKind).text =
                (if (model.streaming) getString(R.string.label_streaming) else getString(R.string.label_offline)) +
                    if (model.recommended) " · 推荐" else ""

            view.findViewById<TextView>(R.id.tvMeta).text =
                "${model.langs} · ${ModelCatalog.human(model.totalBytes)}\n${model.note}"

            val tvState = view.findViewById<TextView>(R.id.tvState)
            val bar = view.findViewById<ProgressBar>(R.id.progress)
            val p = progress[model.id]
            when {
                running && p != null -> {
                    val pct = if (p.second > 0) (p.first * 100 / p.second).toInt() else 0
                    tvState.text = String.format(
                        Locale.US, "下载中 %d%% · %s / %s",
                        pct, ModelCatalog.human(p.first), ModelCatalog.human(p.second),
                    )
                    tvState.setTextColor(Ui.ACCENT)
                    bar.visibility = View.VISIBLE
                    bar.progress = pct
                }
                running -> {
                    // 显示**实际使用的源**（v0.5.0：中文镜像 / 其它语言官方源）
                    val src = Prefs(this@ModelsActivity)
                        .modelSource(LocaleHelper.langTag(this@ModelsActivity))
                    tvState.text = "下载中…（正在连接 ${src.base.substringAfter("://")}）"
                    tvState.setTextColor(Ui.ACCENT)
                    bar.visibility = View.VISIBLE
                    bar.isIndeterminate = true
                }
                else -> {
                    tvState.text = when (state) {
                        ModelState.READY -> getString(R.string.state_ready) +
                            " · " + ModelCatalog.human(model.totalBytes) + " · 本地已校验"
                        ModelState.PARTIAL -> "未完成 · 已下载 " +
                            ModelCatalog.human(store.storedBytes(model)) + "（可继续下载）"
                        ModelState.MISSING -> getString(R.string.state_missing)
                    }
                    tvState.setTextColor(
                        when (state) {
                            ModelState.READY -> Ui.OK
                            ModelState.PARTIAL -> Ui.WARN
                            ModelState.MISSING -> Ui.TEXT_DIM
                        },
                    )
                    bar.visibility = View.GONE
                    bar.isIndeterminate = false
                }
            }

            val btnDownload = view.findViewById<TextView>(R.id.btnDownload)
            btnDownload.text = when {
                running -> getString(R.string.btn_cancel)
                state == ModelState.READY -> "重新下载"
                state == ModelState.PARTIAL -> "继续下载"
                else -> getString(R.string.btn_download)
            }
            btnDownload.setOnClickListener { onDownload(model) }

            val btnUse = view.findViewById<TextView>(R.id.btnUse)
            btnUse.alpha = if (state == ModelState.READY) 1f else 0.4f
            btnUse.setOnClickListener {
                if (state == ModelState.READY) onUse(model)
                else Ui.toast(ctx, "先下载这个模型")
            }

            val btnDelete = view.findViewById<TextView>(R.id.btnDelete)
            btnDelete.alpha = if (state == ModelState.MISSING) 0.4f else 1f
            btnDelete.setOnClickListener {
                if (state == ModelState.MISSING) Ui.toast(ctx, "本地没有这个模型")
                else onDelete(model)
            }
            return view
        }
    }
}
