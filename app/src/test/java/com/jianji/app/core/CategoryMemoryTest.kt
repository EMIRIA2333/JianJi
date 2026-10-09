package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类记忆匹配逻辑测试。
 *
 * 目标：你手动改过一次分类，相似账单就该自动套用 —— 但**不能乱套**。
 */
class CategoryMemoryTest {

    @Test
    fun normalizeStripsSpaceAndPunctuation() {
        assertEquals("滴滴出行", CategoryMemory.normalizeKey(" 滴滴出行 "))
        assertEquals("starbucks", CategoryMemory.normalizeKey("Starbucks"))
        assertEquals("美团外卖", CategoryMemory.normalizeKey("美团-外卖"))
        assertEquals("全家family", CategoryMemory.normalizeKey("全家 Family"))
    }

    @Test
    fun exactMatchWins() {
        assertEquals(100, CategoryMemory.score("滴滴出行", "滴滴出行"))
        assertEquals(100, CategoryMemory.score(" 滴滴-出行 ", "滴滴出行"))
    }

    @Test
    fun containmentMatches() {
        // 记忆里是「滴滴出行」，新账单是「滴滴出行-快车」→ 应该能套用
        assertTrue(CategoryMemory.score("滴滴出行快车", "滴滴出行") >= CategoryMemory.MIN_SCORE)
        assertTrue(CategoryMemory.score("滴滴出行", "滴滴出行快车") >= CategoryMemory.MIN_SCORE)
    }

    @Test
    fun unrelatedMerchantsDoNotMatch() {
        // 完全不相关的不能套用（宁可让用户改，也不要乱改）
        assertTrue(CategoryMemory.score("老王烧烤", "滴滴出行") < CategoryMemory.MIN_SCORE)
        assertTrue(CategoryMemory.score("永辉超市", "美团外卖") < CategoryMemory.MIN_SCORE)
        assertNull(
            CategoryMemory.pickBest(
                "老王烧烤",
                listOf(CategoryMemory.Entry("滴滴出行", "交通", 9, 1L))
            )
        )
    }

    @Test
    fun pickBestPrefersScoreThenHitsThenRecency() {
        val entries = listOf(
            CategoryMemory.Entry("滴滴出行", "交通", 2, 100L),
            CategoryMemory.Entry("滴滴出行打车", "交通打车", 5, 200L),
            CategoryMemory.Entry("滴滴出行", "交通", 7, 300L)
        )
        // 完全相同的两条：取票数更多的那个分类
        val best = CategoryMemory.pickBest("滴滴出行", entries)
        assertEquals(7, best!!.hits)
    }

    @Test
    fun latestPreferenceWinsOverOldOne() {
        // 你把某个商户的分类从 A 改成 B 多次 → B 票数更多 → 之后自动用 B
        val entries = listOf(
            CategoryMemory.Entry("智能自助服务", "其他", 1, 100L),
            CategoryMemory.Entry("智能自助服务", "数码", 3, 200L)
        )
        assertEquals("数码", CategoryMemory.pickBest("智能自助服务", entries)!!.category)
    }
}
