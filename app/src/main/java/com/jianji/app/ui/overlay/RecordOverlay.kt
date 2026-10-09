package com.jianji.app.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jianji.app.R
import com.jianji.app.core.RecordType
import com.jianji.app.util.Money
import com.jianji.app.util.PendingRecord
import com.jianji.app.util.TimeUtil
import kotlin.math.abs
import kotlin.math.min

/**
 * 悬浮记账卡片：识别到支付时直接浮在支付页面上，可**当场改分类**并「保存 / 修改 / 忽略」。
 *
 * 使用无障碍服务的 `TYPE_ACCESSIBILITY_OVERLAY` 窗口类型，
 * **无需申请「显示在其他应用上层」权限**，也不需要常驻前台服务；
 * 卡片支持拖动、10 秒无操作自动收起（收起后仍可在通知栏确认）。
 */
class RecordOverlay(private val service: AccessibilityService) {

    interface Listener {
        fun onSave(category: String, payMethod: String)
        fun onEdit(category: String)
        fun onIgnore()
        fun onTimeout()
    }

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var listener: Listener? = null
    private var pending: PendingRecord? = null
    private var chips: MutableList<Pair<String, TextView>> = mutableListOf()
    private var chosenCategory: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val timeoutTask = Runnable { onTimeoutInternal() }

    fun isShowing(): Boolean = root != null

