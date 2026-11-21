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

    /** 日志里支付宝首页的真实文本（248 字那条，含「最近消息 闲鱼 自动扣款成功￥23.35 43分钟前」） */
    private val alipayHome =
        "首页 理财 消息 我的 松开刷新 搜索框 昆明 阴 10℃ 更多操作 搜索 全部 碰一下 " +
            "云南省电子税务局 趣智校园 支付宝贴纸 医保 支付宝小荷包 为你推荐 消息盒子 " +
            "闲鱼 自动扣款成功￥23.35 43分钟前 蚂蚁庄园 今日210克饲料待领取 1小时前 " +
            "芝麻信用 芝麻分 70 速领取"

    @Test
    fun alipayHomePageIsBlocked() {
        // 支付宝首页的「最近消息」卡片写着「自动扣款成功￥23.35 · 43分钟前」，
        // 曾同时命中「自动扣款」+「扣款成功」→ 打开首页就记了三笔 -23.35
        assertTrue(PageGuard.isAppHomePage(alipayHome))
    }

    @Test
    fun alipayHomePageIsNotTreatedAsPaidEvidence() {
        // 首页有「扣款成功」也不算成交证据，付款页仍然要被拦住
        val payPageWithHomeBleed =
            "删除 付款 添加备注 首页 理财 消息 我的 付款给个人 松开刷新 搜索框 更多操作 " +
                "鑫隆超市(**美) 输入付款金额 碰一下"
        assertTrue(PageGuard.isPendingPayPage(payPageWithHomeBleed))
    }

    @Test
    fun realBillPageIsNotHomePage() {
        // 真正的支付成功页必须放行
        val paid = "支付成功 ¥23.35 闲鱼 当前状态 交易成功 交易单号 2026112122001 " +
            "支付时间 2026-11-21 12:55:14 付款方式 花呗 商户全称 示例商户"
        assertFalse(PageGuard.isAppHomePage(paid))
    }

    @Test
    fun homePageIsNotChatListPage() {
        // 支付宝首页也不该被误判成微信聊天列表页（两套规则互不干扰）
        assertFalse(PageGuard.isChatListPage(alipayHome))
    }
}
