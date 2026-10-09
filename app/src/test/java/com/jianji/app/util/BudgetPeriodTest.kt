package com.jianji.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 预算周期测试：每月 / 自定义时间段 / 非法输入回退 */
class BudgetPeriodTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(y: Int, m: Int, d: Int, h: Int = 0): Long =
        LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private val now = at(2026, 10, 15, 12)

    @Test
    fun monthTypeUsesNaturalMonth() {
        val r = BudgetPeriod.resolve(BudgetPeriod.TYPE_MONTH, 0L, 0L, now)
        assertEquals(TimeUtil.monthStart(now), r.start)
        assertEquals(TimeUtil.nextMonthStart(now), r.end)
        assertTrue(r.valid)
        assertFalse(BudgetPeriod.isCustom(BudgetPeriod.TYPE_MONTH, 0L, 0L))
    }

    @Test
    fun customRangeIsUsedAsIs() {
        val start = at(2026, 10, 5)
        val end = at(2026, 11, 5)
        val r = BudgetPeriod.resolve(BudgetPeriod.TYPE_CUSTOM, start, end, now)
        assertEquals(start, r.start)
        assertEquals(end, r.end)
        assertTrue(BudgetPeriod.isCustom(BudgetPeriod.TYPE_CUSTOM, start, end))
        // 自定义周期可以跨月
        assertNotEquals(TimeUtil.monthStart(now), r.start)
    }

    @Test
    fun invalidCustomFallsBackToMonth() {
        // 结束早于开始
        val r1 = BudgetPeriod.resolve(BudgetPeriod.TYPE_CUSTOM, at(2026, 10, 20), at(2026, 10, 10), now)
        assertEquals(TimeUtil.monthStart(now), r1.start)
        // 未设置（0）
        val r2 = BudgetPeriod.resolve(BudgetPeriod.TYPE_CUSTOM, 0L, 0L, now)
        assertEquals(TimeUtil.monthStart(now), r2.start)
        assertFalse(BudgetPeriod.isCustom(BudgetPeriod.TYPE_CUSTOM, 0L, 0L))
    }

    @Test
    fun rangeKeyIsStableAndPeriodSpecific() {
        val start = at(2026, 10, 5)
        val end = at(2026, 11, 5)
        val a = BudgetPeriod.Range(start, end)
        val b = BudgetPeriod.Range(start, end)
        assertEquals(a.key(), b.key())
        // 不同周期 -> 不同 key（用于「每个周期各提醒一次」）
        val next = BudgetPeriod.Range(at(2026, 11, 5), at(2026, 12, 5))
        assertNotEquals(a.key(), next.key())
    }

    @Test
    fun labels() {
        val start = at(2026, 10, 5)
        val end = at(2026, 11, 5)
        val custom = BudgetPeriod.describe(BudgetPeriod.TYPE_CUSTOM, start, end, now)
        assertTrue(custom.contains("~"))
        val month = BudgetPeriod.describe(BudgetPeriod.TYPE_MONTH, 0L, 0L, now)
        assertTrue(month.startsWith("本月"))
        // 结束日期显示为"含当日"（end 是次日 0 点）
        val range = BudgetPeriod.Range(start, end)
        val endDate = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(range.end - 1), zone)
        assertEquals(LocalDate.of(2026, 11, 4), endDate)
    }
}
