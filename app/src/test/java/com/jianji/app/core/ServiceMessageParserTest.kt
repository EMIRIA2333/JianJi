package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应用内「支付消息列表」测试：不弹系统通知的免密支付也能识别 */
class ServiceMessageParserTest {

    /** 支付宝「消息 → 交易提醒」列表页（含图三那条免密支付消息，卡片间带时间戳） */
    private val alipayMessages = """
        消息
        交易提醒
        你在智能自助服务有一笔0.09元的免密/自动扣款支付，点击领取4个支付宝积分。
        0.09
        10月1日 21:00
        示例小吃 支付成功
        23.90
        10月1日 20:00
        退款到账通知：已退款 3.71 元
        3.71
        10月1日 19:00
    """.trimIndent()

    @Test
    fun parsesMianMiExpenseWithMarketingSuffix() {
        val entries = ServiceMessageParser.parse(PaymentParser.PKG_ALIPAY, alipayMessages)
        val e = entries.first { it.amount == 0.09 }
        assertEquals(RecordType.EXPENSE, e.type)
        assertEquals("智能自助服务", e.merchant)
    }

    @Test
    fun parsesPaymentAndRefundDirections() {
        val entries = ServiceMessageParser.parse(PaymentParser.PKG_ALIPAY, alipayMessages)
        assertEquals(RecordType.EXPENSE, entries.first { it.amount == 23.9 }.type)
        assertEquals(RecordType.INCOME, entries.first { it.amount == 3.71 }.type)
    }

    @Test
    fun nonMessagePageIsIgnored() {
        // 普通账单详情页没有「交易提醒/服务通知」标记
        val detail = "账单详情\n拼多多\n-3.71\n当前状态 已全额退款"
        assertTrue(ServiceMessageParser.parse(PaymentParser.PKG_ALIPAY, detail).isEmpty())
    }

    @Test
    fun hardSkipWordsBlock() {
        val text = "交易提醒\n待支付\n199.00\n订单号 xxx"
        assertTrue(ServiceMessageParser.parse(PaymentParser.PKG_ALIPAY, text).isEmpty())
        val fail = "交易提醒\n支付失败\n199.00"
        assertTrue(ServiceMessageParser.parse(PaymentParser.PKG_ALIPAY, fail).isEmpty())
    }

    @Test
    fun ambiguousDirectionIsSkipped() {
        // 同一窗口同时出现收款和支付 -> 方向不明，跳过（宁漏勿错）
        val text = "交易提醒\n收款成功 支付成功\n88.00"
        assertTrue(ServiceMessageParser.parse(PaymentParser.PKG_ALIPAY, text).isEmpty())
    }
}
