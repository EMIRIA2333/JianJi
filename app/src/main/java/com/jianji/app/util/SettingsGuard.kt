package com.jianji.app.util

/**
 * 设置项防误触守卫。
 *
 * 起因（由导出的运行日志定位）：切换主题会重建 Activity，
 * 而**打开主题对话框的那一次点击会穿透到重建后的新界面**，
 * 恰好落在「毛玻璃」开关那一行 → 行点击 = 开关被 toggle → 设置被改成关闭。
 * 日志证据：`界面: MainActivity 创建…毛玻璃=开` 之后 80ms 出现 `毛玻璃: 开关切换 → 关`。
 *
 * 两道防线：
 * 1. 切换主题时**延后**再重建（让触摸事件先结束）；
 * 2. 重建前后的一小段时间里，**忽略开关的回调**（不会把设置改坏）。
 */
object SettingsGuard {

    @Volatile private var suppressUntil = 0L
    @Volatile private var oneShot = false

    /** 抑制开关回调一段时间（切换主题/重建界面时调用） */
    fun suppressSwitchChanges(ms: Long = 1500L) {
        suppressUntil = System.currentTimeMillis() + ms
        oneShot = false
        AppLog.i("设置", "已开启防误触 $ms ms（重建期间忽略开关回调）")
    }

    /**
     * **只吞掉下一个开关回调**（点击穿透最多就一次）。
     *
     * 与 [suppressSwitchChanges] 的区别：这个是**一次性**的 ——
     * 命中一次后窗口立即失效，所以用户在窗口内再点一下照样生效。
     * 起因：给毛玻璃开关用了 1.8 秒的"整段时间抑制"，结果**没法快速连续开关**
     * （第二次点击被当成穿透吞掉了）。
     */
    fun suppressNextSwitchChange(ms: Long = 600L) {
        suppressUntil = System.currentTimeMillis() + ms
        oneShot = true
    }

    fun isSwitchSuppressed(): Boolean {
        val now = System.currentTimeMillis()
        if (now >= suppressUntil) return false
        if (oneShot) {
            // 消费掉：只放过这一次穿透，后续点击照常生效
            suppressUntil = 0L
            oneShot = false
        }
        return true
    }
}