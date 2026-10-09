package com.jianji.app.util

import com.jianji.app.core.Record
import com.jianji.app.core.RecordSource
import com.jianji.app.core.RecordType

/**
 * CSV 序列化与解析（纯逻辑，供导出到 SAF uri / 导入解析共用）。
 * 表头：时间,类型,分类,金额,备注,来源（UTF-8，Excel 需 BOM）
 * 导入侧走 [SheetParser] 的智能表头映射，兼容列顺序变化与常见改名。
 */
object CsvFormat {

    const val HEADER = "时间,类型,分类,金额,备注,标签,支付方式,来源"

    fun recordToFields(r: Record): List<String> = listOf(
        TimeUtil.formatFull(r.time),
        if (r.type == RecordType.INCOME) "收入" else "支出",
        r.category,
        SheetParser.amountCell(r.amount),
        r.note.replace("\r", " ").replace("\n", " "),
        r.tag.replace("\r", " ").replace("\n", " "),
        r.payMethod.replace("\r", " ").replace("\n", " "),
        sourceName(r.source)
    )

    fun buildCsv(records: List<Record>, withBom: Boolean = true): String {
        val sb = StringBuilder()
        if (withBom) sb.append('\uFEFF')
        sb.append(HEADER).append("\r\n")
        records.forEach { r -> sb.append(CsvCodec.buildLine(recordToFields(r))).append("\r\n") }
        return sb.toString()
    }

    fun rowsFromCsv(text: String): List<List<String>> =
        text.replace("\uFEFF", "")
            .split("\r\n", "\n", "\r")
            .filter { it.isNotBlank() }
            .map { CsvCodec.parseLine(it) }

    /** 解析 CSV 文本为账单列表（智能表头映射） */
    fun parseCsv(text: String): SheetParser.ImportResult =
        SheetParser.recordsFromRows(rowsFromCsv(text))

    /**
     * 按固定列序解析一行。兼容三种列序：
     * - 8 列（v1.5+）：时间,类型,分类,金额,备注,标签,支付方式,来源
     * - 7 列（v1.3+）：时间,类型,分类,金额,备注,标签,来源
     * - 6 列（旧版）：时间,类型,分类,金额,备注,来源
     */
    fun parseRecordRow(fields: List<String>): Record? {
        if (fields.size < 4) return null
        val time = TimeUtil.parseFull(fields[0].trim())
            ?: TimeUtil.parseMinute(fields[0].trim())
            ?: TimeUtil.parseDateOnly(fields[0].trim())
            ?: return null
        val type = when (fields[1].trim()) {
            "收入" -> RecordType.INCOME
            "支出" -> RecordType.EXPENSE
            else -> return null
        }
        val amount = fields[3].trim().replace(",", "").toDoubleOrNull() ?: return null
        if (amount <= 0.0 || amount > com.jianji.app.core.PaymentParser.MAX_AMOUNT) return null
        val category = fields.getOrNull(2)?.trim().orEmpty().ifEmpty { "其他" }
        val note = fields.getOrNull(4)?.trim().orEmpty()
        val tag = if (fields.size >= 7) fields[5].trim() else ""
        val payMethod = if (fields.size >= 8) fields[6].trim() else ""
        val sourceField = when {
            fields.size >= 8 -> fields[7]
            fields.size == 7 -> fields[6]
            else -> fields.getOrNull(5)
        }
        val source = sourceFromName(sourceField?.trim().orEmpty())
        return Record(
            amount = Math.round(amount * 100.0) / 100.0,
            type = type,
            category = category,
            note = note,
            tag = tag,
            payMethod = payMethod,
            source = source,
            time = time
        )
    }

    fun sourceName(s: Int): String = when (s) {
        RecordSource.MANUAL -> "手动"
        RecordSource.AUTO_WECHAT -> "微信自动"
        RecordSource.AUTO_ALIPAY -> "支付宝自动"
        RecordSource.AUTO_SMS -> "短信自动"
        else -> "屏幕识别"
    }

    private fun sourceFromName(n: String): Int = when {
        n.contains("微信") -> RecordSource.AUTO_WECHAT
        n.contains("支付宝") -> RecordSource.AUTO_ALIPAY
        n.contains("短信") -> RecordSource.AUTO_SMS
        n.contains("屏幕") -> RecordSource.AUTO_SCREEN
        else -> RecordSource.MANUAL
    }
}
