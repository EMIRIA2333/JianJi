package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天页转账回归测试（来自用户实机截图）。
 *
 * 两个真实 bug：
 * 1. 一笔 10.91 的转账被记成**两条**（一支出 + 一收入）—— 因为聊天页同时显示
 *    「转账 已被接收」和「转账 已收款」两个气泡；
 * 2. 商户名被抽成头像的无障碍文案「エミリヤチャン头像」，而不是聊天标题「陈冠霖」。
 */
class ChatTransferDedupTest {

    private fun lines(vararg s: String) = s.joinToString("\n")

    @Test
    fun oneTransferWithBothBubblesIsRecordedOnce() {
        val page = lines(
            "陈冠霖",
            "那么便宜",
            "我没用过",
            "中午12:44",
            "¥10.91",
            "转账",
            "已被接收",
            "¥10.91",
            "转账",
            "已收款",
            "我去还有11块",
            "其实是91"
        )
        val entries = ChatPaymentParser.parse("com.tencent.mm", page)
        assertEquals("同一笔转账只能记一条", 1, entries.size)
        assertEquals("收到的转账应记为收入", RecordType.INCOME, entries[0].type)
        assertEquals(10.91, entries[0].amount, 0.001)
        assertEquals("陈冠霖", entries[0].note)
    }

    @Test
    fun sentTransferStillExpense() {
        // 只有「已被接收」（自己发出）→ 仍然是支出
        val page = lines("陈冠霖", "¥10.91", "转账", "已被接收")
        val entries = ChatPaymentParser.parse("com.tencent.mm", page)
        assertEquals(1, entries.size)
        assertEquals(RecordType.EXPENSE, entries[0].type)
    }


    @Test
    fun avatarLabelIsNotUsedAsTitle() {
        val page = lines(
            "エミリヤチャン头像",
            "中午12:44",
            "¥10.91",
            "转账",
            "已收款"
        )
        val title = ChatPaymentParser.extractChatTitle(page.split("\n").map { it.trim() })
        assertFalse("头像的无障碍文案不是人名", title?.contains("头像") == true)
        val entries = ChatPaymentParser.parse("com.tencent.mm", page)
        assertEquals(1, entries.size)
        assertFalse(entries[0].note.contains("头像"))
    }

    @Test
    fun chatTitleIsPreferred() {
        val page = lines("陈冠霖", "中午12:44", "¥10.91", "转账", "已收款")
        assertEquals("陈冠霖", ChatPaymentParser.extractChatTitle(page.split("\n").map { it.trim() }))
        assertTrue(ChatPaymentParser.parse("com.tencent.mm", page).isNotEmpty())
    }
}
