package app.dsh.asr

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 首页「三步开始用」引导卡（v0.8.0）。
 *
 * ## 为什么要有
 * 这个 App 装完是个空壳：没有模型不能识别、没设为系统默认别的 App 用不上。
 * 但首页原来只有「状态两行 + 一堆功能行」，新用户不知道该先点哪个 —— 反馈过
 * 「下载识别应用到里面配置完，还要回来找配置页」。
 *
 * ## 它做什么
 * 把三件事按顺序排好，每件带当前是否完成 + 一个直达按钮；全部完成后整卡变成
 * 一行「已就绪」并自动隐藏（也可手动关掉，标记存 [Prefs.guideDismissed]）。
 * 这不是新页面，是一张卡 —— 用户不用离开首页就能从头配到尾。
 */
class SetupGuideCard(private val act: Activity) {

    private val dp get() = act.resources.displayMetrics.density

    private fun px(v: Int) = (v * dp).toInt()

    /** 构建卡片；返回 null 表示不需要展示（已就绪 / 已被关掉）。 */
    fun build(
        modelReady: Boolean,
        isDefault: Boolean?,
        onDownload: () -> Unit,
        onSetDefault: () -> Unit,
        onCheck: () -> Unit,
        onDismiss: () -> Unit,
    ): View? {
        val prefs = Prefs(act)
        if (prefs.guideDismissed) return null
        val allDone = modelReady && isDefault == true
        if (allDone) return null

        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(14), px(16), px(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(16).toFloat()
                setColor(0xFF1B2330.toInt())
                setStroke(px(1).coerceAtLeast(1), 0xFF3B82F6.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = px(16) }
        }

        // 标题行：标题 + 「不再显示」
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(act).apply {
            text = act.getString(R.string.guide_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF7DD3FC.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(act).apply {
            text = act.getString(R.string.guide_dismiss)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF6B7684.toInt())
            setPadding(px(8), px(2), px(2), px(2))
            isClickable = true
            setOnClickListener { onDismiss() }
        })
        card.addView(head, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        card.addView(step(
            index = 1,
            titleRes = R.string.guide_step1_title,
            bodyRes = R.string.guide_step1_body,
            done = modelReady,
            actionRes = if (modelReady) R.string.guide_done else R.string.guide_step1_action,
            onAction = onDownload,
        ))
        card.addView(step(
            index = 2,
            titleRes = R.string.guide_step2_title,
            bodyRes = R.string.guide_step2_body,
            done = isDefault == true,
            actionRes = if (isDefault == true) R.string.guide_done else R.string.guide_step2_action,
            onAction = onSetDefault,
            unknown = isDefault == null,
        ))
        card.addView(step(
            index = 3,
            titleRes = R.string.guide_step3_title,
            bodyRes = R.string.guide_step3_body,
            done = false,
            actionRes = R.string.guide_step3_action,
            onAction = onCheck,
        ))
        return card
    }

    private fun step(
        index: Int,
        titleRes: Int,
        bodyRes: Int,
        done: Boolean,
        actionRes: Int,
        onAction: () -> Unit,
        unknown: Boolean = false,
    ): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(10), 0, 0)
        }
        val titleRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(act).apply {
            text = if (done) "✓  " else "%d.  ".format(index)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(
                when {
                    done -> 0xFF22A06B.toInt()
                    unknown -> 0xFFB45309.toInt()
                    else -> 0xFFF2F5F9.toInt()
                }
            )
        })
        titleRow.addView(TextView(act).apply {
            text = act.getString(titleRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (done) 0xFF9CA3AF.toInt() else 0xFFF2F5F9.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        titleRow.addView(TextView(act).apply {
            text = act.getString(actionRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF7DD3FC.toInt())
            setPadding(px(10), px(2), px(2), px(2))
            isClickable = true
            setOnClickListener { onAction() }
        })
        row.addView(titleRow)
        row.addView(TextView(act).apply {
            text = act.getString(bodyRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(0xFF6B7684.toInt())
            setPadding(px(18), px(2), 0, 0)
        })
        return row
    }
}
