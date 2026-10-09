package com.jianji.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回收站策略测试：保留期与自动清理界限 */
class TrashPolicyTest {

    private val now = 1_800_000_000_000L

    @Test
    fun expiryCutoffIsThirtyDaysAgo() {
        val cutoff = TrashPolicy.expiryCutoff(now)
        assertEquals(now - 30L * 24 * 60 * 60 * 1000, cutoff)
        // 昨天删除的不会被清理
        assertTrue(now - 24L * 60 * 60 * 1000 > cutoff)
        // 40 天前删除的会被清理
        assertTrue(now - 40L * 24 * 60 * 60 * 1000 < cutoff)
    }

    @Test
    fun remainingDays() {
        assertEquals(30, TrashPolicy.remainingDays(now, now))
        assertEquals(29, TrashPolicy.remainingDays(now - 24L * 60 * 60 * 1000, now))
        assertEquals(0, TrashPolicy.remainingDays(now - 45L * 24 * 60 * 60 * 1000, now))
    }

    @Test
    fun customRetention() {
        assertEquals(7, TrashPolicy.remainingDays(now, now, retentionDays = 7))
        assertEquals(now - 7L * 24 * 60 * 60 * 1000, TrashPolicy.expiryCutoff(now, retentionDays = 7))
        // 非法值兜底为至少 1 天
        assertEquals(now - 24L * 60 * 60 * 1000, TrashPolicy.expiryCutoff(now, retentionDays = 0))
    }
}
