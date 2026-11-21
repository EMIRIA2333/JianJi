package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 通知指纹测试：保证「同一条通知只记一次」，同时不误杀真实的第二笔。
 *
 * 回归背景：用户通知栏里留着一条免密支付消息没清理，
 * 分组通知每次更新 + root 每 30 秒轮询 → 同一条通知被反复记账。
 */
class NotificationFingerprintTest {

    private val pkg = PaymentParser.PKG_ALIPAY
    private val title = "交易提醒"
    private val text = "你在智能自助服务有一笔0.09元的免密/自动扣款支付，点击领取4个支付宝积分。"

    @Test
    fun sameNotificationProducesSameFingerprint() {
        val a = NotificationFingerprint.of(pkg, title, text, 1791121288436L)
        val b = NotificationFingerprint.of(pkg, title, text, 1791121288436L)
        assertEquals(a, b)
        assertEquals(NotificationFingerprint.key(a), NotificationFingerprint.key(b))
    }

    @Test
    fun groupCountNoiseIsStripped() {
        // 微信/支付宝分组通知的「[12条]」「[13条]」会随更新变化，必须归一化掉
        val a = NotificationFingerprint.of("com.tencent.mm", "微信支付", "[12条]微信支付: 已支付¥0.78", 1L)
        val b = NotificationFingerprint.of("com.tencent.mm", "微信支付", "[13条]微信支付: 已支付¥0.78", 1L)
        assertEquals(a, b)
    }

    @Test
    fun whitespaceDifferencesAreIgnored() {
        val a = NotificationFingerprint.of("com.tencent.mm", "微信支付", "你已收款 ¥0.01", 5L)
        val b = NotificationFingerprint.of("com.tencent.mm", "微信支付", "你已收款  ¥0.01 ", 5L)
        assertEquals(a, b)
    }

    @Test
    fun aRealSecondPaymentIsNotKilled() {
        // 同样金额但通知时间戳不同 = 新的一笔，必须能记。
        // 指纹里的时间按 30 秒分桶（跨通道容差），所以这里要用**跨桶**的时间：
        // 5 秒内的差别是"同一条通知被不同通道各报了一次"，不能算两笔。
        val first = NotificationFingerprint.of(pkg, title, text, 1_000L)
        val second = NotificationFingerprint.of(pkg, title, text, 40_000L)
        assertNotEquals(first, second)
        // 金额不同也要区分
        val other = NotificationFingerprint.of(pkg, title, text.replace("0.09", "0.10"), 1_000L)
        assertNotEquals(first, other)
    }

    @Test
    fun sameNotificationFromDifferentChannelsSharesAKey() {
        // 真实 bug：同一条免密支付，Hook 通道与「通知读取」通道各报一次，
        // 两次的 notifyTime 差几百毫秒 → 以前的精确时间戳指纹不相等 → 同一笔记两遍。
        val fromHook = NotificationFingerprint.keys(pkg, title, text, 1_791_121_288_436L)
        val fromListener = NotificationFingerprint.keys(pkg, title, text, 1_791_121_288_436L + 260L)
        assert(fromHook.any { a -> fromListener.any { it == a } }) {
            "同一通知换个通道必须共享至少一个指纹键（容差 ±1 桶）"
        }
        // 反过来：隔了 2 分钟的同额通知，不能共享键（那是真的第二笔）
        val later = NotificationFingerprint.keys(pkg, title, text, 1_791_121_288_436L + 120_000L)
        assert(fromHook.none { a -> later.any { it == a } }) {
            "两分钟后的新通知不能与之前共享指纹键"
        }
    }
}
