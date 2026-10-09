package com.jianji.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.jianji.app.App
import com.jianji.app.R
import com.jianji.app.core.RuntimePolicy
import com.jianji.app.databinding.ActivityMainBinding
import com.jianji.app.util.AppLog
import com.jianji.app.util.BackgroundWork
import com.jianji.app.util.Notifier
import com.jianji.app.util.Prefs
import com.jianji.app.util.RootManager
import com.jianji.app.util.ServiceHealth
import kotlin.math.abs

/**
 * 主界面：ViewPager2 + 底部导航，支持左右滑动切换「明细 / 统计 / 我的」三个页面。
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Notifier.ensureChannel(this)
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 3
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> RecordsFragment()
                1 -> StatsFragment()
                else -> ProfileFragment()
            }
        }
        binding.viewPager.offscreenPageLimit = 2
        binding.viewPager.setPageTransformer(SmoothPageTransformer())

        binding.bottomNav.setOnItemSelectedListener { item ->
            val index = when (item.itemId) {
                R.id.nav_stats -> 1
                R.id.nav_profile -> 2
                else -> 0
            }
            binding.viewPager.setCurrentItem(index, true)
            true
        }
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.bottomNav.selectedItemId = when (position) {
                    1 -> R.id.nav_stats
                    2 -> R.id.nav_profile
                    else -> R.id.nav_records
                }
                // 记住当前页：切换主题会重建界面，恢复后应回到同一页
                Prefs.setLastTab(this@MainActivity, position)
                // 「回到最上方」只在明细页出现
                if (position != 0) binding.fabTop.visibility = View.GONE
            }
        })

        // 「回到最上方」：与「记一笔」左右对称（同一父容器 + 镜像边距）
        binding.fabTop.setOnClickListener {
            runCatching {
                supportFragmentManager.fragments
                    .filterIsInstance<RecordsFragment>()
                    .firstOrNull()
                    ?.scrollToTop()
            }.onFailure { AppLog.e("界面", it) }
            binding.fabTop.visibility = View.GONE
        }
        // 恢复上次所在页（重建 / 重新打开都一致，不会"跳回明细页"）
        val savedTab = Prefs.lastTab(this).coerceIn(0, 2)
        if (savedTab != 0) {
            binding.viewPager.setCurrentItem(savedTab, false)
            binding.bottomNav.selectedItemId = when (savedTab) {
                1 -> R.id.nav_stats
                2 -> R.id.nav_profile
                else -> R.id.nav_records
            }
        }

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, AddRecordActivity::class.java))
        }
    }

    /** 供明细页的「统计」图标调用 */
    fun goToStats() {
        binding.viewPager.setCurrentItem(1, true)
    }

    override fun onResume() {
        super.onResume()
        // 主界面已经起来了：标记本次启动正常（下次启动就不会进安全模式）
        runCatching { AppLog.markRunOk(this) }
        runCatching {
            // 先尝试自动恢复（ADB / root 已授权时），再判断是否需要提醒用户
            ServiceHealth.ensureAccessibilityAliveThrottled(this)
            ServiceHealth.warnIfDisabled(this)
            // 后台服务启停完全按门控：没开的功能不会跑
            BackgroundWork.sync(this)
            // root 轮询仅在「总开关 + 该功能 + 无障碍」都满足时才手动补扫一次
            val a11yOn = ServiceHealth.isAccessibilityEnabled(this)
            val allowRootScan = RuntimePolicy.rootScanEnabled(
                autoEnabled = Prefs.isAutoEnabled(this),
                rootScanOn = Prefs.isRootAutoScan(this),
                a11yEnabled = a11yOn
            )
            if (allowRootScan && RootManager.rootedCached() == true) {
                val app = application as App
                app.post { runCatching { RootManager.scanNotifications(this) } }
            }
        }.onFailure { AppLog.e("界面", it) }
    }


    /**
     * 首次启动的免责声明（开源版合规要求，只弹一次）。
     * 不同意就退出，避免用户在不知情的情况下使用 Hook / root 功能。
     */
    private fun showDisclaimerIfNeeded() {
        if (Prefs.isDisclaimerAccepted(this)) return
        runCatching {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("使用前请阅读")
                .setMessage(
                    "简记是个人学习作品，仅供本机自用。\n\n" +
                        "· 全部数据只存在本机：应用没有联网权限，不会上传任何信息；\n" +
                        "· 通过系统公开接口（通知 / 无障碍 / 通知监听 / dumpsys）读取信息，" +
                        "不含任何第三方应用代码，也不修改第三方应用；\n" +
                        "· Hook 与 root 功能可能与部分应用的用户协议冲突，请自行评估风险；\n" +
                        "· 不提供绕过支付 / 破解 / 伪造数据的能力；\n" +
                        "· 软件按原样提供，记账结果请自行核对，账本数据请定期导出备份。\n\n" +
                        "继续使用即表示你已知悉以上内容。"
                )
                .setCancelable(false)
                .setPositiveButton("我已知悉") { _, _ -> Prefs.setDisclaimerAccepted(this, true) }
                .setNegativeButton("退出") { _, _ -> finish() }
                .show()
        }.onFailure { AppLog.e("界面", it) }
    }
    /** 明细页滚动到一定位置后显示「回到最上方」（与「记一笔」对称的那个按钮） */
    fun setTopFabVisible(visible: Boolean) {
        if (binding.viewPager.currentItem != 0) return
        binding.fabTop.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** 供明细页的「设置」图标调用（设置项已合并到「我的」页） */
    fun goToProfile() {
        binding.viewPager.setCurrentItem(2, true)
    }

    /** 多选模式下隐藏悬浮按钮，避免遮挡批量操作栏 */
    fun setFabVisible(visible: Boolean) {
        binding.fabAdd.visibility = if (visible) View.VISIBLE else View.GONE
    }
}

/**
 * 页面切换动效：轻微视差 + 缩放 + 透明度渐变，滑动更顺滑。
 * 注意：相邻页必须向**外侧**位移并淡出，否则会漏进当前页形成重影。
 */
class SmoothPageTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        val absPos = abs(position)
        // 完全离开视口的页面不可见，避免相邻页面"压"在当前页上
        page.alpha = if (absPos >= 0.98f) 0f else 1f - absPos * 0.25f
        page.scaleY = 1f - absPos * 0.04f
        // position > 0 是右侧页：继续向右推；position < 0 是左侧页：继续向左推
        page.translationX = position * page.width * 0.16f
    }
}
