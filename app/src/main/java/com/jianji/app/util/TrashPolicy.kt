package com.jianji.app.util

/**
 * 回收站策略（纯 Kotlin，可单测）：删除的账单保留 N 天，超期自动彻底清理。
 */
object TrashPolicy {

    const val RETENTION_DAYS = 30

    /** 自动清理的时间界限：删除时间早于该值的记录会被彻底清除 */
    fun expiryCutoff(now: Long, retentionDays: Int = RETENTION_DAYS): Long =
        now - retentionDays.coerceAtLeast(1) * DAY_MS

    /** 剩余保留天数（用于界面提示），已超期返回 0 */
    fun remainingDays(deletedAt: Long, now: Long, retentionDays: Int = RETENTION_DAYS): Int {
        val passed = ((now - deletedAt) / DAY_MS).toInt()
        return (retentionDays - passed).coerceAtLeast(0)
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
