package com.jianji.app.util

import com.jianji.app.core.PaymentParser
import com.jianji.app.core.Record
import com.jianji.app.core.RecordSource
import com.jianji.app.core.RecordType
import java.util.Locale

/**
 * 表格数据（CSV / Excel 共用）→ 账单记录。
 *
 * 表头智能映射：兼容本应用导出的列名，也兼容常见改名（日期/类别/收支/说明…），
 * 列顺序无关；缺少「类型」列时按金额正负判断（负数=支出，正数=收入）。
 */
object SheetParser {

    data class ImportResult(val records: List<Record>, val skippedLines: Int, val badHeader: Boolean)

    private val TIME_ALIASES = listOf("时间", "日期", "交易时间", "记账时间", "发生时间")
    private val TYPE_ALIASES = listOf("类型", "收支", "收支类型", "方向")
    private val CATEGORY_ALIASES = listOf("分类", "类别", "账单分类")
    private val AMOUNT_ALIASES = listOf("金额", "交易金额", "收支金额", "价钱", "价格")
    private val NOTE_ALIASES = listOf("备注", "说明", "商户", "摘要", "商品")
    private val TAG_ALIASES = listOf("标签", "tag", "标记", "分类标签")
    private val PAY_ALIASES = listOf("支付方式", "付款方式", "支付工具", "支出工具", "支付渠道", "付款渠道")
    private val SOURCE_ALIASES = listOf("来源", "渠道", "账单来源")

    /** 表头 → 列下标；识别不到必要列（时间/金额）时返回 null */
    fun headerIndex(header: List<String>): Map<String, Int>? {
        val clean = header.map { it.trim().removePrefix("\uFEFF") }
        fun find(aliases: List<String>): Int {
            aliases.forEach { a ->
                val i = clean.indexOfFirst { it == a }
                if (i >= 0) return i
            }
            // 退化：包含关系
            aliases.forEach { a ->
                val i = clean.indexOfFirst { it.contains(a) }
                if (i >= 0) return i
            }
            return -1
        }
        val idx = mapOf(
            "time" to find(TIME_ALIASES),
            "type" to find(TYPE_ALIASES),
            "category" to find(CATEGORY_ALIASES),
            "amount" to find(AMOUNT_ALIASES),
            "note" to find(NOTE_ALIASES),
            "tag" to find(TAG_ALIASES),
            "pay" to find(PAY_ALIASES),
            "source" to find(SOURCE_ALIASES)
        )
        if (idx["time"]!! < 0 || idx["amount"]!! < 0) return null
        return idx
    }

    fun recordsFromRows(rows: List<List<String>>): ImportResult {
        val nonEmpty = rows.filter { row -> row.any { it.isNotBlank() } }
        if (nonEmpty.isEmpty()) return ImportResult(emptyList(), 0, true)
        val idx = headerIndex(nonEmpty[0]) ?: return ImportResult(emptyList(), 0, true)

        val out = ArrayList<Record>()
        var skipped = 0
        for (i in 1 until nonEmpty.size) {
            val r = rowToRecord(nonEmpty[i], idx)
            if (r == null) skipped++ else out.add(r)
        }
        return ImportResult(out, skipped, false)
    }

    fun rowToRecord(row: List<String>, idx: Map<String, Int>): Record? {
        fun cell(key: String): String {
            val i = idx[key] ?: -1
            return if (i in row.indices) row[i].trim() else ""
        }

        val time = parseTime(cell("time")) ?: return null
        val amountRaw = cell("amount").replace("¥", "").replace("￥", "").replace(",", "").replace(" ", "")
        var amount = amountRaw.toDoubleOrNull() ?: return null
        if (amount == 0.0 || kotlin.math.abs(amount) > PaymentParser.MAX_AMOUNT) return null

        val typeText = cell("type")
        val type = when {
            // 无类型列（或该行类型为空）：按正负号判断
            typeText.isBlank() -> if (amount < 0) RecordType.EXPENSE else RecordType.INCOME
            typeText.contains("收入") || typeText.contains("收") ||
                typeText.equals("income", true) -> RecordType.INCOME
            typeText.contains("支出") || typeText.contains("支") ||
                typeText.equals("expense", true) -> RecordType.EXPENSE
            // 类型列有值但无法识别（如「转账」）：该行无效，避免误判方向
            else -> return null
        }
        amount = kotlin.math.abs(amount)

        val category = cell("category").ifEmpty { "其他" }
        val note = cell("note")
        val tag = cell("tag")
        val payMethod = cell("pay")
        val source = sourceFromName(cell("source"))
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

    /** 兼容 "yyyy-MM-dd HH:mm:ss"、"yyyy-MM-dd HH:mm"、"yyyy/MM/dd HH:mm"、"yyyy-MM-dd" */
    fun parseTime(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        TimeUtil.parseFull(t)?.let { return it }
        TimeUtil.parseMinute(t)?.let { return it }
        TimeUtil.parseDateOnly(t)?.let { return it }
        return null
    }

    fun sourceName(s: Int): String = CsvFormat.sourceName(s)

    private fun sourceFromName(n: String): Int = when {
        n.contains("微信") -> RecordSource.AUTO_WECHAT
        n.contains("支付宝") -> RecordSource.AUTO_ALIPAY
        n.contains("短信") -> RecordSource.AUTO_SMS
        n.contains("屏幕") -> RecordSource.AUTO_SCREEN
        else -> RecordSource.MANUAL
    }

    fun amountCell(amount: Double): String = String.format(Locale.US, "%.2f", amount)
}
