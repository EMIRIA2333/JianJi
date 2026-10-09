package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 支付解析器单元测试。样本取自微信/支付宝常见通知与页面文本。
 */
class PaymentParserTest {

    // ---------- 支出 ----------

    @Test
    fun wechatExpenseReceipt() {
        val p = PaymentParser.parse(
            PaymentParser.PKG_WECHAT, "微信支付",
            "微信支付凭证\n你已成功向【老王烧烤】支付¥26.00"
        )
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(26.00, p.amount, 1e-9)
        assertEquals("老王烧烤", p.merchant)
        assertEquals(1.0, p.confidence, 1e-9)
        assertEquals("餐饮", Categories.guess(p.merchant, false))
    }

    @Test
    fun wechatExpenseMerchantSuffix() {
        val p = PaymentParser.parse(
            PaymentParser.PKG_WECHAT, "微信支付",
            "你已成功向瑞幸咖啡（国贸店）支付¥19.9"
        )
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(19.9, p.amount, 1e-9)
        assertEquals("瑞幸咖啡（国贸店）", p.merchant)
    }

    @Test
    fun alipayExpenseToMerchant() {
        val p = PaymentParser.parse(
            PaymentParser.PKG_ALIPAY, "支付宝通知",
            "您已成功付款25.90元给盒马鲜生"
        )
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(25.90, p.amount, 1e-9)
        assertEquals("盒马鲜生", p.merchant)
        assertEquals("购物", Categories.guess(p.merchant, false))
    }

    @Test
    fun alipayExpenseSuccessPage() {
        val p = PaymentParser.parse(
            PaymentParser.PKG_ALIPAY, "",
            "支付成功\n¥35.50\n付款给：顺丰速运\n交易时间 2025-01-01 12:00:00", strict = true
        )
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(35.50, p.amount, 1e-9)
        assertEquals("顺丰速运", p.merchant)
    }

