package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天页转账 / 红包测试。
 *
 * 关键点：聊天里可能同时有「我发出去的」和「别人发给我的」，
 * 必须**逐笔按气泡状态**判定方向，不能整页当成一笔（曾把转账记成收入）。
 */
class ChatPaymentParserTest {

    /** 用户真机截图里的聊天：一笔我发出去的 + 一笔别人发给我的 */
    private val mixedChat = """
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
    """.trimIndent()

    @Test
    fun parsesBothDirectionsFromOneChat() {
        val entries = ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, mixedChat)
        assertEquals(2, entries.size)
        val sent = entries.first { it.type == RecordType.EXPENSE }
        val received = entries.first { it.type == RecordType.INCOME }
        assertEquals(0.5, sent.amount, 1e-9)
        assertEquals(0.5, received.amount, 1e-9)
    }

    @Test
    fun categoryFollowsTheBubbleNotTheWholePage() {
        // 同一个聊天里既有红包也有转账：分类必须按各自气泡来，
        // 不能因为整页出现过「红包」就把收到的转账也标成红包（曾导致看起来像重复记账）
        val entries = ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, mixedChat)
        val sent = entries.first { it.type == RecordType.EXPENSE }
        val received = entries.first { it.type == RecordType.INCOME }
        assertEquals("收款", received.category)   // 已收款 = 别人转给我
        assertEquals("人情", sent.category)       // 已被接收 = 我转出去
    }

    @Test
    fun payMethodIsNeverRedPacketOrTransfer() {
        // 红包 / 转账是业务类型，不能当支付方式
        assertEquals("", ReceiptParser.extractPayMethod("微信红包 恭喜发财"))
        assertEquals("", ReceiptParser.extractPayMethod("转账 已收款"))
        assertEquals("零钱", ReceiptParser.extractPayMethod("微信红包 支付方式 零钱"))
    }

    @Test
    fun redPacketEntriesUsePacketCategories() {
        val sent = ChatPaymentParser.parse(
            PaymentParser.PKG_WECHAT,
            "老王\n微信红包\n¥8.88\n已被领取\n按住 说话"
        )
        assertEquals("人情", sent[0].category)
        val received = ChatPaymentParser.parse(
            PaymentParser.PKG_WECHAT,
            "老王\n¥8.88\n你领取了\n微信红包\n按住 说话"
        )
        assertEquals("红包", received[0].category)
    }

    @Test
    fun namedRedPacketSystemMessages() {
        // 带名字的系统消息：发红包方看到「张三领取了你的红包」，收红包方看到「你领取了张三的红包」
        val sent = """
            老王
            ¥0.01
            张三领取了你的红包
            按住 说话
        """.trimIndent()
        val s = ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, sent)
        assertEquals(1, s.size)
        assertEquals(RecordType.EXPENSE, s[0].type)

        val received = """
            老王
            ¥0.01
            你领取了张三的红包
            按住 说话
        """.trimIndent()
        val r = ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, received)
        assertEquals(1, r.size)
        assertEquals(RecordType.INCOME, r[0].type)
    }

    @Test
    fun amountAndStatusOnSameLine() {
        // a11y 有时把金额和状态放在同一节点：「¥0.01 已被接收」
        val chat = """
            老王
            ¥0.01 已被接收
            转账
            按住 说话
        """.trimIndent()
        val e = ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, chat)
        assertEquals(1, e.size)
        assertEquals(RecordType.EXPENSE, e[0].type)
        assertEquals(0.01, e[0].amount, 1e-9)
    }

    @Test
    fun worksWithoutChatInputBarHint() {
        // 回归：曾经要求页面必须出现「按住 说话」等聊天特征，新版微信不一定有，
        // 结果整个聊天解析器直接返回空 -> 转账红包全不记
        val chat = """
            老王
            ¥0.01
            已被接收
            转账
        """.trimIndent()
        val e = ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, chat)
        assertEquals(1, e.size)
        assertEquals(RecordType.EXPENSE, e[0].type)
    }

    @Test
    fun twoIdenticalBubblesYieldTwoEntries() {
        // 页面上真的有两个同金额气泡时，必须分别记账（按条数同步依赖这一点）
        val chat = """
            老王
            ¥0.01
            已被接收
            转账
            ¥0.01
            已被接收
            转账
        """.trimIndent()
        val e = ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, chat)
        assertEquals(2, e.size)
    }

    @Test
    fun receiptDetailPageWithGuardLabelsIsIgnored() {
        // 同时含多个账单详情字段的页面交给 ReceiptParser，避免重复记账
        val detail = """
            账单详情
            拼多多
            -3.71
            已被接收
            当前状态 已全额退款
            交易单号 4500000000202601010000000001
            商户单号 XP0000000000000001
        """.trimIndent()
        assertTrue(ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, detail).isEmpty())
    }

    @Test
    fun findsNewBubbleAtTheEndOfALongChat() {
        // 聊天很长时，最新的气泡在**最下面**。这里构造 3000+ 字的聊天，
        // 确认末尾的收入气泡仍能被解析出来（页面截断/去重键截断都曾导致漏记）
        val filler = (1..200).joinToString("\n") { "这是第 $it 条普通聊天内容，和钱没有关系" }
        val chat = filler + "\n老王\n¥0.01\n已收款\n转账\n"
        assertTrue(chat.length > 2000)
        val e = ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, chat)
        assertEquals(1, e.size)
        assertEquals(RecordType.INCOME, e[0].type)
        assertEquals(0.01, e[0].amount, 1e-9)
    }

    @Test
    fun incomeTransferAndPacketMarkers() {
        // 收入侧的常见文案都要认：已收款 / 你已收款 / 已存入零钱 / 你领取了 / 红包到账 / 已收钱
        listOf("已收款", "你已收款", "已存入零钱", "你领取了", "红包到账", "已收钱").forEach { marker ->
            val chat = "老王\n¥0.01\n$marker\n转账"
            val e = ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, chat)
            assertEquals("收入文案应被识别：$marker", 1, e.size)
            assertEquals("$marker 应为收入", RecordType.INCOME, e[0].type)
        }
    }

    @Test
    fun usesChatTitleAsMerchant() {
        val chat = """
            老王
            ¥50.00
            已被接收
            转账
            按住 说话
        """.trimIndent()
        val entries = ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, chat)
        assertEquals(1, entries.size)
        assertEquals("老王", entries[0].note)
        assertEquals(RecordType.EXPENSE, entries[0].type)
    }

    @Test
    fun redPacketSentIsExpense() {
        val chat = """
            老王
            微信红包
            ¥8.88
            已被领取
            恭喜发财，大吉大利
            按住 说话
        """.trimIndent()
        val e = ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, chat)
        assertEquals(1, e.size)
        assertEquals(RecordType.EXPENSE, e[0].type)
        assertEquals(8.88, e[0].amount, 1e-9)
    }

    @Test
    fun redPacketReceivedIsIncome() {
        val chat = """
            老王
            ¥8.88
            你领取了
            微信红包
            已存入零钱
            按住 说话
        """.trimIndent()
        val e = ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, chat)
        assertEquals(1, e.size)
        assertEquals(RecordType.INCOME, e[0].type)
    }

    @Test
    fun pendingOrRefundedTransferSkipped() {
        val pending = """
            老王
            ¥200.00
            待对方确认收款
            转账
            按住 说话
        """.trimIndent()
        assertTrue(ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, pending).isEmpty())

        val refunded = """
            老王
            ¥200.00
            已退还
            转账
            按住 说话
        """.trimIndent()
        assertTrue(ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, refunded).isEmpty())
    }

    @Test
    fun plainChatTextIsNotRecorded() {
        // 普通聊天：没有收付气泡状态，不能记账
        val chat = """
            老王
            今天花了100元
            在吗
            按住 说话
        """.trimIndent()
        assertTrue(ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, chat).isEmpty())
    }

    @Test
    fun nonChatPageIsIgnored() {
        // 账单详情页没有聊天特征，交给 ReceiptParser，避免重复记账
        val detail = """
            账单详情
            拼多多
            -3.71
            当前状态 已全额退款
            支付时间 2026年10月1日 23:12:37
        """.trimIndent()
        assertTrue(ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, detail).isEmpty())
    }

    @Test
    fun amountFarFromBubbleIsRejected() {
        // 状态词附近没有金额（超过 4 行）时不记账，避免误抓聊天里的其它数字
        val chat = """
            老王
            ¥100.00
            随便聊聊
            再说点别的
            还有别的
            继续
            已被接收
        """.trimIndent()
        assertTrue(ChatPaymentParser.parse(PaymentParser.PKG_WECHAT, chat).isEmpty())
    }
}
