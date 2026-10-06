package app.dsh.asr

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import android.widget.Toast

/** 与 DSH Mobile 一致的暗色设计令牌 */
object Ui {

    const val BG = 0xFF101418.toInt()
    const val CARD = 0xFF171C22.toInt()
    const val STROKE = 0xFF262D35.toInt()
    const val ACCENT = 0xFF7DD3FC.toInt()
    const val TEXT = 0xFFE6EDF3.toInt()
    const val TEXT_DIM = 0xFF8A94A3.toInt()
    const val OK = 0xFF7EE787.toInt()
    const val WARN = 0xFFF2CC60.toInt()
    const val ERR = 0xFFFF7B72.toInt()

    fun dp(ctx: Context, v: Int): Int = (ctx.resources.displayMetrics.density * v).toInt()

    fun rounded(fill: Int, radiusDp: Int, strokeColor: Int? = null, ctx: Context? = null): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = (ctx?.let { dp(it, radiusDp).toFloat() } ?: radiusDp.toFloat())
        d.setColor(fill)
        if (strokeColor != null) d.setStroke(ctx?.let { dp(it, 1) } ?: 1, strokeColor)
        return d
    }

    fun TextView.asCardTitle() {
        setTextColor(ACCENT)
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun statusColor(ok: Boolean, warn: Boolean = false): Int = when {
        ok -> OK
        warn -> WARN
        else -> ERR
    }

    fun setVisible(v: View, visible: Boolean) {
        v.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
