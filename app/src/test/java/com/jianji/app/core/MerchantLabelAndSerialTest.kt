package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 真机截图里发现的商户/金额误判：
 * - 商户「拼多多平台商户」含「商户」二字，曾被 NOT_MERCHANT 的 contains 直接判死，
 *   兜底取到平台名「支付宝」；
 * - 页面里有「商户单号 XP1026…」的长数字串，曾被当成金额。
 */
class MerchantLabelAndSerialTest {

    private val pddPage = """
        账单详情
        全部账单
        拼多多平台商户
        -96.99
        交易成功
        极速付款
        支付时间 2026-11-13 14:12:07
        付款方式 花呗
        商品说明 商户单号XP1026111314200633236171006857
        支付奖励 立即领取12积分
        更多
        账单分类 日用百货
        标签 请选择
        备注 添加
    """.trimIndent()

    @Test
    fun merchantWithWordShangHuIsKept() {
        assertEquals("拼多多平台商户", ReceiptParser.extractMerchant(pddPage))
    }

    @Test
    fun amountIsNotTakenFromSerialNumber() {
        // extractAmount 按设计保留符号（用于判方向），所以这里是 -96.99
        assertEquals(-96.99, ReceiptParser.extractAmount(pddPage)!!, 1e-9)
    }

    @Test
    fun parsesAsExpense() {
        val p = ReceiptParser.parse("com.eg.android.AlipayGphone", pddPage)
        assertNotNull("这张账单必须能识别：${ReceiptParser.lastRejectReason}", p)
        assertEquals(96.99, p!!.amount, 1e-9)
        assertEquals(RecordType.EXPENSE, p.type)
        assertEquals("拼多多平台商户", p.merchant)
    }

    @Test
    fun plainLabelStillRejectedAsMerchant() {
        // 纯标签「商户全称」仍不能当商户名
        val text = "账单详情\n商户全称\n-11.00\n支付成功\n支付时间 2026-11-13 16:19:40"
        val m = ReceiptParser.extractMerchant(text)
        assertEquals(false, m == "商户全称")
    }
}
