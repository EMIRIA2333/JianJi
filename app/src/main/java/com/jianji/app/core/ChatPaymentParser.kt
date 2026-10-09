package com.jianji.app.core

import java.util.regex.Pattern

/**
 * 聊天页转账 / 红包识别（纯 Kotlin，可单测）。
 *
 * 聊天页整体上**不是账单**（一页可能混着「我发出去的」和「别人发给我的」，方向会记反），
 * 但转账 / 红包气泡自带**双方不会混淆**的状态词，所以这里做「**按气泡逐笔**」解析：
 *
 * | 气泡状态 | 含义 | 结果 |
 * | --- | --- | --- |
 * | 已被接收 | 我转出去、对方已收 | 支出 |
 * | 已收款 | 别人转给我、我已收 | 收入 |
 * | 待对方确认收款 / 已退还 | 转账未完成或被退回 | 跳过 |
 * | 已被领取 / 已被领完 | 我发的红包被领了 | 支出 |
 * | 你领取了 / 已存入零钱 | 我领了别人的红包 | 收入 |
 * | 等待对方领取 | 我发的红包还挂在那（钱已经出去） | 支出 |
 *
 * 只认这些短状态行 + 就近金额，聊天里的普通数字不会被当成账单。
 */
object ChatPaymentParser {

    data class Entry(
        val type: Int,
        val amount: Double,
        val note: String,
        val payMethod: String,
        /** 该气泡对应的分类（转账出=人情，转账入=收款，红包出=人情，红包入=红包） */
        val category: String
    )

    /** 我转出去 / 我发出的红包（含「张三领取了你的红包」这种带名字的系统消息） */
    private val EXPENSE_MARKERS = listOf(
        "已被接收", "已被领取", "已被领完", "已领完", "等待对方领取", "已发送",
        "领取了你的红包", "领取了你的"
    )

    /** 别人转给我 / 我领到的红包（含「你领取了张三的红包」这种带名字的系统消息） */
    private val INCOME_MARKERS = listOf(
        "已收款", "你已收款", "已存入零钱", "你领取了", "红包到账", "已转入零钱", "已收钱"
    )

    /** 未完成或已退回：不记账 */
    private val SKIP_MARKERS = listOf(
        "待对方确认收款", "待确认收款", "已退还", "退还成功", "已过期", "超过24小时未领取", "已取消"
    )

    /** 账单详情页特征：这些页面交给 ReceiptParser，避免重复记账 */
    private val RECEIPT_GUARD = listOf(
        "交易单号", "商户单号", "商户全称", "收款方全称", "账单分类", "当前状态", "收单机构"
    )

    private val AMOUNT_LINE = Pattern.compile(
        "^\\s*[¥￥]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元?\\s*$"
    )

    /** 转账 / 红包气泡里的词（用于确认金额行附近确实是收付气泡） */
    private val PAY_BUBBLE_HINTS = listOf("转账", "红包", "已收款", "已被接收", "零钱")

    private val NOISE_TITLE = listOf(
        "微信", "支付宝", "转账", "红包", "微信红包", "转账详情", "已收款", "已被接收",
        "对方正在输入", "按住 说话", "按住说话", "语音输入", "表情", "发送", "取消", "确定",
        // 气泡状态词本身不能当联系人名
        "已被领取", "已被领完", "已领完", "已存入零钱", "你领取了", "红包到账", "已转入零钱",
        "待对方确认收款", "待确认收款", "已退还", "退还成功", "等待对方领取", "已发送"
    )

    /**
     * @return 逐笔解析出的转账/红包；不是聊天页或没有可确认的气泡时返回空
     */
    fun parse(pkg: String, screenText: String): List<Entry> {
        val text = screenText.replace('\u00A0', ' ')
        // 快速预筛：整页没有任何气泡状态词就直接返回（聊天页扫描很频繁，这步很省）
        if ((EXPENSE_MARKERS + INCOME_MARKERS + SKIP_MARKERS).none { text.contains(it) }) return emptyList()
        // 账单详情页交给 ReceiptParser，避免同一笔记两次
        if (RECEIPT_GUARD.count { text.contains(it) } >= 2) return emptyList()

        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 3) return emptyList()

        val title = extractChatTitle(lines) ?: PaymentParser.platformName(pkg)
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()

