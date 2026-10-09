package com.jianji.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行时门控测试。
 *
 * 用户定下的规矩，必须由测试钉死：
 * **总开关关掉 → 一切后台不跑；无障碍没开 → 不扫描；没开的功能 → 附属功能也不许开。**
 */
class RuntimePolicyTest {

    @Test
    fun masterSwitchOffDisablesEverything() {
        assertFalse(RuntimePolicy.a11yWorkEnabled(autoEnabled = false, a11yEnabled = true))
        assertFalse(RuntimePolicy.rootScanEnabled(false, rootScanOn = true, a11yEnabled = true))
        assertFalse(RuntimePolicy.guardEnabled(false, guardOn = true, hookActive = true, canRestoreA11y = true))
        assertFalse(RuntimePolicy.notifyPipeEnabled(false, hookActive = true, listenerEnabled = true, a11yEnabled = true))
        assertFalse(RuntimePolicy.smsEnabled(false, smsOn = true))
        assertFalse(RuntimePolicy.billScanEnabled(false, a11yEnabled = true, billScanOn = true))
    }

    @Test
    fun noAccessibilityMeansNoScanning() {
        // 无障碍没开 → 页面扫描 / root 轮询 / 账单页补齐 全部不跑
        assertFalse(RuntimePolicy.a11yWorkEnabled(autoEnabled = true, a11yEnabled = false))
        assertFalse(RuntimePolicy.rootScanEnabled(true, rootScanOn = true, a11yEnabled = false))
        assertFalse(RuntimePolicy.billScanEnabled(true, a11yEnabled = false, billScanOn = true))
    }

    @Test
    fun unrequestedFeaturesStayOff() {
        // 用户没开 root 轮询 → 即使无障碍开着也不跑
        assertFalse(RuntimePolicy.rootScanEnabled(true, rootScanOn = false, a11yEnabled = true))
        // 用户没开守护 → 不常驻
        assertFalse(RuntimePolicy.guardEnabled(true, guardOn = false, hookActive = true, canRestoreA11y = true))
        // 没开短信 → 不解析短信
        assertFalse(RuntimePolicy.smsEnabled(true, smsOn = false))
    }

    @Test
    fun guardOnlyRunsWhenItHasAJob() {
        // Hook 生效（需要进程收广播）→ 该跑
        assertTrue(RuntimePolicy.guardEnabled(true, guardOn = true, hookActive = true, canRestoreA11y = false))
        // 能自动恢复无障碍 → 该跑
        assertTrue(RuntimePolicy.guardEnabled(true, guardOn = true, hookActive = false, canRestoreA11y = true))
        // 既没有 Hook 也不能恢复无障碍 → 没有它服务的事，不常驻
        assertFalse(RuntimePolicy.guardEnabled(true, guardOn = true, hookActive = false, canRestoreA11y = false))
    }

    @Test
    fun enabledFeaturesRun() {
        assertTrue(RuntimePolicy.a11yWorkEnabled(true, true))
        assertTrue(RuntimePolicy.rootScanEnabled(true, true, true))
        assertTrue(RuntimePolicy.smsEnabled(true, true))
        assertTrue(RuntimePolicy.billScanEnabled(true, true, true))
        // 通知管道：Hook / 通知读取 / 无障碍 任一可用即可
        assertTrue(RuntimePolicy.notifyPipeEnabled(true, hookActive = true, listenerEnabled = false, a11yEnabled = false))
        assertTrue(RuntimePolicy.notifyPipeEnabled(true, hookActive = false, listenerEnabled = true, a11yEnabled = false))
    }
}
