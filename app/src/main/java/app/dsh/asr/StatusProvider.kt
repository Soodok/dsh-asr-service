package app.dsh.asr

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Settings
import org.json.JSONObject

/**
 * 跨 App 状态查询（v0.8.0）。
 *
 * ## 为什么要它
 * DSH Mobile 的语音服务页要显示「识别服务装没装 / 模型下没下 / 是不是系统默认」。
 * 只靠 PackageManager 只能判断"装没装"，用户下完模型、设完默认它还是不知道，
 * 于是那一页永远停在「已安装」，用户没法在 dsh 里看到自己配到哪一步。
 *
 * ## 契约（稳定，调用方按这个读）
 * `content://app.dsh.asr.status/status` → 一行一列 `json`：
 * ```
 * {
 *   "installed": true,
 *   "model": "zipformer-bilingual-zh-en-int8",   // 当前选中的模型 id，未选为 null
 *   "modelName": "流式 Zipformer 中英双语",
 *   "modelReady": true,                           // 该模型文件是否已完整落盘
 *   "streaming": true,
 *   "langs": "中 · 英",
 *   "isDefault": true                             // 读系统设置失败时为 null（不知道，不是"否"）
 * }
 * ```
 * `isDefault` 用 null 表示"读不到"，绝不把读不到当成"没设"，否则调用方会误导用户重设。
 */
class StatusProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val ctx = context ?: return MatrixCursor(arrayOf(COL_JSON))
        val prefs = Prefs(ctx)
        val store = ModelStore(ctx)
        val json = JSONObject().apply {
            put("installed", true)
            val model = prefs.defaultModel()
            put("model", model?.id ?: JSONObject.NULL)
            put("modelName", model?.name ?: JSONObject.NULL)
            put("modelReady", model != null && store.state(model) == ModelState.READY)
            put("streaming", model?.streaming ?: false)
            put("langs", model?.langs ?: JSONObject.NULL)
            put("isDefault", isSystemDefault(ctx))
        }
        return MatrixCursor(arrayOf(COL_JSON)).apply { addRow(arrayOf(json.toString())) }
    }

    /**
     * 系统当前选中的识别服务是不是本 App。
     * `Settings.Secure.VOICE_RECOGNITION_SERVICE` 普通应用读不到时返回 null —— 调用方据此
     * 显示「未知」而不是「未设置」。
     */
    private fun isSystemDefault(ctx: Context): Any = runCatching {
        val v = Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")
            ?: return@runCatching JSONObject.NULL
        v.contains(ctx.packageName)
    }.getOrDefault(JSONObject.NULL)

    override fun getType(uri: Uri): String = "application/json"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val COL_JSON = "json"
    }
}
