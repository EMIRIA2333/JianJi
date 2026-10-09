package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 支出 / 收入方向一致性测试。
 *
 * 覆盖用户反馈的问题：**商品退款被记成了支出**；
 * 同时系统性检查各类收入（收款/工资/红包/报销/理财/退款）与支出（支付/消费/扣款/转账）方向是否正确。
 */
class DirectionConformanceTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val todayStart: Long = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()

    // ---------------- 退款必须记为收入 ----------------

    // ---------------- 微信红包 / 转账（发出去＝支出，收到＝收入） ----------------

    @Test
    fun sendingRedPacketIsExpense() {
        val samples = listOf(
            "你已成功发送红包¥100.00",
            "你发送了一个红包，金额100.00元",
            "微信红包 已发送 ¥88.00 给张三",
            "红包已被领取：¥100.00",
            "你发送的红包已被领完 ¥66.00"
        )
        samples.forEach { text ->
            val p = PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", text)
            assertEquals("发红包应为支出：$text", RecordType.EXPENSE, p!!.type)
        }
    }

    @Test
    fun receivingRedPacketIsIncome() {
        val samples = listOf(
            "你领取了张三的红包¥50.00",
            "收到红包：¥20.00 已存入零钱",
            "红包到账 ¥8.88",
            "你领取了群红包 ¥12.34"
        )
        samples.forEach { text ->
            val p = PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", text)
            assertEquals("收红包应为收入：$text", RecordType.INCOME, p!!.type)
        }
    }

    @Test
    fun redPacketDetailPageDirections() {
        // 发出去的红包详情页：已被领取 -> 支出，分类是人情（不能是收入类分类「红包」）
        val sent = """
            微信红包（单发）
            -100.00
            红包已被领取
            支付时间 2026年10月1日 21:00:00
            支付方式 零钱
        """.trimIndent()
        val s = ReceiptParser.parse(PaymentParser.PKG_WECHAT, sent)
        assertEquals(RecordType.EXPENSE, s!!.type)
        assertEquals("人情", s.categoryHint)

        // 收到的红包详情页：已存入零钱 -> 收入
        val received = """
            微信红包
            +50.00
            你领取了张三的红包
            已存入零钱
            到账时间 2026年10月1日 21:05:00
        """.trimIndent()
        val r = ReceiptParser.parse(PaymentParser.PKG_WECHAT, received)
        assertEquals(RecordType.INCOME, r!!.type)
        assertEquals("红包", r.categoryHint)
    }

    @Test
    fun unclaimedRedPacketInChatIsIgnored() {
        // 聊天里只有金额、还没领取的红包：不记账（避免把别人发的红包记成自己的支出）
        val chat = """
            微信红包
            恭喜发财，大吉大利
            100.00
        """.trimIndent()
        assertEquals(null, ReceiptParser.parse(PaymentParser.PKG_WECHAT, chat))
    }

    @Test
    fun unPaidRedPacketPageIsIgnored() {
        // 发红包的付款页（还没付）：不能记账
        val payPage = """
            微信红包
            100.00
            支付方式 零钱
            立即支付
        """.trimIndent()
        assertNull(ReceiptParser.parse(PaymentParser.PKG_WECHAT, payPage))
    }

    @Test
    fun pendingTransferIsNotRecorded() {
        // 转账待对方确认收款：钱还没出去，不能记
        assertNull(PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "你已发起转账¥200.00，待对方确认收款"))
        // 对方确认后才是支出
        val done = PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "你已成功转账¥200.00给张三")
        assertEquals(RecordType.EXPENSE, done!!.type)
    }

    @Test
    fun incomingTransferIsIncome() {
        val p = PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "张三转账给你¥200.00")
        assertEquals(RecordType.INCOME, p!!.type)
        val p2 = PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "张三向你转账200.00元，已收款")
        assertEquals(RecordType.INCOME, p2!!.type)
    }

    @Test
    fun redPacketRowInChangeDetailList() {
        // 微信「零钱明细」：发红包行 （-100.00），收红包行（+50.00），带「10月1日 21:00」这种日期头
        val text = """
            零钱明细
            2026年10月
            微信红包（单发） 10月1日 21:00
            -100.00
            微信红包（单发） 10月1日 21:05
            +50.00
            示例学院-二楼小吃9001A收款 10月1日 21:10
            -17.00
        """.trimIndent()
        val entries = BillListParser.parse(PaymentParser.PKG_WECHAT, text, todayStart + 3600_000)
        assertEquals(3, entries.size)
        assertEquals(RecordType.EXPENSE, entries.first { it.amount == 100.0 }.type)
        assertEquals(RecordType.INCOME, entries.first { it.amount == 50.0 }.type)
        assertEquals(RecordType.EXPENSE, entries.first { it.amount == 17.0 }.type)
    }

    // ---------------- 聊天页不记账（真机事故：转账给对方被记成收入） ----------------

    /** 复刻用户截图：同一个聊天里同时有「我发出去的」和「别人发给我的」转账 */
    private val chatWithTransfers = """
        对方正在输入...
        ¥0.50
        已被接收
        转账
        ¥0.50
        已收款
        转账
        行
        好像是有 bug
        按住 说话
        表情
        10月1日 22:57
    """.trimIndent()

    @Test
    fun chatPageIsNeverParsedAsReceipt() {
        assertNull(ReceiptParser.parse(PaymentParser.PKG_WECHAT, chatWithTransfers))
    }

    @Test
    fun chatPageIsNeverParsedAsBillList() {
        assertTrue(BillListParser.parse(PaymentParser.PKG_WECHAT, chatWithTransfers, todayStart).isEmpty())
    }

    // ---------------- 转账详情页：用收款方/付款方标签判断方向 ----------------

    @Test
    fun transferDetailOutgoingIsExpense() {
        // 我转给对方：详情页列的是「收款方」
        val text = """
            转账详情
            转账金额
            ¥0.50
            转账时间 2026-10-01 22:57
            收款方 我等会再改一下
            转账说明 
            当前状态 已收款
            支付方式 零钱
        """.trimIndent()
        val p = ReceiptParser.parse(PaymentParser.PKG_WECHAT, text)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(0.5, p.amount, 1e-9)
    }

    @Test
    fun transferDetailIncomingIsIncome() {
        // 对方转给我：详情页列的是「付款方」
        val text = """
            转账详情
            转账金额
            ¥0.50
            转账时间 2026-10-01 22:58
            付款方 我等会再改一下
            当前状态 已收款
        """.trimIndent()
        val p = ReceiptParser.parse(PaymentParser.PKG_WECHAT, text)
        assertEquals(RecordType.INCOME, p!!.type)
    }

    // ---------------- 备注提取（「自动提取备注」开关） ----------------

    @Test
    fun extractRemarkFromReceiptPage() {
        assertEquals("直接付款", ReceiptParser.extractRemark("商品说明 直接付款\n支付时间 2026年10月1日"))
        assertEquals("给妈妈的生日礼物", ReceiptParser.extractRemark("转账说明 给妈妈的生日礼物"))
        assertEquals("秋季外套", ReceiptParser.extractRemark("商品：秋季外套\n金额 ¥199.00"))
    }

    @Test
    fun remarkIgnoresOrderNumbersAndButtons() {
        assertEquals("", ReceiptParser.extractRemark("商品 商户单号XP0000000000000000000000000001"))
        assertEquals("", ReceiptParser.extractRemark("备注 查看详情"))
        assertEquals("", ReceiptParser.extractRemark("支付成功 ¥17.00"))
    }

    @Test
    fun ambiguousTransferDetailIsIgnored() {
        // 两个标签都出现（或都不出现）时无法判断谁是我 -> 不记账
        val both = """
            转账详情
            转账金额 ¥0.50
            转账时间 2026-10-01 22:57
            付款方 张三
            收款方 李四
        """.trimIndent()
        assertNull(ReceiptParser.parse(PaymentParser.PKG_WECHAT, both))
        val neither = """
            转账详情
            转账金额 ¥0.50
            转账时间 2026-10-01 22:57
            当前状态 已收款
        """.trimIndent()
        assertNull(ReceiptParser.parse(PaymentParser.PKG_WECHAT, neither))
    }

    // ---------------- 商品退款（真机截图：微信账单详情页 · 拼多多全额退款） ----------------

    /** 复刻用户截图的账单详情页 */
    private val wechatRefundDetail = """
        全部账单
        拼多多
        -3.71
        退款记录
        已退款 ¥3.71 >
        2026年10月1日 23:12:53
        当前状态 已全额退款
        支付时间 2026年10月1日 23:12:37
        商品 商户单号XP0000000000000000000000000001
        收单机构 财付通支付科技有限公司
        支付方式 零钱
        交易单号 4500000000202601010000000001
        商户单号 XP0000000000000000000000000001
        账单服务
        对订单有疑惑 在此商户的交易 申请电子凭证
        联系商家
    """.trimIndent()

    @Test
    fun wechatRefundDetailPageIsIncome() {
        val p = ReceiptParser.parse(PaymentParser.PKG_WECHAT, wechatRefundDetail)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(3.71, p.amount, 1e-9)
        assertEquals("退款", p.categoryHint)
        assertEquals("拼多多", p.merchant)
        assertEquals("零钱", p.payMethod)
        // 支付时间要取到真实交易时间
        assertNotNull(p.time)
    }

    @Test
    fun refundNotificationToLingQianTongIsIncome() {
        // 「退款到账至零钱通」不能被资金内部转移规则拦掉
        val p = PaymentParser.parse(
            PaymentParser.PKG_WECHAT, "微信支付",
            "退款到账通知：已退款¥3.71，已原路退回至零钱通"
        )
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(3.71, p.amount, 1e-9)
    }

    @Test
    fun pureTransferIntoLingQianTongIsStillIgnored() {
        // 没有退款语境时，零钱通内部转移仍然不记账
        assertNull(
            PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "零钱通转入成功 ¥100.00")
        )
    }

    @Test
    fun partialRefundPurchaseStaysExpense() {
        // 部分退款：主体仍是那笔支出（退款金额会在零钱明细里单独记一笔收入）
        val text = """
            全部账单
            拼多多
            -38.60
            退款记录
            已退款 ¥3.71 >
            2026年10月1日 23:12:53
            当前状态 交易成功
            支付时间 2026年10月1日 23:12:37
            支付方式 零钱
            交易单号 4500000000202601010000000001
        """.trimIndent()
        val p = ReceiptParser.parse(PaymentParser.PKG_WECHAT, text)
        assertEquals(RecordType.EXPENSE, p!!.type)
    }

    @Test
    fun billListPageIsNotParsedAsDetail() {
        // 列表页应交给 BillListParser：详情解析器不能把其中一行当成单笔
        val list = """
            账单
            全部账单
            今天
            示例学院-二楼小吃9001A收款
            交易成功
            -17.00
            拼多多
            已全额退款
            -3.71
            永辉超市
            交易成功
            -56.00
        """.trimIndent()
        assertNull(ReceiptParser.parse(PaymentParser.PKG_WECHAT, list))
        assertTrue(BillListParser.parse(PaymentParser.PKG_WECHAT, list, todayStart).size >= 2)
    }

    // ---------------- 微信转账成功页（真机截图） ----------------

    @Test
    fun wechatTransferSuccessPageIsExpense() {
        // 「支付成功 / 待🐱确认收款 / ¥0.01 / 完成」——钱已经从我这扣了，应记为支出
        val page = """
            支付成功
            待🐱确认收款
            ¥0.01
            完成
        """.trimIndent()
        val p = ReceiptParser.parse(PaymentParser.PKG_WECHAT, page)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(0.01, p.amount, 1e-9)
        assertEquals("🐱", p.merchant)
    }

    @Test
    fun wechatTransferPageWithGenericCounterparty() {
        // 「待对方确认收款」这种情况用平台名兜底，别把「对方」当商户
        val page = """
            支付成功
            待对方确认收款
            ¥12.34
            完成
        """.trimIndent()
        val p = ReceiptParser.parse(PaymentParser.PKG_WECHAT, page)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals("微信", p.merchant)
    }

    // ---------------- 三张真机页面（转账收款 / 转账付款 / 红包收款） ----------------

    /** 图一：转账收款成功页 —— 你已收款，资金已存入零钱 */
    private val transferReceivedPage = """
        ✓
        你已收款，资金已存入零钱
        ¥0.01
        零钱余额
        转账时间 2026年10月02日 23:44:55
        收款时间 2026年10月02日 23:45:00
        账单详情
    """.trimIndent()

    /** 图二：转账付款成功页 —— 支付成功 / 待示例昵称确认收款 */
    private val transferSentPage = """
        支付成功
        待示例昵称确认收款
        ¥0.01
        完成
    """.trimIndent()

    /** 图三：红包收款页 —— 「示例昵称的红包」+「已存入零钱，可直接转账」（页面里没有“微信红包”四个字） */
    private val redPacketReceivedPage = """
        封面由个人制作
        示例昵称的红包
        国庆假期快乐
        0.01 元
        已存入零钱，可直接转账 >
        回复表情到聊天
        了解红包封面
    """.trimIndent()

    @Test
    fun threeRealPagesFromSamePersonDoNotCollide() {
        // 用户真机场景：同一个人 41 秒内产生的三笔（红包收款 → 转账付款 → 转账收款）
        // 必须各记一笔，且去重键不能互相撞车
        val received = ReceiptParser.parse(PaymentParser.PKG_WECHAT, transferReceivedPage)!!
        val sent = ReceiptParser.parse(PaymentParser.PKG_WECHAT, transferSentPage)!!
        val packet = ReceiptParser.parse(PaymentParser.PKG_WECHAT, redPacketReceivedPage)!!

        assertEquals(RecordType.INCOME, received.type)
        assertEquals(RecordType.EXPENSE, sent.type)
        assertEquals(RecordType.INCOME, packet.type)
        assertEquals("微信转账", received.merchant)
        assertEquals("示例昵称", sent.merchant)
        assertEquals("示例昵称", packet.merchant)

        val keys = setOf(
            "${received.type}|${received.merchant}",
            "${sent.type}|${sent.merchant}",
            "${packet.type}|${packet.merchant}"
        )
        assertEquals("三笔的去重键必须互不相同", 3, keys.size)
    }

    @Test
    fun transferReceivedPageIsIncome() {
        val p = ReceiptParser.parse(PaymentParser.PKG_WECHAT, transferReceivedPage)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(0.01, p.amount, 1e-9)
        assertEquals("微信转账", p.merchant)
        assertNotNull(p.time)
    }

    @Test
    fun transferSentPageIsExpense() {
        val p = ReceiptParser.parse(PaymentParser.PKG_WECHAT, transferSentPage)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(0.01, p.amount, 1e-9)
        assertEquals("示例昵称", p.merchant)
    }

    @Test
    fun redPacketReceivedPageIsIncome() {
        val p = ReceiptParser.parse(PaymentParser.PKG_WECHAT, redPacketReceivedPage)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(0.01, p.amount, 1e-9)
        assertEquals("示例昵称", p.merchant)
        assertEquals("红包", p.categoryHint)
    }

    @Test
    fun alipayRefundReceiptWithNegativeAmountIsIncome() {
        // 真机场景：账单页金额显示为 -3.31，但状态是「退款成功」、账单分类是「退款」
        val text = """
            账单详情
            全部账单
            下单限时享9.5折
            -3.31
            退款成功
            支付时间 2026-10-01 21:38:12
            付款方式 余额
            账单分类 退款
            原路退回
        """.trimIndent()
        val p = ReceiptParser.parse(PaymentParser.PKG_ALIPAY, text)
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(3.31, p.amount, 1e-9)
        assertEquals("退款", p.categoryHint)
    }

    @Test
    fun refundNotificationIsIncome() {
        val p = PaymentParser.parse(
            PaymentParser.PKG_ALIPAY, "支付宝",
            "退款成功：¥39.90 已原路退回至余额"
        )
        assertEquals(RecordType.INCOME, p!!.type)
        assertEquals(39.90, p.amount, 1e-9)
    }

    @Test
    fun refundRowInBillListWithNegativeSignIsIncome() {
        val text = """
            账单
            今天
            退款-示例小吃
            -23.90
            一楼大餐6006C
            -0.90
        """.trimIndent()
        val entries = BillListParser.parse(PaymentParser.PKG_ALIPAY, text)
        assertEquals(2, entries.size)
        assertEquals(RecordType.INCOME, entries.first { it.amount == 23.9 }.type)
        // 普通消费仍是支出
        assertEquals(RecordType.EXPENSE, entries.first { it.amount == 0.9 }.type)
    }

    @Test
    fun merchantNameContainingShouKuanIsNotIncome() {
        // 「…9001A收款」是校园卡商户名，不能当成收入
        val text = """
            账单
            今天
            示例学院-二楼小吃9001A收款
            -17.00
            一楼大餐6006C
            -0.90
        """.trimIndent()
        val entries = BillListParser.parse(PaymentParser.PKG_WECHAT, text)
        assertEquals(2, entries.size)
        entries.forEach { assertEquals(RecordType.EXPENSE, it.type) }
    }

    @Test
    fun moneyInRowsAreIncome() {
        val text = """
            账单
            今天
            某某公司代发工资
            +5600.00
            收到转账
            +100.00
        """.trimIndent()
        val entries = BillListParser.parse(PaymentParser.PKG_ALIPAY, text)
        assertEquals(2, entries.size)
        entries.forEach { assertEquals(RecordType.INCOME, it.type) }
    }

    // ---------------- 分类与方向必须自洽 ----------------

    @Test
    fun incomeCategoryForcesIncomeDirection() {
        // 引擎兜底：分类是收入类分类时，方向一定被纠正为收入
        assertEquals(RecordType.INCOME, Categories.normalizeType(RecordType.EXPENSE, "退款"))
        assertEquals(RecordType.INCOME, Categories.normalizeType(RecordType.EXPENSE, "收款"))
        assertEquals(RecordType.INCOME, Categories.normalizeType(RecordType.EXPENSE, "工资"))
        assertEquals(RecordType.INCOME, Categories.normalizeType(RecordType.EXPENSE, "红包"))
        // 支出分类不受影响
        assertEquals(RecordType.EXPENSE, Categories.normalizeType(RecordType.EXPENSE, "餐饮"))
        assertEquals(RecordType.EXPENSE, Categories.normalizeType(RecordType.EXPENSE, "其他"))
        // 收入方向保持
        assertEquals(RecordType.INCOME, Categories.normalizeType(RecordType.INCOME, "餐饮"))
    }

    // ---------------- 各类收入样本 ----------------

    @Test
    fun incomeSamples() {
        val samples = listOf(
            "收款成功：¥88.00" to 88.0,
            "支付宝到账100元" to 100.0,
            "收到转账100.00元" to 100.0,
            "你收到了一个红包，金额¥0.66" to 0.66,
            "代发工资入账人民币5,600.00元" to 5600.0,
            "差旅报销到账 ¥320.00" to 320.0
        )
        samples.forEach { (text, amount) ->
            val p = PaymentParser.parse(PaymentParser.PKG_ALIPAY, "通知", text)
            assertEquals("方向应为收入：$text", RecordType.INCOME, p!!.type)
            assertEquals(amount, p.amount, 1e-9)
        }
    }

    // ---------------- 各类支出样本 ----------------

    @Test
    fun expenseSamples() {
        val samples = listOf(
            "支付成功¥26.00" to 26.0,
            "您已成功付款25.90元给盒马鲜生" to 25.90,
            "已向张三转账¥100.00" to 100.0,
            "你在智能自助服务有一笔0.08元的免密/自动扣款支付" to 0.08,
            "你已成功向【老王烧烤】支付¥26.00" to 26.0
        )
        samples.forEach { (text, amount) ->
            val p = PaymentParser.parse(PaymentParser.PKG_WECHAT, "通知", text)
            assertEquals("方向应为支出：$text", RecordType.EXPENSE, p!!.type)
            assertEquals(amount, p.amount, 1e-9)
        }
    }

    @Test
    fun refundStillIncomeWhenPageAlsoMentionsRefundMenu() {
        // 微信凭证页有「可在支持的商户扫码退款」这种菜单文案，不能被当成收入
        val text = """
            全部账单
            示例学院
            -17.00
            当前状态 支付成功
            支付时间 2026年10月1日 18:04:53
            商品 示例学院-二楼小吃9001A收款
            商户全称 示例学院
            商户单号 可在支持的商户扫码退款
        """.trimIndent()
        val p = ReceiptParser.parse(PaymentParser.PKG_WECHAT, text)
        assertEquals(RecordType.EXPENSE, p!!.type)
        assertEquals(17.0, p.amount, 1e-9)
    }
}
