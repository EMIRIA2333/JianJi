package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 银行短信解析测试 */
class SmsParserTest {

    @Test
    fun cmbExpenseWithMerchant() {
        val p = SmsParser.parse(
            "95555",
            "【招商银行】您尾号1234的储蓄卡10月1日12:00支付宝消费人民币26.00元，余额1034.50元"
        )
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(26.00, p.amount, 1e-9)
        assertEquals("招商银行", p.bank)
        assertEquals("支付宝", p.merchant)
    }

    @Test
    fun icbcTransferOut() {
        val p = SmsParser.parse(
            "95588",
            "您尾号8888的账户10月1日完成跨行转出交易1000.00元，手续费0.00元。"
        )
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(1000.00, p.amount, 1e-9)
    }

    @Test
    fun salaryIncome() {
        val p = SmsParser.parse(
            "95533",
            "您尾号4321的账户11月10日代发工资入账人民币5,600.00元。"
        )
        assertNotNull(p)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(5600.00, p.amount, 1e-9)
    }

    @Test
    fun refundIncome() {
        val p = SmsParser.parse(
            "95533",
            "【建设银行】退款50.00元已退回您尾号4321储蓄卡。"
        )
        assertNotNull(p)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(50.00, p.amount, 1e-9)
    }

    @Test
    fun ccbConsumptionMerchantBracket() {
        val p = SmsParser.parse(
            "95533",
            "您尾号7788的账户10月2日在（美团）消费120.50元。"
        )
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals("美团", p!!.merchant)
        assertEquals("餐饮", Categories.guess(p.merchant, false))
    }

    @Test
    fun ignoreVerifyCode() {
        assertNull(
            SmsParser.parse("95555", "【招商银行】验证码 886233，您正在进行支付验证，请勿泄露。")
        )
    }

    @Test
    fun ignoreMarketing() {
        assertNull(
            SmsParser.parse("95588", "【工商银行】专属优惠：消费满200元可领50元支付券，点击激活。")
        )
    }

    @Test
    fun ignoreRepayReminder() {
        assertNull(
            SmsParser.parse("95555", "【招商银行】您的信用卡最低还款额为1200.00元，还款日为10月10日。")
        )
    }

    @Test
    fun ignoreNoAmount() {
        assertNull(SmsParser.parse("95533", "您尾号4321的账户发生一笔交易，请留意。"))
    }

    @Test
    fun ignoreNonBankContext() {
        // 有金额有方向词但无银行语境（非银行号码、无尾号/卡等）
        assertNull(SmsParser.parse("1069000000000", "你的外卖订单已支付35.00元，祝您用餐愉快。"))
    }

    @Test
    fun creditCardRepaySuccess() {
        val p = SmsParser.parse(
            "95588",
            "您尾号6666的信用卡10月1日还款成功，金额2,000.00元。"
        )
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(2000.00, p.amount, 1e-9)
    }

    @Test
    fun incomeByParens() {
        val p = SmsParser.parse(
            "95555",
            "您尾号1234的账户10月3日收款入账（微信提现）300.00元。"
        )
        assertNotNull(p)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals("微信提现", p.merchant)
    }
}
