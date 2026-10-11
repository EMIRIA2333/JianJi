package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知文本抽取回归测试（纯逻辑部分）。
 *
 * 背景：支付宝「交易提醒」这类通知用**自定义 RemoteViews**，正文不在
 * `EXTRA_TEXT/BIG_TEXT` 里 —— 旧实现取不到就整条丢掉，表现为「支付宝免密支付不记账」。
 * 现在 [NotifyText] 会依次尝试常规 extras、InboxStyle/MessagingStyle、tickerText，
 * 最后反射读 RemoteViews 的 actions。
 */
class NotifyTextTest {

    @Test
    fun composePicksTitleAndBodies() {
        val r = NotifyText.compose(
            "交易提醒",
            listOf(null, "", "你在智能自助服务有一笔0.24元的免密/自动扣款支付，点击领取4个支付宝积分。")
        )
        assertNotNull(r)
        assertEquals("交易提醒", r!!.first)
        assertTrue(r.second.contains("0.24元"))
    }

    @Test
    fun composeJoinsMultipleBodiesAndDropsDuplicates() {
        val r = NotifyText.compose("标题", listOf("第一行", "第一行", "第二行"))
        assertEquals("第一行\n第二行", r!!.second)
    }

    @Test
    fun composeReturnsNullWhenNothingUseful() {
        assertNull(NotifyText.compose(null, listOf(null, "", "   ")))
        assertNull(NotifyText.compose("  ", emptyList()))
    }

    /** 解析层回归：这条通知必须认成支出 0.24（支付宝免密支付的真实文案） */
    @Test
    fun alipayMianmiNotificationParses() {
        val p = PaymentParser.parse(
            "com.eg.android.AlipayGphone",
            "交易提醒",
            "你在智能自助服务有一笔0.24元的免密/自动扣款支付，点击领取4个支付宝积分。"
        )
        assertNotNull("支付宝免密通知必须能记账", p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(0.24, p.amount, 0.0001)
    }
}
