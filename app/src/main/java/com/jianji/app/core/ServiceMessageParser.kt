package com.jianji.app.core

import java.util.regex.Pattern

/**
 * 应用内「支付消息列表」解析（纯 Kotlin，可单测）。
 *
 * 背景：免密支付 / 自动扣款这类消息**往往不弹系统通知**，只出现在
 * 支付宝「消息 → 交易提醒」或微信「服务通知 / 微信支付」的消息列表里。
 * 系统通知栏的 `TYPE_NOTIFICATION_STATE_CHANGED` 收不到它们，
 * 因此这里专门扫描这些**消息列表页**，把每一张支付卡片单独识别出来。
 *
 * 只认「金额 + 方向状态词」在同一小窗口内成对出现：
 * - 支出：支付成功 / 已支付 / 扣款 / 自动扣款 / 免密支付 / 有一笔…支付 / 已向…转账
 * - 收入：收款成功 / 已收款 / 到账 / 退款成功 / 已退款 / 红包到账 / 已存入零钱
 * - 硬跳过：支付失败 / 待支付 / 待付款 / 已取消 / 交易关闭 / 提现
 * 营销文案（领积分 / 领红包 / 优惠）只有在没有任何方向证据时才不触发 ——
 * 图三那种「…免密/自动扣款支付，点击领取4个积分」会正确记成支出。
 */
object ServiceMessageParser {

    data class Entry(
        val type: Int,
        val amount: Double,
        val merchant: String,
        val payMethod: String
    )

    private val PAGE_MARKERS = listOf(
        "交易提醒", "服务通知", "账单消息", "支付助手", "支付宝通知", "微信支付",
        "收支明细", "通知中心", "收款助手"
    )

    private val EXPENSE_WORDS = listOf(
        "支付成功", "已支付", "付款成功", "扣款成功", "已扣款", "自动扣款", "免密支付",
        "消费成功", "有一笔", "交易成功", "已向", "转账成功", "扣款"
    )
    private val INCOME_WORDS = listOf(
        "收款成功", "已收款", "到账", "退款成功", "已退款", "红包到账", "已存入零钱",
        "收入", "转入成功", "已收钱"
    )
    private val HARD_SKIP = listOf(
        "支付失败", "付款失败", "待支付", "待付款", "已取消", "交易关闭", "提现", "余额不足"
    )

    private val AMOUNT_LINE = Pattern.compile(
        "^\\s*[¥￥]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元?\\s*$"
    )

    fun parse(pkg: String, screenText: String): List<Entry> {
        val text = screenText.replace('\u00A0', ' ')
        if (PAGE_MARKERS.none { text.contains(it) }) return emptyList()
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 3) return emptyList()

        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (i in lines.indices) {
            val m = AMOUNT_LINE.matcher(lines[i])
            if (!m.matches()) continue
            val v = m.group(1).replace(",", "").toDoubleOrNull() ?: continue
            if (v <= 0.0 || v > PaymentParser.MAX_AMOUNT) continue

            val nearby = (maxOf(0, i - 6)..minOf(lines.size - 1, i + 6)).joinToString(" ") { lines[it] }
            if (HARD_SKIP.any { nearby.contains(it) }) continue

            // 方向按「最近的支出词 / 收入词的行距」判定：
            // 消息列表里多张卡片相邻，固定窗口会串到隔壁消息，用最近距离更可靠
            val expenseDist = nearestWordDistance(lines, i, EXPENSE_WORDS)
            val incomeDist = nearestWordDistance(lines, i, INCOME_WORDS)
            val type = when {
                incomeDist == null && expenseDist == null -> continue
                incomeDist == null -> RecordType.EXPENSE
                expenseDist == null -> RecordType.INCOME
                expenseDist < incomeDist -> RecordType.EXPENSE
                incomeDist < expenseDist -> RecordType.INCOME
                else -> continue // 距离相同：方向不明，宁漏勿错
            }

            val window = (maxOf(0, i - 4)..minOf(lines.size - 1, i + 4)).joinToString(" ") { lines[it] }
            val merchant = PaymentParser.extractMerchant(window) ?: PaymentParser.platformName(pkg)
            val key = "$type|${Math.round(v * 100)}|${merchant.lowercase()}"
            if (!seen.add(key)) continue
            out.add(Entry(type, Math.round(v * 100.0) / 100.0, merchant, ReceiptParser.extractPayMethod(window)))
        }
        return out
    }

    /** 在 [idx] 上下各 6 行内，找包含任一关键词的最近行距；找不到返回 null */
    private fun nearestWordDistance(lines: List<String>, idx: Int, words: List<String>): Int? {
        var best: Int? = null
        for (d in 0..6) {
            listOf(idx - d, idx + d).forEach { j ->
                if (j < 0 || j >= lines.size) return@forEach
                val line = lines[j]
                if (words.any { line.contains(it) }) {
                    if (best == null || d < best!!) best = d
                }
            }
        }
        return best
    }
}
