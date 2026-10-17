package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 退款 / 境外消费 / 冲正 的回归测试（全部来自用户笔记里的真实通知与短信）。
 *
 * 背景（真实 bug）：
 * - 支付宝「你收到一笔90.50元退款，点此查看账单详情！」**完全不记**：
 *   「退款」不在收入关键词里，而正文的「点此查看」又命中 SOFT_DENY 的引导词。
 * - 中行境外消费「金额1.38新加坡元，已冻结7.36人民币」**整条不记**：
 *   `冻结` 被当成了拦截词。
 * - 中行冲正「…消费**冲正**交易，金额1.38新加坡元，已解冻7.36人民币」被记成**支出 1.38**：
 *   冲正是反向撤销，不该记；而且金额应取人民币 7.36。
 */
class RefundAndForeignCurrencyTest {

    private val alipay = "com.eg.android.AlipayGphone"

    // ---------------- 支付宝退款 ----------------

    @Test
    fun alipayRefundReminderIsIncome() {
        val p = PaymentParser.parse(alipay, "退款提醒", "你收到一笔90.50元退款，点此查看账单详情！")
        assertNotNull("支付宝退款提醒必须识别（以前被判成营销文案丢掉）", p)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(90.50, p.amount, 0.001)
    }

    @Test
    fun alipaySpendingStillWorks() {
        // 同一条通知组里的支出，不能被上面的改动带偏
        val p = PaymentParser.parse(alipay, "交易提醒", "你有一笔87.00元的支出，点击领取6个支付宝积分。")
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(87.00, p.amount, 0.001)
    }

    @Test
    fun failedRefundIsIgnored() {
        assertNull(PaymentParser.parse(alipay, "退款提醒", "您的退款失败，银行处理中，请稍后重新提交"))
    }

    // ---------------- 境外消费（人民币优先） ----------------

    @Test
    fun overseasSmsUsesCnyAmount() {
        val sms = "您的借记卡3233于1017发生双信息无卡消费交易，金额1.38新加坡元，已冻结7.36人民币。【中国银行】"
        val p = SmsParser.parse("95566", sms)
        assertNotNull("境外消费短信必须识别（以前被「冻结」拦掉了）", p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals("必须记人民币 7.36，而不是新币 1.38", 7.36, p.amount, 0.001)
    }

    @Test
    fun reversalIsIgnored() {
        // 冲正 = 反向撤销，不能当成新支出（以前记成 1.38）
        val sms = "您的借记卡3233于1017发生双信息无卡消费冲正交易，金额1.38新加坡元，已解冻7.36人民币。【中国银行】"
        assertNull("冲正不该记账", SmsParser.parse("95566", sms))
    }

    @Test
    fun usdSmsStillUsesCnyAmount() {
        val sms = "【招商银行】您账户于10月17日在新加坡消费USD 12.34，折合人民币88.88元。"
        val p = SmsParser.parse("95555", sms)
        assertNotNull(p)
        assertEquals(88.88, p!!.amount, 0.001)
    }

    @Test
    fun pureForeignAmountFallsBackToFx() {
        // 只有外币、没有人民币金额时，仍然要记（用外币数值），总比丢掉好
        val sms = "【中国银行】您尾号3233的信用卡在境外消费金额1.38美元。"
        val p = SmsParser.parse("95566", sms)
        assertNotNull(p)
        assertEquals(1.38, p!!.amount, 0.001)
    }

    @Test
    fun frozenAccountNoticeIsStillDenied() {
        // 收窄「冻结」拦截词后，真正的"账户被冻结"通知仍要被拦下
        val sms = "【中国银行】您的账户已被冻结，请携带身份证到柜台办理解冻。"
        assertNull(SmsParser.parse("95566", sms))
    }
}
