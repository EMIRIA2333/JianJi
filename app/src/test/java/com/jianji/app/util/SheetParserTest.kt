package com.jianji.app.util

import com.jianji.app.core.RecordSource
import com.jianji.app.core.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetParserTest {

    @Test
    fun headerMappingWithOwnExport() {
        val rows = listOf(
            listOf("时间", "类型", "分类", "金额", "备注", "来源"),
            listOf("2025-01-01 12:00:00", "支出", "餐饮", "26.00", "老王烧烤", "微信自动")
        )
        val r = SheetParser.recordsFromRows(rows)
        assertFalse(r.badHeader)
        assertEquals(1, r.records.size)
        assertEquals(26.0, r.records[0].amount, 1e-9)
        assertEquals(RecordSource.AUTO_WECHAT, r.records[0].source)
    }

    @Test
    fun columnOrderIndependentAndAliases() {
        // 列顺序打乱 + 常见改名
        val rows = listOf(
            listOf("金额", "日期", "类别", "说明"),
            listOf("88.80", "2025/02/03", "交通", "滴滴出行")
        )
        val r = SheetParser.recordsFromRows(rows)
        assertFalse(r.badHeader)
        assertEquals(1, r.records.size)
        assertEquals(88.80, r.records[0].amount, 1e-9)
        assertEquals("交通", r.records[0].category)
        assertEquals("滴滴出行", r.records[0].note)
    }

    @Test
    fun signInfersDirectionWithoutTypeColumn() {
        val rows = listOf(
            listOf("日期", "金额", "备注"),
            listOf("2025-02-01", "-35.50", "超市"),
            listOf("2025-02-02", "5600.00", "工资")
        )
        val r = SheetParser.recordsFromRows(rows)
        assertEquals(2, r.records.size)
        assertEquals(RecordType.EXPENSE, r.records[0].type)
        assertEquals(35.50, r.records[0].amount, 1e-9)
        assertEquals(RecordType.INCOME, r.records[1].type)
        assertEquals(5600.0, r.records[1].amount, 1e-9)
    }

    @Test
    fun unknownTypeValueSkipsRow() {
        val rows = listOf(
            listOf("时间", "类型", "分类", "金额"),
            listOf("2025-01-01 00:00:00", "转账", "其他", "10.00"),
            listOf("2025-01-01 01:00:00", "支出", "其他", "12.00")
        )
        val r = SheetParser.recordsFromRows(rows)
        assertEquals(1, r.records.size)
        assertEquals(1, r.skippedLines)
        assertEquals(12.0, r.records[0].amount, 1e-9)
    }

    @Test
    fun missingRequiredColumnsRejected() {
        val r = SheetParser.recordsFromRows(listOf(listOf("姓名", "电话"), listOf("张三", "138")))
        assertTrue(r.badHeader)
        assertTrue(r.records.isEmpty())
    }

    @Test
    fun currencySymbolAndThousandsSeparator() {
        val rows = listOf(
            listOf("时间", "类型", "金额"),
            listOf("2025-01-01 10:00:00", "支出", "¥1,234.56")
        )
        val r = SheetParser.recordsFromRows(rows)
        assertEquals(1234.56, r.records[0].amount, 1e-9)
    }

    @Test
    fun timeFormats() {
        assertTrue(SheetParser.parseTime("2025-01-01 08:30:00")!! > 0)
        assertTrue(SheetParser.parseTime("2025-01-01 08:30")!! > 0)
        assertTrue(SheetParser.parseTime("2025-01-01")!! > 0)
        assertTrue(SheetParser.parseTime("2025/01/01")!! > 0)
        assertNull(SheetParser.parseTime("昨天"))
        assertNull(SheetParser.parseTime(""))
    }

    @Test
    fun zeroAndHugeAmountSkipped() {
        val rows = listOf(
            listOf("时间", "类型", "金额"),
            listOf("2025-01-01 10:00:00", "支出", "0"),
            listOf("2025-01-01 11:00:00", "支出", "99999999"),
            listOf("2025-01-01 12:00:00", "支出", "50")
        )
        val r = SheetParser.recordsFromRows(rows)
        assertEquals(1, r.records.size)
        assertEquals(2, r.skippedLines)
    }
}
