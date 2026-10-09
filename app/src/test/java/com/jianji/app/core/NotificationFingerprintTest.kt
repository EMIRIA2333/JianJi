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
        // 同样金额但通知时间戳不同 = 新的一笔，必须能记
        val first = NotificationFingerprint.of(pkg, title, text, 1_000L)
        val second = NotificationFingerprint.of(pkg, title, text, 2_000L)
        assertNotEquals(first, second)
        // 金额不同也要区分
        val other = NotificationFingerprint.of(pkg, title, text.replace("0.09", "0.10"), 1_000L)
        assertNotEquals(first, other)
    }
}
