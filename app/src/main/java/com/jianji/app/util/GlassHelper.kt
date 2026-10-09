package com.jianji.app.util

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.jianji.app.App
import com.jianji.app.R
import com.jianji.app.ui.BannerBackgrounds

/**
 * 全局毛玻璃（**跟随主题配色**、运行时即时生效、不重建界面）。
 *
 * 分层：
 * ```
 *   窗口背景 = [模糊底图 + 半透明页面色]   ← 状态栏 / 底部手势条也透出这个（整屏一致）
 *     └ 内容根布局 = 同一套（保证滚动区一致）
 *         └ 卡片 / 底栏 / 标题栏 = 半透明 + 细描边
 * ```
 * - 状态栏、导航栏都设成透明，并让状态栏图标颜色跟随深浅主题；
 * - 模糊底图是小尺寸（[BlurUtil.WORK_WIDTH]）+ 放大过滤，内存约 1MB，不会 OOM；
 * - 深色/纯黑主题下底图会压暗，白字仍清晰，同时磨砂层次看得出来；
 * - **背景图本身永远不模糊**。
 */
object GlassHelper {

    /** 列表条目背景（RecyclerView 复用视图，必须每次绑定都按当前模式给对） */
    fun itemBgRes(ctx: android.content.Context, selected: Boolean): Int = when {
        Prefs.isGlass(ctx) && selected -> R.drawable.card_item_glass_selected
        Prefs.isGlass(ctx) -> R.drawable.card_item_glass
        selected -> R.drawable.card_item_bg_selected
        else -> R.drawable.card_item_bg
    }

    fun apply(activity: Activity) {
        // 安全模式（上次启动崩过）：本次不做毛玻璃，先保证应用能打开
        if (runCatching { (activity.application as App).safeMode }.getOrDefault(false)) return
        val on = Prefs.isGlass(activity)
        val pageColor = color(activity, if (on) R.color.bg_glass else R.color.bg)
        val cardColor = color(activity, if (on) R.color.card_glass else R.color.card_bg)
        val strokeColor = if (on) color(activity, R.color.glass_stroke) else Color.TRANSPARENT
        val windowColor = color(activity, R.color.bg)
        val night = isNight(activity)

        val content = activity.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
        if (content == null) {
            AppLog.w("毛玻璃", "找不到 content 视图，跳过")
            return
        }

        val backdrop: Drawable? = if (on) BannerBackgrounds.glassBackdrop(activity) else null
        var windowOk = false
        runCatching {
            val window = activity.window
            if (on && backdrop != null) {
                // 窗口底也是"模糊底 + 页面色" → 状态栏、底部手势条和内容区完全一致
                window.setBackgroundDrawable(LayerDrawable(arrayOf(backdrop, ColorDrawable(pageColor))))
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
            } else {
                window.setBackgroundDrawable(ColorDrawable(windowColor))
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = color(activity, R.color.card_bg)
            }
            // 状态栏图标：浅色主题用深色图标，深色主题用浅色图标
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !night
            windowOk = true
        }.onFailure { AppLog.e("毛玻璃", it) }

        val stat = intArrayOf(0, 0, 0, 0)
        applyToTree(content, pageColor, cardColor, strokeColor, backdrop, stat, isRootChain = true)
        AppLog.i(
            "毛玻璃",
            "应用 mode=${Prefs.glassMode(activity)}(${if (on) "开" else "关"}) 夜间=$night " +
                "page=#${Integer.toHexString(pageColor)} card=#${Integer.toHexString(cardColor)} " +
                "模糊底=${if (backdrop != null) "已生成" else "无"} 窗口/状态栏/导航栏=${if (windowOk) "已设置" else "失败"} " +
                "生效: 根${stat[0]} 卡片${stat[1]} 栏${stat[2]} 底图${stat[3]}"
        )
    }

    private fun color(activity: Activity, res: Int): Int =
        runCatching { ContextCompat.getColor(activity, res) }.getOrDefault(Color.TRANSPARENT)

    private fun isNight(activity: Activity): Boolean =
        (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun applyToTree(
        view: View,
        pageColor: Int,
        cardColor: Int,
        strokeColor: Int,
        backdrop: Drawable?,
        stat: IntArray,
        isRootChain: Boolean
    ) {
        when {
            view is MaterialCardView -> {
                view.setCardBackgroundColor(cardColor)
                if (strokeColor != Color.TRANSPARENT) {
                    view.strokeWidth = (view.resources.displayMetrics.density * 1).toInt()
                    view.strokeColor = strokeColor
                }
                stat[1]++
            }
            view is BottomNavigationView || view is MaterialToolbar || view is AppBarLayout -> {
                view.setBackgroundColor(cardColor)
                stat[2]++
            }
            isRootChain -> {
                if (backdrop != null) {
                    view.background = LayerDrawable(arrayOf(backdrop, ColorDrawable(pageColor)))
                    stat[3]++
                } else {
                    view.setBackgroundColor(pageColor)
                }
                stat[0]++
            }
        }
        if (view is ViewGroup) {
            val childIsRoot = view.id == android.R.id.content
            for (i in 0 until view.childCount) {
                applyToTree(view.getChildAt(i), pageColor, cardColor, strokeColor, backdrop, stat, childIsRoot)
            }
        }
    }
}