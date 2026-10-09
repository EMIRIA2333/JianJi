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

    /** 抑制开关回调一段时间（切换主题/重建界面时调用） */
    fun suppressSwitchChanges(ms: Long = 1500L) {
        suppressUntil = System.currentTimeMillis() + ms
        AppLog.i("设置", "已开启防误触 $ms ms（重建期间忽略开关回调）")
    }

    fun isSwitchSuppressed(): Boolean = System.currentTimeMillis() < suppressUntil
}