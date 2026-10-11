package com.jianji.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置开关防误触守卫的回归测试。
 *
 * 两个必须同时满足的性质：
 * 1. **能挡住点击穿透**（界面重建时同一次点击会落到新界面的开关上）；
 * 2. **不能挡住用户连续操作** —— 曾经给毛玻璃开关用了 1.8 秒的整段抑制，
 *    结果"开关不能快速打开关闭"（第二次点击被当成穿透吞掉）。
 */
class SettingsGuardTest {

    @Test
    fun oneShotSuppressionSwallowsOnlyOneCallback() {
        SettingsGuard.suppressNextSwitchChange(60_000)
        // 第一次：视为穿透，吞掉
        assertTrue("应当吞掉第一次穿透回调", SettingsGuard.isSwitchSuppressed())
        // 之后窗口立即失效 → 用户马上再点一下照样生效
        assertFalse("一次性抑制消费后必须立刻放行（否则没法快速连续开关）", SettingsGuard.isSwitchSuppressed())
        assertFalse(SettingsGuard.isSwitchSuppressed())
    }

    @Test
    fun timeWindowSuppressionBlocksDuringRebuild() {
        SettingsGuard.suppressSwitchChanges(60_000)
        // 重建期间持续抑制（可以挡多次）
        assertTrue(SettingsGuard.isSwitchSuppressed())
        assertTrue(SettingsGuard.isSwitchSuppressed())
        // 清理，避免影响其它用例
        SettingsGuard.suppressSwitchChanges(0)
        assertFalse(SettingsGuard.isSwitchSuppressed())
    }

    @Test
    fun expiredWindowDoesNotSuppress() {
        SettingsGuard.suppressSwitchChanges(0)
        assertFalse(SettingsGuard.isSwitchSuppressed())
    }
}
