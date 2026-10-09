package com.jianji.app.util

import com.jianji.app.core.Record
import com.jianji.app.core.RecordSource
import com.jianji.app.core.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvCodecTest {

    @Test
    fun plainRoundtrip() {
        val line = CsvCodec.buildLine(listOf("2025-01-01 12:00:00", "支出", "餐饮", "26.00", "老王烧烤", "微信自动"))
        assertEquals(6, CsvCodec.parseLine(line).size)
        assertEquals("餐饮", CsvCodec.parseLine(line)[2])
    }

    @Test
    fun quotedCommaAndQuotes() {
        val note = "备注,含逗号与\"引号\""
        val line = CsvCodec.buildLine(listOf("2025-01-01 12:00:00", "收入", "收款", "100.00", note, "手动"))
        val parsed = CsvCodec.parseLine(line)
        assertEquals(note, parsed[4])
    }

    @Test
    fun emptyFields() {
        val parsed = CsvCodec.parseLine("a,,c,")
        assertEquals(listOf("a", "", "c", ""), parsed)
    }

    @Test
    fun parseLineEscapedQuotes() {
        assertEquals("say \"hi\"", CsvCodec.parseLine("\"say \"\"hi\"\"\"")[0])
    }
}

class CsvFormatTest {

    private val rec = Record(
        id = 1, amount = 26.0, type = RecordType.EXPENSE, category = "餐饮",
        note = "老王烧烤", source = RecordSource.AUTO_WECHAT, time = 1735689600000L // 2025-01-01 08:00:00 UTC
    )

    @Test
    fun buildThenParseRoundtrip() {
        val csv = CsvFormat.buildCsv(listOf(rec))
        val result = CsvFormat.parseCsv(csv)
        assertTrue(!result.badHeader)
        assertEquals(0, result.skippedLines)
        assertEquals(1, result.records.size)
        val r = result.records[0]
        assertEquals(26.0, r.amount, 1e-9)
        assertEquals(RecordType.EXPENSE, r.type)
        assertEquals("餐饮", r.category)
        assertEquals("老王烧烤", r.note)
        assertEquals(rec.time, r.time)
    }

    @Test
    fun badHeaderRejected() {
        // 完全没有「时间/金额」语义的表头才拒绝
        val result = CsvFormat.parseCsv("姓名,电话\n张三,13800000000")
        assertTrue(result.badHeader)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun friendlyHeaderAccepted() {
        // v1.2 起表头智能化：日期+金额即可导入，无类型列时按正负号判断方向
        val result = CsvFormat.parseCsv("日期,金额\n2025-01-01,-35.50\n2025-01-02,100.00")
        assertTrue(!result.badHeader)
        assertEquals(2, result.records.size)
        assertEquals(com.jianji.app.core.RecordType.EXPENSE, result.records[0].type)
        assertEquals(35.50, result.records[0].amount, 1e-9)
        assertEquals(com.jianji.app.core.RecordType.INCOME, result.records[1].type)
    }

    @Test
    fun invalidRowsSkipped() {
        val csv = CsvFormat.buildCsv(listOf(rec)) +
            "2025-01-02 10:00:00,支出,购物,abc,备注,手动\r\n" +           // 金额非法
            "2025-01-02 10:00:00,转账,购物,10.00,备注,手动\r\n" +          // 类型非法
            "bad line without enough fields\r\n" +                       // 字段不足
            "2025-01-02 10:00:00,支出,购物,19.90,备注,手动\r\n"           // 合法
        val result = CsvFormat.parseCsv(csv)
        assertEquals(2, result.records.size) // 原记录 + 1 条合法导入
        assertEquals(3, result.skippedLines)
        assertEquals(19.90, result.records[1].amount, 1e-9)
    }

    @Test
    fun sourceNameRoundtrip() {
        for (s in intArrayOf(
            RecordSource.MANUAL, RecordSource.AUTO_WECHAT, RecordSource.AUTO_ALIPAY,
            RecordSource.AUTO_SCREEN, RecordSource.AUTO_SMS
        )) {
            assertEquals(s, CsvFormat.parseRecordRow(
                listOf("2025-01-02 10:00:00", "支出", "购物", "10.00", "", CsvFormat.sourceName(s))
            )!!.source)
        }
    }
}
