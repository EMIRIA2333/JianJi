package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hook 模块协议测试（与 LSPosed 模块共用同一份常量，避免两边写错）。
 */
class HookProtocolTest {

    @Test
    fun watchedPackagesCoverPaymentApps() {
        assertTrue(HookProtocol.isWatched(PaymentParser.PKG_WECHAT))
        assertTrue(HookProtocol.isWatched(PaymentParser.PKG_ALIPAY))
        assertTrue(HookProtocol.isWatched("com.unionpay"))
        // 主流购物平台也在监听范围内（与无障碍配置保持一致）
        assertTrue(HookProtocol.isWatched("com.taobao.taobao"))
        assertFalse(HookProtocol.isWatched("com.android.systemui"))
        assertFalse(HookProtocol.isWatched(""))
    }

    @Test
    fun tokenMustMatchExactly() {
        assertTrue(HookProtocol.validToken(HookProtocol.TOKEN))
        assertFalse(HookProtocol.validToken(null))
        assertFalse(HookProtocol.validToken(""))
        assertFalse(HookProtocol.validToken(HookProtocol.TOKEN + "x"))
    }

    @Test
    fun heartbeatDecidesActiveState() {
        val now = 1_000_000_000_000L
        // 从未收到心跳
        assertFalse(HookProtocol.isActive(0L, now))
        // 刚收到
        assertTrue(HookProtocol.isActive(now - 1_000, now))
        // 心跳有效期内
        assertTrue(HookProtocol.isActive(now - HookProtocol.ACTIVE_TTL_MS + 1, now))
        // 超过有效期（模块被停用/卸载）
        assertFalse(HookProtocol.isActive(now - HookProtocol.ACTIVE_TTL_MS - 1, now))
    }

    @Test
    fun hookPayloadCanBeFedIntoRecognition() {
        // Hook 到的通知正文（真机样本）必须能被现有解析器认出来
        val alipay = PaymentParser.parse(
            PaymentParser.PKG_ALIPAY, "交易提醒",
            "你在智能自助服务有一笔0.08元的免密/自动扣款支付，点击领取4个支付宝积分。"
        )
        assertEquals(RecordType.EXPENSE, alipay!!.type)
        assertEquals(0.08, alipay.amount, 1e-9)

        val wx = PaymentParser.parse(PaymentParser.PKG_WECHAT, "微信支付", "你已收款 ¥0.01")
        assertEquals(RecordType.INCOME, wx!!.type)
    }

    @Test
    fun fastWatchedSetCoversPaymentApps() {
        // 模块侧用的是硬编码集合（不触碰任何解析器类，避免类初始化失败导致 hook 被跳过）
        assertTrue(HookProtocol.isWatchedFast(PaymentParser.PKG_WECHAT))
        assertTrue(HookProtocol.isWatchedFast(PaymentParser.PKG_ALIPAY))
        assertFalse(HookProtocol.isWatchedFast("com.android.systemui"))
        // 模块侧集合必须覆盖主程序侧的全部支付应用
        assertTrue(HookProtocol.FAST_WATCHED.containsAll(setOf(PaymentParser.PKG_WECHAT, PaymentParser.PKG_ALIPAY)))
    }

    @Test
    fun selfTestHeartbeatIsMarkedSeparately() {
        // 自测阶段有独立标记：接收端据此**不点亮**"模块已生效"（曾经误报过）
        assertEquals("selftest", HookProtocol.STAGE_SELFTEST)
    }

    @Test
    fun protocolConstantsAreStable() {
        assertEquals("com.jianji.app.HOOK_BRIDGE", HookProtocol.ACTION)
        assertEquals("com.jianji.app.service.HookBridgeReceiver", HookProtocol.HOST_RECEIVER)
        assertEquals("com.jianji.app", HookProtocol.HOST_PKG)
    }
}
