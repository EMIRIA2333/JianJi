package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `dumpsys notification --noredact` 解析测试。
 * 样本按真机输出格式编写（Android 10~14 的字段名一致）。
 */
class NotificationDumpParserTest {

    private val dump = """
        Notification List:
          NotificationRecord(0x8f2a1b pkg=com.eg.android.AlipayGphone user=0 importance=HIGH)
            uid=10123 userId=0
            android.title=String (交易提醒)
            android.text=String (你在智能自助服务有一笔0.08元的免密/自动扣款支付，点击领取4个支付宝积分。)
            android.progress=0
          NotificationRecord(0x7c31aa pkg=com.tencent.mm user=0 importance=HIGH)
            android.title=String (微信支付)
            android.bigText=String (你已收款 ¥0.01)
            android.text=String (你已收款)
          NotificationRecord(0x6a11bb pkg=com.android.systemui user=0 importance=LOW)
            android.title=String (正在充电)
            android.text=String (78%)
    """.trimIndent()

    @Test
    fun parsesPackageTitleAndText() {
        val items = NotificationDumpParser.parse(dump)
        assertEquals(3, items.size)

        assertEquals("com.eg.android.AlipayGphone", items[0].pkg)
        assertEquals("交易提醒", items[0].title)
        assertTrue(items[0].text.contains("有一笔0.08元"))

        assertEquals("com.tencent.mm", items[1].pkg)
        assertEquals("微信支付", items[1].title)
        // bigText 优先（信息更全）
        assertEquals("你已收款 ¥0.01", items[1].text)

        assertEquals("com.android.systemui", items[2].pkg)
    }

    @Test
    fun parsedNotificationsAreRecognizedAsPayments() {
        // 解析出来的通知要能直接被支付解析器认出来（无缝接入记账管道）
        val items = NotificationDumpParser.parse(dump)
        val alipay = items.first { it.pkg == PaymentParser.PKG_ALIPAY }
        val p = PaymentParser.parse(alipay.pkg, alipay.title, alipay.text)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(0.08, p.amount, 1e-9)
        assertEquals("智能自助服务", p.merchant)

        val wx = items.first { it.pkg == PaymentParser.PKG_WECHAT }
        val q = PaymentParser.parse(wx.pkg, wx.title, wx.text)
        assertEquals(RecordType.INCOME, q!!.type)
        assertEquals(0.01, q.amount, 1e-9)
    }

    @Test
    fun toleratesSpannableAndMissingFields() {
        val d = """
              NotificationRecord(0x1 pkg=com.tencent.mm user=0)
                android.title=SpannableString (微信支付)
                android.bigText=SpannableString (你已收款，资金已存入零钱 ¥0.01)
        """.trimIndent()
        val items = NotificationDumpParser.parse(d)
        assertEquals(1, items.size)
        assertEquals("微信支付", items[0].title)
        assertTrue(items[0].text.contains("0.01"))
    }

    @Test
    fun emptyDumpYieldsNothing() {
        assertTrue(NotificationDumpParser.parse("").isEmpty())
        assertTrue(NotificationDumpParser.parse("Notification List:\n  (nothing)").isEmpty())
    }
}
