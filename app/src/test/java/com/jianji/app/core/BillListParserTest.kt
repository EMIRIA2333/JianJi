package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 账单列表页解析测试：补齐「不弹通知」的账单 */
class BillListParserTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val todayStart: Long = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
    private val yesterdayStart: Long = todayStart - DAY_MS

    /** 微信账单列表（商户 + 金额，日期分组） */
    private val wechatBills = """
        账单
        全部账单
        今天
        示例学院-二楼小吃9001A收款
        -17.00
        示例小吃
        -23.90
        昨天
        一楼大餐6006C
        -0.90
        永辉超市
        -56.00
    """.trimIndent()

    /** 支付宝账单列表（带支付方式行） */
    private val alipayBills = """
        账单
        2026年10月
        今天
        示例学院-二楼小吃9001A收款
        花呗
        -17.00
        示例小吃
        余额宝
        -23.90
        昨天
        超市购物
        储蓄卡
        -56.00
        收到转账
        +100.00
    """.trimIndent()

    // ---------- 误识别防护（真机事故：淘宝商品页被当成账单） ----------

    @Test
    fun shoppingAppPageIsNeverTreatedAsBillList() {
        // 淘宝商品/订单页：多个价格 + 「账单」入口字样，但不是账单
        val taobao = """
            我的淘宝
            账单
            露管灯电源盒
            ¥29.20
            支架
            ¥13.30
            耐脏玄关脚垫
            ¥33.81
            接头公母两头对接插座
            ¥0.95
            加入购物车
            立即购买
        """.trimIndent()
        assertTrue(BillListParser.parse("com.taobao.taobao", taobao, todayStart).isEmpty())
        // 其它购物平台同样拒绝
        assertTrue(BillListParser.parse("com.jingdong.app.mall", taobao, todayStart).isEmpty())
        assertTrue(BillListParser.parse("com.xunmeng.pinduoduo", taobao, todayStart).isEmpty())
    }

    @Test
    fun orderPageMarkersRejectEvenInsidePaymentApp() {
        // 微信小程序商城（包名仍是微信）的订单页也不能当账单
        val miniShop = """
            账单
            今天
            露管灯电源盒
            -29.20
            支架
            -13.30
            加入购物车
            立即购买
        """.trimIndent()
        assertTrue(BillListParser.parse(PaymentParser.PKG_WECHAT, miniShop, todayStart).isEmpty())
    }

    @Test
    fun productPricesWithoutEvidenceAreRejected() {
        // 商品价格：无正负号、无交易状态、无支付方式 -> 不算账单
        val priceList = """
            账单
            今天
            某商品A
            29.20
            某商品B
            13.30
            某商品C
            33.81
        """.trimIndent()
        assertTrue(BillListParser.parse(PaymentParser.PKG_ALIPAY, priceList, todayStart).isEmpty())
    }

    @Test
    fun pageWithoutDateHeaderIsRejected() {
        // 真实账单按日期分组；没有日期头的列表不算账单
        val noDate = """
            账单
            某商户
            -17.00
            另一商户
            -23.90
        """.trimIndent()
        assertTrue(BillListParser.parse(PaymentParser.PKG_WECHAT, noDate, todayStart).isEmpty())
    }

    @Test
    fun unsignedAmountWithStatusIsAccepted() {
        // 带交易状态的（无正负号）仍应识别，例如「交易成功 ¥17.00」
        val page = """
            账单
            今天
            示例学院-二楼小吃
            交易成功
            ¥17.00
            永辉超市
            交易成功
            ¥56.00
        """.trimIndent()
        val entries = BillListParser.parse(PaymentParser.PKG_ALIPAY, page, todayStart)
        assertEquals(2, entries.size)
        entries.forEach { assertEquals(RecordType.EXPENSE, it.type) }
    }

    @Test
    fun parsesWechatBillList() {
        val entries = BillListParser.parse(PaymentParser.PKG_WECHAT, wechatBills, todayStart + 3600_000)
        assertEquals(4, entries.size)
        val first = entries.first { it.amount == 17.0 }
        assertEquals(RecordType.EXPENSE, first.type)
        assertTrue(first.merchant.contains("9001A"))
        assertEquals(todayStart, LocalDate.ofInstant(Instant.ofEpochMilli(first.time), zone)
            .atStartOfDay(zone).toInstant().toEpochMilli())
    }

    @Test
    fun parsesAlipayBillListWithPayMethod() {
        val entries = BillListParser.parse(PaymentParser.PKG_ALIPAY, alipayBills, todayStart + 3600_000)
        assertEquals(4, entries.size)
        assertEquals("花呗", entries.first { it.amount == 17.0 }.payMethod)
        assertEquals("余额宝", entries.first { it.amount == 23.9 }.payMethod)
        assertEquals("储蓄卡", entries.first { it.amount == 56.0 }.payMethod)
        // 收入方向
        val income = entries.first { it.amount == 100.0 }
        assertEquals(RecordType.INCOME, income.type)
    }

    @Test
    fun yesterdayGroupUsesYesterdayDate() {
        val entries = BillListParser.parse(PaymentParser.PKG_WECHAT, wechatBills, todayStart + 3600_000)
        val y = entries.first { it.amount == 0.9 }
        assertEquals(yesterdayStart, LocalDate.ofInstant(Instant.ofEpochMilli(y.time), zone)
            .atStartOfDay(zone).toInstant().toEpochMilli())
    }

    @Test
    fun detailPageIsNotTreatedAsList() {
        // 账单详情页（含多个详情标签）应交给 ReceiptParser，不在这里解析
        val detail = """
            账单详情
            全部账单
            一楼大餐6006C
            -0.90
            交易成功
            支付时间 2026-09-30 17:03:27
            付款方式 花呗
            收款方全称 示例学院
            商品说明 直接付款
        """.trimIndent()
        assertTrue(BillListParser.parse(PaymentParser.PKG_ALIPAY, detail, todayStart).isEmpty())
    }

    @Test
    fun nonBillPageRejected() {
        val chat = """
            微信支付
            你已成功向【老王烧烤】支付¥26.00
            收款到账100.00元
        """.trimIndent()
        assertTrue(BillListParser.parse(PaymentParser.PKG_WECHAT, chat, todayStart).isEmpty())
    }

    @Test
    fun singleAmountNotTreatedAsList() {
        val page = """
            账单
            今天
            某商户
            -17.00
        """.trimIndent()
        assertTrue(BillListParser.parse(PaymentParser.PKG_WECHAT, page, todayStart).isEmpty())
    }

    @Test
    fun failedAndClosedTransactionsSkipped() {
        val page = """
            账单
            今天
            某商户
            支付失败
            -17.00
            另一商户
            -23.90
        """.trimIndent()
        val entries = BillListParser.parse(PaymentParser.PKG_WECHAT, page, todayStart)
        assertEquals(1, entries.size)
        assertEquals(23.9, entries[0].amount, 1e-9)
    }

    @Test
    fun dateHeaderParsing() {
        assertEquals(todayStart, BillListParser.parseDayHeader("今天", todayStart))
        assertEquals(yesterdayStart, BillListParser.parseDayHeader("昨天", todayStart))
        val ymd = BillListParser.parseDayHeader("2026年10月1日", todayStart)
        assertEquals(LocalDate.of(2026, 10, 1), LocalDate.ofInstant(Instant.ofEpochMilli(ymd!!), zone))
        val dash = BillListParser.parseDayHeader("2026-09-30", todayStart)
        assertEquals(LocalDate.of(2026, 9, 30), LocalDate.ofInstant(Instant.ofEpochMilli(dash!!), zone))
        assertEquals(null, BillListParser.parseDayHeader("全部账单", todayStart))
    }

    @Test
    fun duplicateRowsCollapsed() {
        val page = """
            账单
            今天
            某商户
            -17.00
            某商户
            -17.00
            其他商户
            -23.90
        """.trimIndent()
        val entries = BillListParser.parse(PaymentParser.PKG_WECHAT, page, todayStart)
        assertEquals(2, entries.size)
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
