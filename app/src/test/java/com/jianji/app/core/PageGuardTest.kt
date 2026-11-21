package com.jianji.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面级硬拦截：这几页永远不该记账。
 *
 * 全部来自真机反馈：
 *  - 微信主界面放着不动也记账（聊天列表页）；
 *  - 支付宝还没输金额的付款页就记账。
 */
class PageGuardTest {

    /** 日志里微信主界面的真实文本（82 字，取自 jianji-log 的「页面已扫描 com.tencent.mm」） */
    private val wechatHome =
        "浮窗 退出浮窗 打开侧栏 搜索 更多功能 エミリアチャン 在线 微信(3) 主页 主页 " +
            "通讯录 通讯录 发现 发现 我 我 正在加载… 正在加载… 3 A B 3"

    @Test
    fun wechatHomeScreenIsNotABill() {
        assertTrue(PageGuard.isChatListPage(wechatHome))
    }

    @Test
    fun chatDetailPageIsNotBlocked() {
        // 聊天详情页有输入栏，不能被当成列表页拦掉（否则转账永远记不上）
        val chat =
            "浮窗 退出浮窗 返回 聊天信息 陈冠霖 中午12:44 ¥10.91 转账 已被接收 " +
                "¥10.91 转账 已收款 表情 语音输入 按住 说话"
        assertFalse(PageGuard.isChatListPage(chat))
    }

    @Test
    fun alipayPendingPayPageIsBlocked() {
        // 支付宝「付款给个人」：金额输入框里是上次的 11.00，用户还没确认
        val pending = "付款给个人 余额 花呗 11.00 添加备注 确认付款 请输入支付密码"
        assertTrue(PageGuard.isPendingPayPage(pending))
    }

    @Test
    fun alipayScanPageIsBlocked() {
        // 扫码页连金额都没有，只有操作按钮
        val scan = "扫一扫 相册 输入金额 扫码支付"
        assertTrue(PageGuard.isPendingPayPage(scan))
    }

    @Test
    fun paidPageIsNotBlocked() {
        // 支付成功页有成交后字段，必须放行（否则真实账单记不上）
        val paid = "支付成功 ¥4.50 华泰园师生服务超市 交易成功 付款方式 花呗 支付时间 2026-11-20 18:00"
        assertFalse(PageGuard.isPendingPayPage(paid))
    }

    @Test
    fun receiptWithAmountInputWordsIsNotBlocked() {
        // 详情页里出现「付款金额」这类字段不算"待付款"（它本来就是账单字段）
        val receipt = "当前状态 交易成功 付款金额 17.50 商户全称 示例便利店 交易单号 2026112022001"
        assertFalse(PageGuard.isPendingPayPage(receipt))
    }

    @Test
    fun billPageWithListLikeWordsIsNotBlocked() {
        // 有的账单页也有「搜索 / 发现 / 主页」这类入口文字，
        // 但只要出现账单字段就绝不能按「聊天列表页」拦掉（否则真实账单记不上）
        val bill = "搜索 发现 主页 交易单号 2026112022001234 商户全称 示例便利店 " +
            "当前状态 支付成功 支付时间 2026-11-20 18:00 金额 ¥17.50"
        assertFalse(PageGuard.isChatListPage(bill))
    }
}
