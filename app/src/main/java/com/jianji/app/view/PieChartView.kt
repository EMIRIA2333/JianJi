package com.jianji.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.atan2
import kotlin.math.min

/**
 * 环形占比图（扇形图）：按分类金额绘制扇区，支持点击选中放大，
 * 中心显示「总支出」或选中分类的金额与占比，带加载生长动画。
 */
class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Slice(val label: String, val value: Double, val color: Int)

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    private var slices: List<Slice> = emptyList()
    private var total = 0.0
    private var growth = 0f
    private var selected = -1

    var centerTitle: String = "支出"
    var centerSubtitle: String = ""
    var onSliceSelected: ((Int, Slice?) -> Unit)? = null

    /** 隐私模式：中心金额显示为 **** */
    var masked: Boolean = false

    fun setSlices(list: List<Slice>) {
        slices = list
        total = list.sumOf { it.value }
        selected = -1
        centerSubtitle = ""
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                growth = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = resources.displayMetrics.density * 12f
        val size = min(width, height).toFloat() - pad * 2
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val stroke = size * 0.20f
        val radius = size / 2f - stroke / 2f
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        if (slices.isEmpty() || total <= 0.0) {
            arcPaint.strokeWidth = stroke
            arcPaint.color = 0x33888888
            canvas.drawArc(rect, 0f, 360f, false, arcPaint)
            return
        }

        var startAngle = -90f
        val gap = if (slices.size > 1) 2.2f else 0f
        slices.forEachIndexed { i, slice ->
            val sweep = (slice.value / total * 360f * growth).toFloat()
            val isSel = i == selected
            arcPaint.color = slice.color
            arcPaint.strokeWidth = if (isSel) stroke * 1.22f else stroke
            arcPaint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(rect, startAngle + gap / 2, (sweep - gap).coerceAtLeast(0.5f), false, arcPaint)
            startAngle += sweep
        }

        // 中心文字
        val titleSize = size * 0.085f
        val valueSize = size * 0.135f
        val maskText = if (masked) "✱✱✱✱" else null
        if (selected >= 0 && selected < slices.size) {
            val s = slices[selected]
            textPaint.textSize = titleSize
            textPaint.color = 0x99FFFFFF.toInt()
            canvas.drawText(s.label, cx, cy - valueSize * 0.35f, textPaint)
            textPaint.textSize = valueSize
            textPaint.color = Color.WHITE
            textPaint.isFakeBoldText = true
            canvas.drawText(
                maskText ?: String.format(java.util.Locale.US, "%.2f", s.value),
                cx, cy + valueSize * 0.55f, textPaint
            )
            textPaint.isFakeBoldText = false
            textPaint.textSize = titleSize
            textPaint.color = 0x99FFFFFF.toInt()
            canvas.drawText(
                String.format(java.util.Locale.US, "%.1f%%", s.value / total * 100),
                cx, cy + valueSize * 1.35f, textPaint
            )
        } else {
            textPaint.textSize = titleSize
            textPaint.color = 0x99FFFFFF.toInt()
            canvas.drawText(centerTitle, cx, cy - valueSize * 0.30f, textPaint)
            textPaint.textSize = valueSize
            textPaint.color = Color.WHITE
            textPaint.isFakeBoldText = true
            canvas.drawText(
                maskText ?: String.format(java.util.Locale.US, "%.2f", total),
                cx, cy + valueSize * 0.60f, textPaint
            )
            textPaint.isFakeBoldText = false
            if (centerSubtitle.isNotEmpty()) {
                textPaint.textSize = titleSize
                textPaint.color = 0x99FFFFFF.toInt()
                canvas.drawText(maskText ?: centerSubtitle, cx, cy + valueSize * 1.35f, textPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN || slices.isEmpty() || total <= 0.0) return false
        // 不拦截父级：扇形图上的左右滑动依然可以翻页，轻点则选中扇区
        val cx = width / 2f
        val cy = height / 2f
        val dx = event.x - cx
        val dy = event.y - cy
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        val outer = min(width, height) / 2f
        if (dist > outer) return false
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < -90f) angle += 360f
        var acc = -90f
        slices.forEachIndexed { i, s ->
            val sweep = (s.value / total * 360f).toFloat()
            if (angle >= acc && angle < acc + sweep) {
                selected = if (selected == i) -1 else i
                onSliceSelected?.invoke(selected, if (selected >= 0) slices[selected] else null)
                invalidate()
                return true
            }
            acc += sweep
        }
        return true
    }
}
