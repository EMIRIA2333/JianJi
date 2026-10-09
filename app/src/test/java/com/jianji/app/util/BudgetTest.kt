package com.jianji.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class BudgetTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun noBudget() {
        val s = Budget.status(spent = 120.0, budget = 0.0)
        assertEquals(Budget.LEVEL_NONE, s.level)
        assertFalse(s.hasBudget)
    }

    @Test
    fun normal() {
        val s = Budget.status(spent = 200.0, budget = 1000.0)
        assertEquals(Budget.LEVEL_OK, s.level)
        assertEquals(800.0, s.remaining, 1e-9)
        assertEquals(20, s.percent)
        assertFalse(s.isOver)
    }

    @Test
    fun nearBudget() {
        val s = Budget.status(spent = 850.0, budget = 1000.0)
        assertEquals(Budget.LEVEL_NEAR, s.level)
        assertEquals(150.0, s.remaining, 1e-9)
        assertTrue(s.message().contains("85%"))
    }

    @Test
    fun overBudget() {
        val s = Budget.status(spent = 1200.0, budget = 1000.0)
        assertEquals(Budget.LEVEL_OVER, s.level)
        assertTrue(s.isOver)
        assertEquals(-200.0, s.remaining, 1e-9)
        assertEquals(100, s.percent) // 进度条封顶
        assertTrue(s.message().contains("超预算"))
        assertTrue(s.message().contains("200.00"))
    }

    @Test
    fun exactBudgetIsNearNotOver() {
        val s = Budget.status(spent = 1000.0, budget = 1000.0)
        assertEquals(Budget.LEVEL_NEAR, s.level)
        assertEquals(0.0, s.remaining, 1e-9)
    }

    @Test
    fun customNearRatio() {
        val s = Budget.status(spent = 600.0, budget = 1000.0, nearRatio = 0.5)
        assertEquals(Budget.LEVEL_NEAR, s.level)
    }

    // ---------- 批量编辑的时间平移 ----------

    @Test
    fun shiftTimeKeepsClock() {
        val old = java.time.LocalDateTime.of(2026, 1, 5, 14, 37, 22)
            .atZone(zone).toInstant().toEpochMilli()
        val newDay = java.time.LocalDate.of(2026, 3, 9).atStartOfDay(zone).toInstant().toEpochMilli()
        val moved = Edits.shiftTimeToDay(old, newDay, zone)
        val movedDt = java.time.Instant.ofEpochMilli(moved).atZone(zone).toLocalDateTime()
        assertEquals(2026, movedDt.year)
        assertEquals(3, movedDt.monthValue)
        assertEquals(9, movedDt.dayOfMonth)
        assertEquals(14, movedDt.hour)
        assertEquals(37, movedDt.minute)
        assertEquals(22, movedDt.second)
    }

    @Test
    fun percentCalculation() {
        assertEquals(53, Edits.percent(27.40, 51.30))
        assertEquals(0, Edits.percent(10.0, 0.0))
        assertEquals(100, Edits.percent(10.0, 10.0))
        assertEquals(0, Edits.percent(0.0, 10.0))
    }
}
