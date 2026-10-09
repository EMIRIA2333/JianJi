package com.jianji.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * 近 N 日支出柱状图：圆角柱体 + 数值气泡 + 日期标签，带生长动画。
 *
 * 交互（v3.12.0 新增）：
 * - **短按**某一根柱子 → 回调 [onBarClick]（主界面据此跳到当天的数据）
 * - **长按**某一根柱子 → 回调 [onBarLongClick]（主界面据此弹出当天明细）
 * - 按下时该柱高亮，手指移动会跟随切换柱子，松手才触发，符合直觉
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Bar(val label: String, val value: Double, val key: String = "")

    /** 短按回调（返回被点的下标与数据） */
    var onBarClick: ((Int, Bar) -> Unit)? = null

    /** 长按回调 */
    var onBarLongClick: ((Int, Bar) -> Unit)? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.scaledDensity * 11f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.scaledDensity * 11f
        color = 0x80888888.toInt()
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1F888888 }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.scaledDensity * 13f
        color = 0x70888888.toInt()
    }

    private var bars: List<Bar> = emptyList()
    private var growth = 0f
    private var accent = 0xFFE4572E.toInt()
    private var bubbleBg = 0x22FFFFFF
    /** 空数据文案（支出/收入切换时由外部设置） */
    var emptyText: String = "近 7 日暂无支出"
    private var pressedIndex = -1
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        val i = pressedIndex
        if (i in bars.indices) {
            longPressFired = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onBarLongClick?.invoke(i, bars[i])
            pressedIndex = -1
            invalidate()
        }
    }

    fun setBars(list: List<Bar>, accentColor: Int, bubbleColor: Int) {
        bars = list
        accent = accentColor
        bubbleBg = bubbleColor
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                growth = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    // ---------------- 触摸交互 ----------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bars.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = indexAt(event.x)
                longPressFired = false
                if (pressedIndex >= 0) {
                    postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val i = indexAt(event.x)
                if (i != pressedIndex) {
                    pressedIndex = i
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val i = indexAt(event.x)
                val wasLong = longPressFired
                pressedIndex = -1
                invalidate()
                if (!wasLong && i >= 0 && i == indexAt(event.x)) {
                    performClick()
                    onBarClick?.invoke(i, bars[i])
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 触点落在第几根柱子上（按柱子槽位判定，含柱子两侧的空白） */
    private fun indexAt(x: Float): Int {
        if (bars.isEmpty()) return -1
        val d = resources.displayMetrics.density
        val padH = 10f * d
        val slot = (width - padH * 2) / bars.size
        if (slot <= 0f) return -1
        val i = ((x - padH) / slot).toInt()
        return if (i in bars.indices) i else -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return
        val d = resources.displayMetrics.density
        val padH = 10f * d
        val topPad = 26f * d
        val bottomPad = 20f * d
        val chartH = height - topPad - bottomPad
        if (chartH <= 0f) return

        val maxValue = bars.maxOf { it.value }
        val slot = (width - padH * 2) / bars.size
        val barW = minOf(slot * 0.42f, 16f * d)

        // 基线
        barPaint.color = 0x14888888
        barPaint.strokeWidth = 1f * d
        canvas.drawLine(padH, topPad + chartH, width - padH, topPad + chartH, barPaint)

        if (maxValue <= 0.0) {
            canvas.drawText(emptyText, width / 2f, topPad + chartH / 2f, emptyPaint)
        }

        bars.forEachIndexed { i, bar ->
            val cx = padH + slot * i + slot / 2f
            // 按下的柱子：垫一层高亮，让"点的是哪一天"一目了然
            if (i == pressedIndex) {
                canvas.drawRoundRect(
                    cx - slot / 2f + 1f, topPad - 6f * d,
                    cx + slot / 2f - 1f, topPad + chartH,
                    8f * d, 8f * d, highlightPaint
                )
            }
            val ratio = if (maxValue > 0) (bar.value / maxValue).toFloat() else 0f
            val h = chartH * ratio * growth
            if (h > 0.5f) {
                barPaint.color = accent
                barPaint.strokeWidth = barW
                canvas.drawLine(cx, topPad + chartH, cx, topPad + chartH - h, barPaint)
                // 数值气泡（仅非零柱）
                if (bar.value > 0) {
                    val text = trimNumber(bar.value)
                    val tw = valuePaint.measureText(text)
                    val by = topPad + chartH - h - 12f * d
                    valuePaint.color = accent
                    canvas.drawText(text, cx, by, valuePaint)
                    bubblePaint.color = bubbleBg
                    val bw = max(tw + 8f * d, 22f * d)
                    canvas.drawRoundRect(
                        cx - bw / 2f, by - 14f * d, cx + bw / 2f, by + 3f * d,
                        6f * d, 6f * d, bubblePaint
                    )
                }
            }
            labelPaint.color = if (i == pressedIndex) accent else 0x80888888.toInt()
            canvas.drawText(bar.label, cx, height - 4f * d, labelPaint)
        }
    }

    private fun trimNumber(v: Double): String =
        if (v >= 1000) String.format(Locale.US, "%.0f", v)
        else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    /** 供外部判断两点是否算"同一根柱子"（保留给以后扩展） */
    fun sameSlot(x1: Float, x2: Float): Boolean = abs(indexAt(x1) - indexAt(x2)) == 0
}