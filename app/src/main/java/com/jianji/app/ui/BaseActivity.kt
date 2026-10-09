package com.jianji.app.ui

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.jianji.app.R
import com.jianji.app.util.AppLog
import com.jianji.app.util.GlassHelper
import com.jianji.app.util.Prefs

/**
 * 所有页面的基类：统一应用**系统级后台隐私保护** + **全局毛玻璃主题**。
 *
 * 说明：
 * - v1.4.1 起移除了「禁止截屏」(FLAG_SECURE) 与「应用锁」（会导致切换闪屏）；
 * - v1.9.0 起**不再提供应用内的后台隐私开关**，直接用系统级接口
 *   [setRecentsScreenshotEnabled]（Android 13+）让最近任务列表里看不到内容；
 * - v3.6.2 起「毛玻璃」改为**全局主题层**：窗口底用背景图的模糊版，
 *   卡片/面板半透明（见 [Theme.JianJi.Glass]），背景图本身保持清晰。
 */
open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 注意：setTheme 必须在 super.onCreate 之前
        val glass = Prefs.isGlass(this)
        if (glass) runCatching { setTheme(R.style.Theme_JianJi_Glass) }
        super.onCreate(savedInstanceState)
        AppLog.i(
            "界面",
            "${javaClass.simpleName} 创建：主题=${AppLog.themeLabel(Prefs.themeMode(this))} " +
                "毛玻璃=${if (glass) "开(已套玻璃主题)" else "关"} 夜间=${isNight()}"
        )
        applySystemPrivacy()
        applyGlassWindow()
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    override fun onResume() {
        super.onResume()
        applySystemPrivacy()
        applyGlassWindow()
    }

    /**
     * 毛玻璃：开启时窗口底用背景图的模糊版、卡片/面板半透明；
     * **关闭时也要走一遍**（把颜色还原成不透明的主题色）。
     *
     * 关键：`onResume` 时 ViewPager 里的 Fragment 可能**还没创建**（要等首次布局），
     * 只刷一次会漏掉那几屏（表现为"开了没效果"）→ 立即刷一次 + 布局后再刷两次。
     */
    private fun applyGlassWindow() {
        runCatching { GlassHelper.apply(this) }
        val decor = window?.decorView ?: return
        decor.post { runCatching { GlassHelper.apply(this) } }
        decor.postDelayed({ runCatching { GlassHelper.apply(this) } }, 350L)
    }

    private fun applySystemPrivacy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { setRecentsScreenshotEnabled(false) }
        }
    }
}