        for (i in lines.indices) {
            val marker = markerAt(lines[i]) ?: continue
            if (isSkip(marker)) continue
            val hit = nearestAmount(lines, i) ?: continue
            val amount = hit.first
            val type = if (isIncome(marker)) RecordType.INCOME else RecordType.EXPENSE
            // 去重键带上「金额所在行」：同一气泡被 a11y 拆成两行时不会重复，
            // 而页面上真的有两个同金额气泡（各自一行金额）时会分别记账
            val key = "$type|${Math.round(amount * 100)}|${hit.second}"
            if (!seen.add(key)) continue
            val context = (maxOf(0, i - 2)..minOf(lines.size - 1, i + 2)).joinToString(" ") { lines[it] }
            val isPacket = marker.contains("领取") || context.contains("红包")
            out.add(
                Entry(
                    type = type,
                    amount = amount,
                    note = title,
                    payMethod = ReceiptParser.extractPayMethod(context),
                    category = when {
                        isPacket && type == RecordType.INCOME -> "红包"
                        isPacket -> "人情"
                        type == RecordType.INCOME -> "收款"
                        else -> "人情"
                    }
                )
            )
        }
        return out
    }

    /**
     * 该行是否是气泡/系统状态行。
     * 用 contains 匹配（不再要求整行相等），因为真实微信常是
     * 「张三领取了你的红包」「你领取了张三的红包」这种带名字的句子；
     * 但把行长度限制在 24 字内，普通聊天长句不会误判。
     */
    private fun markerAt(line: String): String? {
        val s = line.trim().trimEnd('>', '›').trim()
        if (s.isEmpty() || s.length > 24) return null
        (INCOME_MARKERS + EXPENSE_MARKERS + SKIP_MARKERS).forEach { m ->
            if (s.contains(m)) return m
        }
        return null
    }

    private fun isSkip(marker: String) = SKIP_MARKERS.contains(marker)
    private fun isIncome(marker: String) = INCOME_MARKERS.contains(marker)

    /** 状态行上下各 4 行内找金额，返回 (金额, 金额所在行号)；找不到返回 null */
    private fun nearestAmount(lines: List<String>, idx: Int): Pair<Double, Int>? {
        // 先看状态行本身是否内嵌金额
        amountEmbedded(lines[idx])?.let {
            return it to idx
        }
        for (offset in 1..4) {
            listOf(idx - offset, idx + offset).forEach { j ->
                if (j < 0 || j >= lines.size) return@forEach
                val m = AMOUNT_LINE.matcher(lines[j])
                if (!m.matches()) return@forEach
                val v = m.group(1).replace(",", "").toDoubleOrNull() ?: return@forEach
                if (v <= 0.0 || v > PaymentParser.MAX_AMOUNT) return@forEach
                val ctx = (maxOf(0, j - 2)..minOf(lines.size - 1, j + 2)).joinToString(" ") { lines[it] }
                if (PAY_BUBBLE_HINTS.any { ctx.contains(it) }) {
                    return Math.round(v * 100.0) / 100.0 to j
                }
            }
        }
        return null
    }

    /** 从一行里提取内嵌金额（如「¥0.01 已被接收」「已收款 50.00」） */
    private fun amountEmbedded(line: String): Double? {
        val m = Regex("[¥￥]?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)").find(line) ?: return null
        val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        return if (v in 0.01..PaymentParser.MAX_AMOUNT) Math.round(v * 100.0) / 100.0 else null
    }

    /** 会话标题（联系人 / 群名）：从上往下找第一条像名字的文本 */
    fun extractChatTitle(lines: List<String>): String? {
        lines.take(15).forEach { raw ->
            val s = raw.trim()
            if (s.length !in 2..20) return@forEach
            if (NOISE_TITLE.any { s == it || s.startsWith(it) }) return@forEach
            if (s.matches(Regex("[0-9.,¥￥+\\-:/\\s]+"))) return@forEach
            // 时间分隔（晚上 9:39 / 10月1日 21:00）不是名字
            if (s.matches(Regex(".*[0-9]{1,2}:[0-9]{2}.*"))) return@forEach
            if (s.contains(":")) return@forEach
            return s
        }
        return null
    }
}
