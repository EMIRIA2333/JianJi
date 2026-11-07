package com.jianji.app.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「月账单汇总页」不能被当成一笔交易（真实反馈：支付宝 11 月账单页的
 * 「支出 ¥189.87 / 收入 ¥0.00 / 本月已省」被记成了 +189.87 的收入）。
 */
class MonthlySummaryGuardTest {

    private val alipay = "com.eg.android.AlipayGphone"

    private val summaryPage = """
        搜索交易记录
        全部
        支出
        转账
        退款
        订单
        筛选
        11
        月
        支出
        ¥189.87
        收入
        ¥0.00
        本月已省
        3.10元
        收支分析
        随心选2+4，不是外卖，北京用…
        日用百货
        -23.98
        今天 16:53
        企鹅共享
        生活服务
        -0.24
        昨天 20:32
    """.trimIndent()

    @Test
    fun monthlySummaryIsNotRecorded() {
        assertNull(
            "月账单汇总页不能记账（会把汇总数字当成一笔交易）",
            ReceiptParser.parse(alipay, summaryPage)
        )
    }

    @Test
    fun singlePaymentPageStillWorks() {
        // 单笔支付成功页不含汇总特征词，必须照常识别
        val payPage = """
            支付成功
            完成
            ¥4.50
            示例便利店
            ¥4.50
            交易方式
            花呗
        """.trimIndent()
        val parsed = ReceiptParser.parse(alipay, payPage)
        assertNotNull("单笔支付页必须继续可用", parsed)
    }

    @Test
    fun singleTransactionDetailHasNoSummaryMarkers() {
        val detail = """
            账单详情
            示例便利店
            -4.50
            当前状态 支付成功
            支付时间 2026年11月6日 10:18
            支付方式 花呗
            交易单号 2026110622001
        """.trimIndent()
        val parsed = ReceiptParser.parse(alipay, detail)
        assertNotNull(parsed)
    }
}
