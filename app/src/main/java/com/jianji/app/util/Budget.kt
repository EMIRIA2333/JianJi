package com.jianji.app.util

/**
 * 每月预算与超支预警（纯 Kotlin，可单测）。
 */
object Budget {

    const val LEVEL_NONE = 0   // 未设置预算
    const val LEVEL_OK = 1     // 正常
    const val LEVEL_NEAR = 2   // 接近超支（默认 80%）
    const val LEVEL_OVER = 3   // 已超支

    const val DEFAULT_NEAR_RATIO = 0.8

    data class Status(
        val budget: Double,
        val spent: Double,
        val remaining: Double,
        val ratio: Double,
        val level: Int
    ) {
        val hasBudget: Boolean get() = level != LEVEL_NONE
        val isOver: Boolean get() = level == LEVEL_OVER

        /** 进度条百分比（0-100，超支封顶 100） */
        val percent: Int get() = (ratio * 100).toInt().coerceIn(0, 100)

        /** 预警文案 */
        fun message(): String = when (level) {
            LEVEL_OVER -> "本月支出已超预算 ${Money.plain(-remaining)} 元"
            LEVEL_NEAR -> "本月支出已达预算的 ${(ratio * 100).toInt()}%，注意控制"
            else -> ""
        }
    }

    fun status(spent: Double, budget: Double, nearRatio: Double = DEFAULT_NEAR_RATIO): Status {
        if (budget <= 0.0) {
            return Status(budget = 0.0, spent = spent, remaining = 0.0, ratio = 0.0, level = LEVEL_NONE)
        }
        val ratio = spent / budget
        val level = when {
            ratio > 1.0 -> LEVEL_OVER
            ratio >= nearRatio -> LEVEL_NEAR
            else -> LEVEL_OK
        }
        return Status(budget, spent, budget - spent, ratio, level)
    }
}

/**
 * 批量编辑的纯计算逻辑（可单测）。
 */
object Edits {

    /** 把时间平移到新的一天，保留原来的时刻（时:分:秒） */
    fun shiftTimeToDay(
        oldTime: Long,
        newDayStart: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): Long {
        val oldDayStart = java.time.Instant.ofEpochMilli(oldTime)
            .atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()
        val timeOfDay = oldTime - oldDayStart
        return newDayStart + timeOfDay
    }

    /** 占比百分数（整数，0-100） */
    fun percent(value: Double, total: Double): Int {
        if (total <= 0.0) return 0
        return ((value / total) * 100).toInt().coerceIn(0, 100)
    }
}
