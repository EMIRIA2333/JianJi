package com.jianji.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeduperTest {

    @Test
    fun duplicateWithinWindow() {
        var now = 1000L
        val d = Deduper(windowMs = 60_000, clock = { now })
        assertFalse(d.duplicate(RecordType.EXPENSE, 26.0, "老王烧烤"))
        now = 2000L
        // 同类型+同金额+同商户 → 重复
        assertTrue(d.duplicate(RecordType.EXPENSE, 26.0, "老王烧烤"))
        // 商户不同 → 不重复
        assertFalse(d.duplicate(RecordType.EXPENSE, 26.0, "隔壁烧烤"))
        // 类型不同 → 不重复
        assertFalse(d.duplicate(RecordType.INCOME, 26.0, "老王烧烤"))
        // 金额不同 → 不重复
        assertFalse(d.duplicate(RecordType.EXPENSE, 26.5, "老王烧烤"))
    }

    @Test
    fun windowExpiry() {
        var now = 1000L
        val d = Deduper(windowMs = 60_000, clock = { now })
        assertFalse(d.duplicate(RecordType.EXPENSE, 26.0, "老王烧烤"))
        now = 1000L + 60_001
        assertFalse(d.duplicate(RecordType.EXPENSE, 26.0, "老王烧烤"))
    }

    @Test
    fun textDedup() {
        var now = 0L
        val d = Deduper(windowMs = 60_000, clock = { now })
        val text = "微信支付凭证\n你已成功向【老王烧烤】支付¥26.00"
        assertFalse(d.duplicateText(text))
        assertTrue(d.duplicateText(text))
        now += 60_001
        assertFalse(d.duplicateText(text))
    }

    @Test
    fun amountPrecision() {
        val d = Deduper(windowMs = 60_000)
        assertFalse(d.duplicate(RecordType.EXPENSE, 19.99, "A"))
        // 19.99 与 19.990 应视为同一金额
        assertTrue(d.duplicate(RecordType.EXPENSE, 19.990, "A"))
    }

    @Test
    fun clear() {
        val d = Deduper(windowMs = 60_000)
        assertFalse(d.duplicate(RecordType.EXPENSE, 1.0, "A"))
        d.clear()
        assertFalse(d.duplicate(RecordType.EXPENSE, 1.0, "A"))
    }

    @Test
    fun looseDuplicateWhenMerchantIsGenericPlatformName() {
        // 通知先记一次（商户=微信），页面上又解析出推广文案作为商户 -> 应判为重复
        val d = Deduper(windowMs = 60_000)
        assertFalse(d.duplicate(RecordType.EXPENSE, 3.31, "微信"))
        assertTrue(d.duplicate(RecordType.EXPENSE, 3.31, "下单限时享9.5折"))
        // 反过来也成立
        val d2 = Deduper(windowMs = 60_000)
        assertFalse(d2.duplicate(RecordType.EXPENSE, 8.80, "下单立减"))
        assertTrue(d2.duplicate(RecordType.EXPENSE, 8.80, "支付宝"))
    }

    @Test
    fun looseDuplicateWhenMerchantsOverlap() {
        val d = Deduper(windowMs = 60_000)
        assertFalse(d.duplicate(RecordType.EXPENSE, 17.0, "示例学院-二楼小吃"))
        // 同一商户的更长名称（页面带后缀）仍视为同一笔
        assertTrue(d.duplicate(RecordType.EXPENSE, 17.0, "示例学院-二楼小吃9001A收款"))
    }

    @Test
    fun differentSpecificMerchantsSameAmountAreNotDuplicates() {
        // 两笔真实消费：同金额但商户完全不同 -> 不判重复
        val d = Deduper(windowMs = 60_000)
        assertFalse(d.duplicate(RecordType.EXPENSE, 15.0, "老王烧烤"))
        assertFalse(d.duplicate(RecordType.EXPENSE, 15.0, "沙县小吃"))
        // 方向不同也不算
        assertFalse(d.duplicate(RecordType.INCOME, 15.0, "老王烧烤"))
    }

    @Test
    fun pendingMarkerPreventsRepeatedPrompts() {
        val d = Deduper(windowMs = 60_000)
        assertTrue(d.markPending(RecordType.EXPENSE, 17.0, "示例学院"))
        // 同一笔在等待用户处理期间再次被识别 -> 不再弹提醒
        assertFalse(d.markPending(RecordType.EXPENSE, 17.0, "示例学院"))
        // 用户保存/忽略后清除标记，下一笔可以再弹
        d.clearPending(RecordType.EXPENSE, 17.0, "示例学院")
        assertTrue(d.markPending(RecordType.EXPENSE, 17.0, "示例学院"))
        // 不同金额互不影响
        assertTrue(d.markPending(RecordType.EXPENSE, 18.0, "示例学院"))
    }

    @Test
    fun pendingMarkerExpiresQuickly() {
        // 回归：待处理标记曾经活 3 分钟，导致「同一商户紧接着的第二笔相同金额」
        // 被静默吞掉（连疑似重复的提示都没有）
        var now = 0L
        val d = Deduper(windowMs = 60_000, clock = { now })
        assertTrue(d.markPending(RecordType.EXPENSE, 0.01, "🐱"))
        assertFalse(d.markPending(RecordType.EXPENSE, 0.01, "🐱"))
        // 超过 PENDING_TTL_MS(30s) 后，同一笔再次出现必须能重新提示
        now += Deduper.PENDING_TTL_MS + 1_000
        assertTrue(d.markPending(RecordType.EXPENSE, 0.01, "🐱"))
    }

    @Test
    fun removeAllowsReInsert() {
        val d = Deduper(windowMs = 60_000)
        assertFalse(d.duplicate(RecordType.EXPENSE, 26.0, "老王烧烤"))
        assertTrue(d.duplicate(RecordType.EXPENSE, 26.0, "老王烧烤"))
        // 用户确认「仍然录入」后移除去重记录
        d.remove(RecordType.EXPENSE, 26.0, "老王烧烤")
        assertFalse(d.duplicate(RecordType.EXPENSE, 26.0, "老王烧烤"))
    }

    @Test
    fun boundedMemory() {
        var now = 0L
        val d = Deduper(windowMs = 60_000, clock = { now })
        for (i in 0 until 10_000) {
            d.duplicate(RecordType.EXPENSE, i.toDouble(), "店$i")
        }
        // 超过容量上限后旧条目被挤出，不会无限增长
        assertFalse(d.duplicate(RecordType.EXPENSE, 0.0, "店0"))
    }
}
