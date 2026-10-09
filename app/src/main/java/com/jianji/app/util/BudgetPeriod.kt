package com.jianji.app.util

/**
 * 预算周期（纯 Kotlin，可单测）：
 *  - 每月：自然月（默认）
 *  - 自定义：用户指定的任意起止日期（如 10-05 ~ 11-04 的记账周期）
 */
object BudgetPeriod {

    const val TYPE_MONTH = "month"
    const val TYPE_CUSTOM = "custom"

    data class Range(val start: Long, val end: Long) {
        val valid: Boolean get() = end > start
        fun label(): String =
            TimeUtil.formatDayHeader(start) + " ~ " + TimeUtil.formatDayHeader(end - 1)
        fun key(): String = "$start-$end"
    }

    /** 解析当前生效的预算区间；自定义区间非法时回退到自然月 */
    fun resolve(
        type: String,
        customStart: Long,
        customEnd: Long,
        now: Long = System.currentTimeMillis()
    ): Range {
        if (type == TYPE_CUSTOM && customEnd > customStart) return Range(customStart, customEnd)
        return Range(TimeUtil.monthStart(now), TimeUtil.nextMonthStart(now))
    }

    /** 是否处于自定义周期 */
    fun isCustom(type: String, customStart: Long, customEnd: Long): Boolean =
        type == TYPE_CUSTOM && customEnd > customStart

    /** 周期描述：本月 / 自定义区间文案 */
    fun describe(type: String, customStart: Long, customEnd: Long, now: Long = System.currentTimeMillis()): String {
        val r = resolve(type, customStart, customEnd, now)
        return if (isCustom(type, customStart, customEnd)) r.label() else "本月 " + r.label()
    }
}
