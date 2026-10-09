package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 账单详情页解析测试：样本取自真机截图（微信支付凭证页 / 支付宝账单详情页）。
 */
class ReceiptParserTest {

    /** 微信「全部账单」- 支付凭证详情页（金额是独占一行的 -17.00） */
    private val wechatReceipt = """
        全部账单
        1
        示例学院
        -17.00
        当前状态 支付成功
        支付时间 2026年10月1日 18:04:53
        商品 示例学院-二楼小吃9001A收款
        商户全称 示例学院
        收单机构 财付通支付科技有限公司
        支付方式 零钱
        交易单号 4200000/000000000000000000000
        商户单号 可在支持的商户扫码退款
        26000000000000000000000000000000
        账单服务
        对订单有疑惑 发起群收款
        在此商户的交易 申请电子凭证
    """.trimIndent()

    /** 支付宝账单详情页（含免息券/积分等营销文案，金额 -0.90，平台自带分类） */
    private val alipayReceipt = """
        账单详情
        全部账单
        一楼大餐6006C
        -0.90
        交易成功
        支付时间 2026-09-30 17:03:27
        付款方式 花呗
        商品说明 直接付款
        支付奖励 立即领取4积分
        收单机构 支付宝支付科技有限公司
        收款方全称 示例学院
        推荐服务
        你有3张免息券待领取 去领取
        更多
        账单管理 我的消费图鉴
        账单分类 教育培训
        标签 请选择
        开通记账本，自动生成标签统计报告
    """.trimIndent()

    /** 支付宝免密/自动扣款页面 */
    private val alipayAutoPay = """
        账单详情
        全部账单
        企鹅科技 >
        -0.11
        自动扣款成功
        管理免密支付 免密支付
        支付时间 2026-10-01 15:13:25
        付款方式 花呗
        支付奖励 立即领取4积分
        交易详情
        胖乖生活 支付成功
        2-1-1/0 智能设备消费 共1件
        推荐服务
        你有3张免息券待领取 去领取
        账单管理 已解锁"家居数码"贴纸
        账单分类 生活服务
        标签 请选择
    """.trimIndent()

    @Test
    fun wechatReceiptPage() {
        val p = ReceiptParser.parse(PaymentParser.PKG_WECHAT, wechatReceipt)
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(17.00, p.amount, 1e-9)
        assertTrue(p.merchant.contains("9001A") || p.merchant.contains("示例学院"))
        // 支付时间取的是页面里的真实时刻（2026-10-01 18:04:53 本地时区）
        val expect = com.jianji.app.util.TimeUtil.parseAny("2026年10月1日 18:04:53")
        assertEquals(expect, p.time)
        // 商户含「小吃」-> 餐饮
        assertEquals("餐饮", p.categoryHint)
    }

    @Test
    fun alipayReceiptPageWithMarketingNoise() {
        val p = ReceiptParser.parse(PaymentParser.PKG_ALIPAY, alipayReceipt)
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(0.90, p.amount, 1e-9)
        assertEquals("示例学院", p.merchant)
        // 平台自带分类「教育培训」-> 教育
        assertEquals("教育", p.categoryHint)
        assertEquals(com.jianji.app.util.TimeUtil.parseAny("2026-09-30 17:03:27"), p.time)
    }

    @Test
    fun alipayAutoDeductPageUsesMerchantAboveAmount() {
        val p = ReceiptParser.parse(PaymentParser.PKG_ALIPAY, alipayAutoPay)
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(0.11, p.amount, 1e-9)
        // 金额上方最近的非噪声行是商户名
        assertEquals("企鹅科技", p.merchant)
        assertEquals("其他", p.categoryHint) // 生活服务 -> 其他
    }

    @Test
    fun pageWithoutStrongEvidenceRejected() {
        assertNull(ReceiptParser.parse(PaymentParser.PKG_ALIPAY, "账单详情\n待支付\n-0.90\n去支付"))
        assertNull(ReceiptParser.parse(PaymentParser.PKG_ALIPAY, "购物车\n合计 12.00\n去结算"))
    }

    @Test
    fun signedLineAmountDirection() {
        assertEquals(-17.0, ReceiptParser.extractAmount("支付成功\n-17.00")!!, 1e-9)
        assertEquals(17.0, ReceiptParser.extractAmount("收款成功\n17.00")!!, 1e-9)
        // 全角负号也要识别为负
        assertEquals(-0.11, ReceiptParser.extractAmount("交易成功\n－0.11")!!, 1e-9)
        // 带 ¥ 的优先于后面的负数行
        assertEquals(26.0, ReceiptParser.extractAmount("支付成功\n¥26.00\n-99.00")!!, 1e-9)
    }

    @Test
    fun ignoresLongBarcodeNumbers() {
        // 条码/单号这类长数字不能当成金额
        assertNull(ReceiptParser.extractAmount("支付成功\n26000000000000000000000000000000"))
        assertNull(ReceiptParser.extractAmount("支付成功\n4200000/000000000000000000000"))
    }

    @Test
    fun platformCategoryMapping() {
        assertEquals("餐饮", ReceiptParser.extractPlatformCategory("账单分类 餐饮美食"))
        assertEquals("交通", ReceiptParser.extractPlatformCategory("账单分类 交通出行"))
        assertEquals("人情", ReceiptParser.extractPlatformCategory("账单分类 转账红包"))
        assertEquals("教育", ReceiptParser.extractPlatformCategory("账单分类 教育培训"))
        assertNull(ReceiptParser.extractPlatformCategory("随便一个词"))
    }

    // ---------- 支付方式（支出工具） ----------

    @Test
    fun wechatPayMethod() {
        val p = ReceiptParser.parse(PaymentParser.PKG_WECHAT, wechatReceipt)
        assertEquals("零钱", p!!.payMethod)
    }

    @Test
    fun alipayPayMethod() {
        val p = ReceiptParser.parse(PaymentParser.PKG_ALIPAY, alipayReceipt)
        assertEquals("花呗", p!!.payMethod)
    }

    @Test
    fun payMethodFallbackByKeyword() {
        assertEquals("余额宝", ReceiptParser.extractPayMethod("交易成功\n使用余额宝付款"))
        assertEquals("云闪付", ReceiptParser.extractPayMethod("支付成功 云闪付 已扣款"))
        assertEquals("", ReceiptParser.extractPayMethod("付款方式 请选择"))
        assertEquals("", ReceiptParser.extractPayMethod("支付成功 ¥10.00"))
    }

    @Test
    fun incomeReceiptPage() {
        val text = """
            账单详情
            某某公司
            5,600.00
            收款成功
            支付时间 2026-10-01 09:00:00
            收款方全称 我的账户
        """.trimIndent()
        val p = ReceiptParser.parse(PaymentParser.PKG_ALIPAY, text)
        assertNotNull(p)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(5600.0, p.amount, 1e-9)
    }

    @Test
    fun timeParsingSupportsChineseFormat() {
        val zone = ZoneId.systemDefault()
        val t = com.jianji.app.util.TimeUtil.parseAny("2026年10月1日 18:04:53")!!
        val dt = java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDateTime()
        assertEquals(2026, dt.year)
        assertEquals(10, dt.monthValue)
        assertEquals(1, dt.dayOfMonth)
        assertEquals(18, dt.hour)
        assertEquals(4, dt.minute)
        assertEquals(53, dt.second)
    }
}
