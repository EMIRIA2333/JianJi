package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 扫描策略测试：事件预筛与自适应降频（省电的核心逻辑） */
class ScanPolicyTest {

    @Test
    fun paymentRelatedTextIsInteresting() {
        listOf(
            "已支付¥26.00",
            "你已成功发送红包",
            "收款成功 88.00",
            "账单明细",
            "零钱明细",
            "退款成功：¥39.90",
            "花呗 扣款成功 15.00元",
            "交易单号 2026100122001"
        ).forEach { assertTrue("应判为相关：$it", ScanPolicy.looksInteresting(it)) }
    }

    @Test
    fun transferAndRedPacketBubbleWordsAreInteresting() {
        // 回归：这几个词曾经不在白名单里，导致「已被接收 / 已被领取」事件被预筛掉，
        // 聊天页整页都不再解析（红包 / 转账完全不记账）
        listOf("已被接收", "已被领取", "已被领完", "你领取了", "已存入零钱", "已退还").forEach {
            assertTrue("转账/红包气泡状态词应判为相关：$it", ScanPolicy.looksInteresting(it))
        }
    }

    @Test
    fun bareAmountIsInteresting() {
        // 账单页把金额渲染成大字号纯数字，这时事件文本只有数字
        listOf("17.00", "-17.00", "¥17.00", "+50.00", "1,234.56", "0.08元").forEach {
            assertTrue("纯金额应判为相关：$it", ScanPolicy.looksInteresting(it))
        }
    }

    @Test
    fun irrelevantTextIsSkipped() {
        // 聊天、搜索输入、无关页面内容：不该为它遍历节点树
        listOf(
            "在吗",
            "恭喜发财，大吉大利",
            "今天天气不错",
            "搜索",
            "ABCDEF",
            "第 3 页，共 20 页"
        ).forEach { assertFalse("应跳过：$it", ScanPolicy.looksInteresting(it)) }
    }

    @Test
    fun emptyTextIsAllowed() {
        // 文本为空时无从判断（例如 WebView 更新不带文本），允许继续按间隔扫描
        assertTrue(ScanPolicy.looksInteresting(null))
        assertTrue(ScanPolicy.looksInteresting(""))
        assertTrue(ScanPolicy.looksInteresting("   "))
    }

    @Test
    fun intervalsFavourWindowScansAndPowerSave() {
        // 窗口切换事件是页面级变化，优先扫描
        assertEquals(ScanPolicy.WINDOW_INTERVAL_MS, ScanPolicy.intervalFor(true, true, 0))
        // 省电模式下内容变化扫描间隔更长
        assertTrue(
            ScanPolicy.intervalFor(false, true, 0) > ScanPolicy.intervalFor(false, false, 0)
        )
        assertEquals(ScanPolicy.CONTENT_INTERVAL_MS, ScanPolicy.intervalFor(false, false, 0))
        assertEquals(ScanPolicy.CONTENT_INTERVAL_SAVE_MS, ScanPolicy.intervalFor(false, true, 0))
    }

    @Test
    fun backoffGrowsThenCaps() {
        assertEquals(1, ScanPolicy.factorFor(0))
        assertEquals(1, ScanPolicy.factorFor(7))
        assertEquals(2, ScanPolicy.factorFor(8))
        assertEquals(2, ScanPolicy.factorFor(999))
        // 识别优先：最多只降一半频率（省电模式下 2.5s × 2 = 5s）
        assertEquals(
            ScanPolicy.CONTENT_INTERVAL_SAVE_MS * 2,
            ScanPolicy.intervalFor(false, true, 30)
        )
        // 退避不影响窗口切换事件（页面导航必须及时识别）
        assertEquals(ScanPolicy.WINDOW_INTERVAL_MS, ScanPolicy.intervalFor(true, true, 30))
    }

    @Test
    fun systemBatterySaverFurtherSlowsScanning() {
        // 系统全局省电：内容扫描再降一半频率
        assertEquals(
            ScanPolicy.CONTENT_INTERVAL_MS * ScanPolicy.BATTERY_SAVER_FACTOR,
            ScanPolicy.intervalFor(false, false, 0, batterySaver = true)
        )
        assertEquals(
            ScanPolicy.CONTENT_INTERVAL_SAVE_MS * ScanPolicy.BATTERY_SAVER_FACTOR,
            ScanPolicy.intervalFor(false, true, 0, batterySaver = true)
        )
        // 窗口切换事件不受省电模式影响（打开账单页必须及时识别）
        assertEquals(
            ScanPolicy.WINDOW_INTERVAL_MS,
            ScanPolicy.intervalFor(true, true, 30, batterySaver = true)
        )
    }

    @Test
    fun nodeLimitsAreBounded() {
        assertTrue(ScanPolicy.nodeLimit(false) < ScanPolicy.nodeLimit(true))
        // 耗电优先：单次扫描的节点上限不得过大（遍历节点树是最贵的动作）
        assertTrue(ScanPolicy.nodeLimit(false) <= 400)
        assertTrue(ScanPolicy.nodeLimit(true) <= 500)
    }

    @Test
    fun intervalsAreBatteryFriendly() {
        // 回归：上一版间隔过密（内容 1s / 省电 2.5s），导致长时间刷微信时 CPU 占用过高
        assertTrue(ScanPolicy.CONTENT_INTERVAL_MS >= 1500)
        assertTrue(ScanPolicy.CONTENT_INTERVAL_SAVE_MS >= 3000)
        assertTrue(ScanPolicy.WINDOW_INTERVAL_MS >= 800)
    }
}
