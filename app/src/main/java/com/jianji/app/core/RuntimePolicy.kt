package com.jianji.app.core

/**
 * 运行时门控（纯逻辑，可单测）——**所有后台工作的唯一开关依据**。
 *
 * 用户定下的规矩，代码里必须严格照做：
 *
 * 1. **「自动记账」总开关关掉 → 一切后台工作都不跑**（不扫描、不轮询、不常驻）；
 * 2. **无障碍没开 → 取消扫描**（页面扫描、通知事件扫描都不做）；
 * 3. **用户没开的功能，其附属功能也不许开**：
 *    - 没开「自动读取通知栏(root)」→ 绝不跑 `dumpsys` 轮询；
 *    - 没开「保持后台常驻」→ 绝不启动守护服务；
 *    - 连守护服务本身也只在"确实有它服务的事"时才运行
 *      （Hook 生效需要进程收广播，或已授权能自动恢复无障碍）。
 *
 * 这样任何后台开销都能对应到用户明确打开的一项功能，不会再出现
 * "某个开关没开、后台却一直在跑"的耗电。
 */
object RuntimePolicy {

    /** 无障碍相关工作（页面扫描、a11y 通知事件） */
    fun a11yWorkEnabled(autoEnabled: Boolean, a11yEnabled: Boolean): Boolean =
        autoEnabled && a11yEnabled

    /**
     * root 轮询通知栏。
     * 按用户要求：**无障碍没开就不扫描**；并且必须用户显式开启该功能。
     */
    fun rootScanEnabled(
        autoEnabled: Boolean,
        rootScanOn: Boolean,
        a11yEnabled: Boolean
    ): Boolean = autoEnabled && rootScanOn && a11yEnabled

    /**
     * Hook 直读守护（前台服务，让进程常驻）。
     * 只有"确实需要它"时才跑：Hook 生效（要收广播）或能自动恢复无障碍。
     */
    fun guardEnabled(
        autoEnabled: Boolean,
        guardOn: Boolean,
        hookActive: Boolean,
        canRestoreA11y: Boolean
    ): Boolean = autoEnabled && guardOn && (hookActive || canRestoreA11y)

    /** 通知类记账管道是否需要工作（四条通路任一可用） */
    fun notifyPipeEnabled(
        autoEnabled: Boolean,
        hookActive: Boolean,
        listenerEnabled: Boolean,
        a11yEnabled: Boolean
    ): Boolean = autoEnabled && (hookActive || listenerEnabled || a11yEnabled)

    /** 短信记账 */
    fun smsEnabled(autoEnabled: Boolean, smsOn: Boolean): Boolean = autoEnabled && smsOn

    /** 账单列表页补齐（依赖无障碍） */
    fun billScanEnabled(autoEnabled: Boolean, a11yEnabled: Boolean, billScanOn: Boolean): Boolean =
        autoEnabled && a11yEnabled && billScanOn
}