    /**
     * 显示悬浮卡片。
     * @return true = 已成功显示；false = 加窗失败（调用方**必须**退化为通知，避免丢账）
     */
    fun show(p: PendingRecord, suggestions: List<String>, listener: Listener): Boolean {
        dismiss()
        this.pending = p
        this.listener = listener
        this.chosenCategory = p.category

        val ctx = service
        val density = service.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun sp(v: Float) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, v, service.resources.displayMetrics
        )

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(ContextCompat.getColor(ctx, R.color.card_bg))
                setStroke(dp(1), ContextCompat.getColor(ctx, R.color.divider))
            }
        }

        // ---- 第一行：标题 + 支付方式 ----
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(ctx).apply {
            textSize = 15f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val payTag = TextView(ctx).apply {
            textSize = 11f
            setTextColor(ContextCompat.getColor(ctx, R.color.primary))
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(ContextCompat.getColor(ctx, R.color.summary_bg))
            }
            visibility = if (p.payMethod.isBlank()) View.GONE else View.VISIBLE
            text = p.payMethod
        }
        titleRow.addView(payTag)

        val close = TextView(ctx).apply {
            text = "✕"
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(dp(10), dp(2), dp(4), dp(2))
            setOnClickListener { listener.onIgnore(); dismiss() }
        }
        titleRow.addView(close)
        card.addView(titleRow)

        // ---- 第二行：分类 · 商户 · 时间 ----
        val subtitle = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(2))
        }
        card.addView(subtitle)

        // ---- 第三行：分类快捷切换 ----
        val chipScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(2))
        }
        chipScroll.addView(chipRow)
        card.addView(chipScroll)

        fun updateTitle() {
            title.text = if (Money.isMasked(ctx)) {
                if (p.similar) "疑似重复账单" else if (p.type == RecordType.INCOME) "识别到一笔收入" else "识别到一笔支出"
            } else {
                val amountText = Money.signedYuan(ctx, p.type, p.amount)
                when {
                    p.similar -> "疑似重复 $amountText"
                    p.type == RecordType.INCOME -> "识别到收入 $amountText"
                    else -> "识别到支出 ${amountText.trimStart('-')}"
                }
            }
            titleRow.contentDescription = Money.signedYuan(ctx, p.type, p.amount)
        }

        fun updateSubtitle() {
            val parts = mutableListOf(chosenCategory.ifBlank { "未分类" })
            if (p.merchant.isNotBlank()) parts.add(p.merchant)
            parts.add(TimeUtil.formatList(p.time))
            if (p.similar) parts.add("近期已有一笔相同金额")
            subtitle.text = parts.joinToString(" · ")
        }

        fun renderChips() {
            chipRow.removeAllViews()
            chips.clear()
            suggestions.forEach { name ->
                val chip = TextView(ctx).apply {
                    text = name
                    textSize = 12f
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener {
                        chosenCategory = name
                        renderChips()
                        updateSubtitle()
                        resetTimeout()
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(6)
                chipRow.addView(chip, lp)
                chips.add(name to chip)
            }
            chips.forEach { (name, chip) ->
                val selected = name == chosenCategory
                chip.background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    if (selected) {
                        setColor(ContextCompat.getColor(ctx, R.color.primary))
                        setStroke(dp(1), ContextCompat.getColor(ctx, R.color.primary))
                    } else {
                        setColor(ContextCompat.getColor(ctx, R.color.card_bg_soft))
                        setStroke(dp(1), ContextCompat.getColor(ctx, R.color.divider))
                    }
                }
                chip.setTextColor(
                    ContextCompat.getColor(ctx, if (selected) R.color.on_primary else R.color.text_primary)
                )
            }
        }

        renderChips()
        updateTitle()
        updateSubtitle()

        // ---- 第四行：操作按钮 ----
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        fun actionButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
            TextView(ctx).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    if (primary) setColor(ContextCompat.getColor(ctx, R.color.primary))
                    else {
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(1), ContextCompat.getColor(ctx, R.color.divider))
                    }
                }
                setTextColor(
                    ContextCompat.getColor(ctx, if (primary) R.color.on_primary else R.color.text_primary)
                )
                setOnClickListener { onClick() }
            }

        val ignoreBtn = actionButton("忽略", false) { listener.onIgnore(); dismiss() }
        val editBtn = actionButton("修改", false) { listener.onEdit(chosenCategory); dismiss() }
        val saveBtn = actionButton(if (p.similar) "继续记录" else "保存", true) {
            listener.onSave(chosenCategory, p.payMethod)
            dismiss()
        }
        listOf(ignoreBtn, editBtn, saveBtn).forEach { btn ->
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(8)
            actionRow.addView(btn, lp)
        }
        card.addView(actionRow)

        // 点标题栏可拖动卡片
        attachDrag(titleRow, card)

        // ---- 添加到窗口 ----
        val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        val screenWidth = service.resources.displayMetrics.widthPixels
        val width = min(screenWidth - dp(24), dp(440))

        // 优先用**无障碍悬浮窗**（不需要任何权限，最稳）；失败时若用户授予了
        // 「显示在其他应用上层」再尝试应用悬浮窗。
        // 注意：addView 失败必须返回 false，让调用方退化为通知 —— 绝不能静默丢账。
        val accessibilityLp = layoutParams(
            width, dp(90), screenWidth, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        )
        if (tryAdd(wm, card, accessibilityLp)) return true

        if (android.provider.Settings.canDrawOverlays(service)) {
            val appLp = layoutParams(
                width, dp(90), screenWidth, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            )
            if (tryAdd(wm, card, appLp)) return true
        }
        return false
    }

    private fun layoutParams(
        width: Int,
        y: Int,
        screenWidth: Int,
        type: Int
    ): WindowManager.LayoutParams = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        android.graphics.PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = (screenWidth - width) / 2
        this.y = y
    }

    /** @return 是否成功加到窗口 */
    private fun tryAdd(wm: WindowManager, card: LinearLayout, lp: WindowManager.LayoutParams): Boolean =
        runCatching {
            wm.addView(card, lp)
            root = card
            params = lp
            resetTimeout()
            true
        }.getOrDefault(false).also { ok ->
            if (!ok) {
                root = null
                params = null
            }
        }

    private fun attachDrag(handle: View, card: View) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - touchX).toInt()
                    lp.y = (startY + (event.rawY - touchY).toInt()).coerceAtLeast(0)
                    runCatching {
                        (service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager)
                            .updateViewLayout(card, lp)
                    }
                    resetTimeout()
                    true
                }
                else -> false
            }
        }
    }

    private fun resetTimeout() {
        handler.removeCallbacks(timeoutTask)
        handler.postDelayed(timeoutTask, TIMEOUT_MS)
    }

    private fun onTimeoutInternal() {
        val l = listener
        val p = pending
        dismiss()
        if (l != null && p != null) l.onTimeout()
    }

    fun dismiss() {
        handler.removeCallbacks(timeoutTask)
        val card = root ?: return
        runCatching {
            (service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager).removeView(card)
        }
        root = null
        params = null
        chips.clear()
        listener = null
        pending = null
    }

    /** 当前选中的分类（供调用方读取） */
    fun currentCategory(): String = chosenCategory

    companion object {
        private const val TIMEOUT_MS = 10_000L
    }
}