    @Test
    fun expenseAtMerchant() {
        val p = PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "在美团消费42.5元")
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(42.5, p.amount, 1e-9)
        assertEquals("美团", p.merchant)
    }

    @Test
    fun transferToFriend() {
        val p = PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "已向张三转账¥100.00")
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(100.00, p.amount, 1e-9)
        assertEquals("张三", p.merchant)
    }

    @Test
    fun commaAmount() {
        val p = PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "你已成功向【Apple Store】支付¥1,234.56")
        assertNotNull(p)
        assertEquals(1234.56, p!!.amount, 1e-9)
    }

    @Test
    fun merchantFallbackToPlatform() {
        val p = PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "支付成功¥12.30")
        assertNotNull(p)
        assertEquals("微信", p!!.merchant)
        assertEquals(RecordType.EXPENSE, p.type)
    }

    // ---------- 收入 ----------

    @Test
    fun incomeTransfer() {
        val p = PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信收款助手", "收到转账100.00元")
        assertNotNull(p)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(100.00, p.amount, 1e-9)
        assertEquals("收款", Categories.guess(p.merchant, true, "收到转账100.00元"))
    }

    @Test
    fun incomeAlipayArrive() {
        val p = PaymentParser.parse(PaymentParser.PKG_ALIPAY, "支付宝", "支付宝到账100元")
        assertNotNull(p)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(100.0, p.amount, 1e-9)
    }

    @Test
    fun incomeRedPacket() {
        val p = PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信红包", "你收到了一个红包，金额¥0.66")
        assertNotNull(p)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(0.66, p.amount, 1e-9)
        assertEquals("红包", Categories.guess(p.merchant, true, "收到了一个红包"))
    }

    @Test
    fun incomeFromFriend() {
        val p = PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "来自李四的转账¥520.00")
        assertNotNull(p)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals("李四", p.merchant)
    }

    @Test
    fun refund() {
        val p = PaymentParser.parse(PaymentParser.PKG_ALIPAY, "支付宝通知", "退款成功：¥39.90已原路退回")
        assertNotNull(p)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(39.90, p.amount, 1e-9)
        assertEquals("退款", Categories.guess(p.merchant, true, "退款成功原路退回"))
    }

    // ---------- 误报防护 ----------

    @Test
    fun ignoreFailedPayment() {
        assertNull(PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "支付失败，银行卡余额不足¥100.00"))
    }

    @Test
    fun ignorePromotion() {
        assertNull(PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "微信支付有优惠，点击领取"))
    }

    @Test
    fun ignoreMonthlyBill() {
        assertNull(PaymentParser.parse(PaymentParser.PKG_ALIPAY, "支付宝", "您的月账单已出，共消费¥1,200.00，请查看"))
    }

    @Test
    fun ignoreWithdraw() {
        assertNull(PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "零钱提现申请已提交，提现¥500.00"))
    }

    @Test
    fun ignorePendingPayment() {
        assertNull(PaymentParser.parse(PaymentParser.PKG_ALIPAY, "支付宝", "你有一笔订单待支付，金额¥88.00"))
    }

    @Test
    fun ignoreCoupon() {
        assertNull(PaymentParser.parse(PaymentParser.PKG_ALIPAY, "支付宝", "你有一张消费券待领取，满100减20"))
    }

    @Test
    fun ignoreNoAmount() {
        assertNull(PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "你已成功支付"))
    }

    @Test
    fun ignoreZeroAmount() {
        assertNull(PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "支付成功¥0.00"))
    }

    @Test
    fun ignoreAbsurdAmount() {
        assertNull(PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "支付成功¥99999999.00"))
    }

    @Test
    fun ignoreAmbiguousBothDirections() {
        // 同一段文本既像收入又像支出：宁可不记
        assertNull(
            PaymentParser.parse(
                PaymentParser.PKG_WECHAT, "微信支付",
                "你已成功向【A店】支付¥10.00，随后收款成功¥10.00"
            )
        )
    }

    // ---------- 屏幕内容（strict 模式） ----------

    @Test
    fun screenStrictRequiresStrongKeyword() {
        // 页面上只有金额、没有「支付成功」类强关键词：不记
        assertNull(PaymentParser.parse(PaymentParser.PKG_WECHAT, "", "¥88.00\n收银台\n微信支付", strict = true))
    }

    @Test
    fun screenStrictSuccessPage() {
        val p = PaymentParser.parse(
            PaymentParser.PKG_WECHAT, "",
            "支付成功\n¥88.00\n付款方：微信用户\n商户：某某便利店", strict = true
        )
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(88.00, p.amount, 1e-9)
        assertEquals("某某便利店", p.merchant)
        assertEquals("购物", Categories.guess(p.merchant, false))
    }

    @Test
    fun screenStrictPendingRejected() {
        assertNull(PaymentParser.parse(PaymentParser.PKG_WECHAT, "", "待支付\n¥88.00\n立即支付", strict = true))
    }

    @Test
    fun nonStrictStillWorks() {
        val p = PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "支付成功¥88.00", strict = false)
        assertNotNull(p)
    }

    // ---------- 分类 ----------

    @Test
    fun categoryTraffic() {
        assertEquals("交通", Categories.guess("中国石化加油站", false))
        assertEquals("交通", Categories.guess("滴滴出行", false))
    }

    @Test
    fun categoryFood() {
        assertEquals("餐饮", Categories.guess("沙县小吃", false))
        assertEquals("餐饮", Categories.guess("麦当劳", false))
    }

    @Test
    fun categoryOther() {
        assertEquals("其他", Categories.guess("某某工作室", false))
    }

    // ---------- v1.1 新增：平台 / 账单页 / 退款 ----------

    @Test
    fun platformNameMapping() {
        assertEquals("美团", PaymentParser.platformName("com.sankuai.meituan"))
        assertEquals("京东", PaymentParser.platformName("com.jingdong.app.mall"))
        assertEquals("微信", PaymentParser.platformName(PaymentParser.PKG_WECHAT))
        assertEquals("支付", PaymentParser.platformName("com.unknown.app"))
    }

    @Test
    fun thirdPartyPlatformExpense() {
        val p = PaymentParser.parse(
            "com.sankuai.meituan", "美团", "支付成功 ¥45.80\n美团外卖订单"
        )
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(45.80, p.amount, 1e-9)
        assertEquals("餐饮", Categories.guess(p.merchant, false, "美团外卖订单", "com.sankuai.meituan"))
    }

    @Test
    fun billDetailTransactionSuccessStrict() {
        val p = PaymentParser.parse(
            PaymentParser.PKG_ALIPAY, "",
            "交易成功\n付款金额：¥35.50\n对方：顺丰速运", strict = true
        )
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(35.50, p.amount, 1e-9)
    }

    @Test
    fun refundWithMerchant() {
        val p = PaymentParser.parse(
            PaymentParser.PKG_ALIPAY, "支付宝通知",
            "退款给：盒马鲜生\n退款金额：¥29.90 已原路退回"
        )
        assertNotNull(p)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(29.90, p.amount, 1e-9)
        assertEquals("盒马鲜生", p.merchant)
        assertEquals("退款", Categories.guess(p.merchant, true, "退款 原路退回"))
    }

    @Test
    fun goodsRefundIncome() {
        val p = PaymentParser.parse(
            "com.taobao.taobao", "淘宝", "你的退货退款已成功，退款金额¥128.00将原路退回"
        )
        assertNotNull(p)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(128.00, p.amount, 1e-9)
        // 退款统一归类「退款」，而不是按平台归类「购物」
        assertEquals("退款", Categories.guess(p.merchant, true, "退款", "com.taobao.taobao"))
    }

    @Test
    fun platformPromoIgnored() {
        assertNull(PaymentParser.parse("com.xunmeng.pinduoduo", "拼多多", "限时秒杀！仅¥9.9，快来砍价"))
    }

    @Test
    fun categoryFallbackByPlatform() {
        // 商户无法识别时按平台兜底：美团->餐饮
        assertEquals("餐饮", Categories.guess("某某店", false, "", "com.sankuai.meituan"))
        assertEquals("交通", Categories.guess("某某公司", false, "", "com.sdu.didi.psnger"))
        assertEquals("购物", Categories.guess("某某店铺", false, "", "com.jingdong.app.mall"))
    }

    // ---------- v1.3.1 新增：免密支付 / 自动扣款 ----------

    @Test
    fun alipayAutoDeductNotification() {
        // 真机样本：没有「支付成功」字样，且带「积分」营销语
        val p = PaymentParser.parse(
            PaymentParser.PKG_ALIPAY, "交易提醒",
            "你在智能自助服务有一笔0.08元的免密/自动扣款支付，点击领取4个支付宝积分。"
        )
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(0.08, p.amount, 1e-9)
        assertEquals("智能自助服务", p.merchant)
    }

    @Test
    fun subscribeRenewalNotification() {
        val p = PaymentParser.parse(
            PaymentParser.PKG_ALIPAY, "支付宝",
            "你已开通连续包月，本次自动扣款¥15.00"
        )
        assertNotNull(p)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(15.0, p.amount, 1e-9)
    }

    @Test
    fun membershipRenewalGuidesIgnored() {
        // 只是引导开通/管理，没有扣款金额与强证据 -> 不记
        assertNull(
            PaymentParser.parse(PaymentParser.PKG_ALIPAY, "支付宝", "开通免密支付，支付更快捷，点击开通")
        )
        assertNull(
            PaymentParser.parse(PaymentParser.PKG_ALIPAY, "支付宝", "自动续费管理：可随时关闭")
        )
    }

    @Test
    fun mianmiDetectedForBothChannels() {
        // 两条通路（无障碍通知 / 通知读取）共用同一判断：
        // 图三那条免密支付必须被认成「钱已扣完」，直接记账而不再等确认
        val text = "交易提醒\n你在智能自助服务有一笔0.08元的免密/自动扣款支付，点击领取4个支付宝积分。"
        assertTrue(PaymentParser.isMianmi(text))
        assertTrue(PaymentParser.isMianmi("你已开通连续包月，本次自动扣款¥15.00"))
        assertTrue(PaymentParser.isMianmi("银行卡代扣 成功扣款 88.00"))
        // 普通消费不该被当成免密（避免所有通知都跳过确认）
        assertFalse(
            PaymentParser.isMianmi(
                "支付成功\n" +
                    PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "你已成功向老王烧烤支付¥26.00")!!.merchant
            )
        )
    }

    @Test
    fun strongEvidenceOverridesMarketingWords() {
        // 页面/通知里同时出现「积分/免息券」，但有强证据 -> 仍要记账
        val p = PaymentParser.parse(
            PaymentParser.PKG_ALIPAY, "",
            "支付成功 ¥26.00\n立即领取4积分\n你有3张免息券待领取", strict = true
        )
        assertNotNull(p)
        assertEquals(26.0, p!!.amount, 1e-9)
    }

    @Test
    fun hardDenyStillBlocks() {
        assertNull(PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "零钱提现¥500.00 提现成功"))
        assertNull(PaymentParser.parse(PaymentParser.PKG_ALIPAY, "支付宝", "待支付 ¥88.00 立即支付"))
    }

    // ---------- 基础提取 ----------

    @Test
    fun extractAmountYuanSuffix() {
        assertEquals(42.5, PaymentParser.extractAmount("消费42.5元")!!, 1e-9)
        assertEquals(100.0, PaymentParser.extractAmount("人民币 100")!!, 1e-9)
        assertEquals(7.77, PaymentParser.extractAmount("￥7.77")!!, 1e-9)
    }
}
