package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拼多多「先用后付」回归测试（用户真实通知原文）。
 *
 * 背景（真实 bug）：
 * - **提前一天**的提醒被记成了支出 ✗
 *   标题「先用后付扣款提醒」里有「扣款」→ 命中支出关键词；
 *   正文「明天将自动确认收货，并自动付款6.93元」→ 取到 6.93。
 * - **当天真正扣款**的三条通知反而都没记 ✗
 *   微信那条是「已**扣费**」，而关键词表里只有「扣款」没有「扣费」。
 *
 * 另外：这三条通知（拼多多 / 微信 / 银行）金额方向都相同，会各记一笔 → 需要跨通道去重。
 */
class PddBuyNowPayLaterTest {

    private val pdd = "com.xunmeng.pinduoduo"
    private val wechat = PaymentParser.PKG_WECHAT

    /** 提前一天的提醒：绝对不能记账 */
    @Test
    fun reminderBeforeDeductionIsIgnored() {
        // 图（10-10）：先用后付扣款提醒
        val title = "先用后付扣款提醒"
        val text = "您的先用后付订单明天将自动确认收货，并自动付款6.93元。如未收到商品可联系商家。"
        assertNull("「明天将自动…」是未来时，不该记账", PaymentParser.parse(pdd, title, text))
        assertNull("同一段文本即使带上金额也不能记", PaymentParser.parse(wechat, title, text))
    }

    /** 当天真实扣款：拼多多自己的通知 */
    @Test
    fun pddCompletionNotificationIsRecorded() {
        val p = PaymentParser.parse(
            pdd,
            "先用后付订单完成通知",
            "该订单使用先用后付0元下单，收货后已自动扣款133.9元，如有疑问请联系拼多多客服。"
        )
        assertNotNull("当天扣款必须记账", p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals("金额要取扣款额，不是「0元下单」的 0", 133.9, p.amount, 0.001)
    }

    /** 当天真实扣款：微信支付「已扣费」 */
    @Test
    fun wechatDeductionNotificationIsRecorded() {
        val p = PaymentParser.parse(wechat, "微信支付", "[3条]微信支付: 已扣费¥133.90")
        assertNotNull("「已扣费」以前不在关键词表里，会被漏掉", p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(133.90, p.amount, 0.001)
    }

    /** 当天真实扣款：银行「发生快捷支付扣款」 */
    @Test
    fun bankQuickPayNotificationIsRecorded() {
        val p = PaymentParser.parse(
            wechat,
            "招商银行",
            "您账户1213于10月11日11:27在【财付通-微信支付-拼多多平台商户】发生快捷支付扣款133.90元。"
        )
        assertNotNull("银行扣款通知必须记账", p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(133.90, p.amount, 0.001)
    }

    /**
     * 跨通道去重：同一笔 133.9 的三条通知只留第一条。
     * 同一包名重复出现**不判重**（真实第二笔不能被吞）。
     */
    @Test
    fun samePurchaseAcrossChannelsIsRecordedOnce() {
        var now = 1_000_000L
        val d = Deduper(clock = { now })

        // 第 1 条：拼多多自己的通知 -> 记
        assertFalse(d.crossChannelDuplicate(pdd, RecordType.EXPENSE, 133.9, "拼多多"))
        // 第 2 条：微信「已扣费」（不同包名，商户是平台通用名）-> 跳过
        assertTrue(d.crossChannelDuplicate(wechat, RecordType.EXPENSE, 133.9, "微信支付"))
        // 第 3 条：银行（不同包名，商户里含「拼多多」）-> 跳过
        assertTrue(d.crossChannelDuplicate(wechat, RecordType.EXPENSE, 133.9, "财付通-微信支付-拼多多平台商户"))

        // 同包名再来一条相同金额：视为真实第二笔，不能吞
        assertFalse("同一通道连续两笔相同金额应保留", d.crossChannelDuplicate(pdd, RecordType.EXPENSE, 133.9, "拼多多"))

        // 金额不同：不是同一笔
        assertFalse(d.crossChannelDuplicate(wechat, RecordType.EXPENSE, 6.93, "微信支付"))

        // 超过窗口（90 秒）后同金额也不再判重
        now += 91_000
        assertFalse(d.crossChannelDuplicate(pdd, RecordType.EXPENSE, 133.9, "拼多多"))
    }

    /** 提醒里出现「扣款」不应被当成支出；但真正的「已扣款」必须能记 */
    @Test
    fun futureKeywordsDoNotAffectPastPayments() {
        assertNull(PaymentParser.parse(pdd, "先用后付扣款提醒", "将于明天扣款6.93元"))
        assertNotNull(PaymentParser.parse(pdd, "扣款成功", "已扣款133.9元"))
        assertNotNull(PaymentParser.parse(pdd, "自动扣费", "会员自动扣费19.00元"))
    }

    /**
     * 诊断信息必须能说清"为什么没记" —— 识别记录里会直接显示这句，
     * 用户就不用来回猜（这次就是因为看不到原因才排查了很久）。
     */
    @Test
    fun rejectReasonExplainsWhy() {
        PaymentParser.parse(pdd, "先用后付扣款提醒", "您的订单明天将自动确认收货，并自动付款6.93元")
        assertTrue(
            "应说明是提醒/预告，实际：${PaymentParser.lastRejectReason}",
            PaymentParser.lastRejectReason.contains("提醒") || PaymentParser.lastRejectReason.contains("预告")
        )

        PaymentParser.parse(pdd, "某某商城", "双十一大促，全场五折，点击查看")
        assertTrue(
            "应说明判断不了方向，实际：${PaymentParser.lastRejectReason}",
            PaymentParser.lastRejectReason.contains("方向") || PaymentParser.lastRejectReason.contains("营销")
        )
    }
}
