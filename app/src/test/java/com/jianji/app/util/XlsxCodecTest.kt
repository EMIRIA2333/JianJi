package com.jianji.app.util

import com.jianji.app.core.Record
import com.jianji.app.core.RecordSource
import com.jianji.app.core.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsxCodecTest {

    private val sample = listOf(
        Record(amount = 26.0, type = RecordType.EXPENSE, category = "餐饮", note = "老王烧烤",
            source = RecordSource.AUTO_WECHAT, time = 1735689600000L),
        Record(amount = 1234.56, type = RecordType.INCOME, category = "工资", note = "工资 & 奖金 <10月>",
            source = RecordSource.MANUAL, time = 1735776000000L)
    )

    @Test
    fun xlsxRoundTrip() {
        val bytes = XlsxCodec.buildXlsx(sample)
        assertTrue(XlsxCodec.isXlsx(bytes))

        val rows = XlsxCodec.readRows(bytes)
        assertNotNull(rows)
        assertEquals(3, rows!!.size) // 表头 + 2 行
        assertEquals("时间", rows[0][0])
        assertEquals("金额", rows[0][3])

        val result = SheetParser.recordsFromRows(rows)
        assertFalse(result.badHeader)
        assertEquals(0, result.skippedLines)
        assertEquals(2, result.records.size)

        val r0 = result.records[0]
        assertEquals(26.0, r0.amount, 1e-9)
        assertEquals(RecordType.EXPENSE, r0.type)
        assertEquals("餐饮", r0.category)
        assertEquals("老王烧烤", r0.note)
        assertEquals(RecordSource.AUTO_WECHAT, r0.source)
        assertEquals(sample[0].time, r0.time)

        val r1 = result.records[1]
        assertEquals(1234.56, r1.amount, 1e-9)
        assertEquals(RecordType.INCOME, r1.type)
        // XML 特殊字符往返
        assertEquals("工资 & 奖金 <10月>", r1.note)
    }

    @Test
    fun nonXlsxRejected() {
        assertFalse(XlsxCodec.isXlsx("时间,类型\n2025-01-01,支出".toByteArray()))
        assertNull(XlsxCodec.readRows("not a zip".toByteArray()))
    }

    @Test
    fun xmlEscaping() {
        assertEquals("a&amp;b&lt;c&gt;", XlsxCodec.escapeXml("a&b<c>"))
        assertEquals("a&b<c>", XlsxCodec.unescapeXml("a&amp;b&lt;c&gt;"))
        assertEquals("\"q\"'s'", XlsxCodec.unescapeXml("&quot;q&quot;&apos;s&apos;"))
    }

    @Test
    fun emptyRecordsStillValid() {
        val bytes = XlsxCodec.buildXlsx(emptyList())
        val rows = XlsxCodec.readRows(bytes)
        assertNotNull(rows)
        assertEquals(1, rows!!.size)
    }
}
