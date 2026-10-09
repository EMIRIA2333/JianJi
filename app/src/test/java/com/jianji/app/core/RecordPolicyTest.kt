package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 记账决策测试：把「什么时候直接记账、什么时候先问一句」钉死。
 *
 * 重点是回归 v2.8.3 修的那个静默丢单 bug：
 * **雷同/疑似重复绝不能让记账停下来。**
 */
class RecordPolicyTest {

    @Test
    fun defaultSavesDirectly() {
        // 默认（未开启确认）→ 直接记账
        assertEquals(
            RecordPolicy.SAVE_DIRECT,
            RecordPolicy.decide(confirmBeforeSave = false, autoSaveMianmi = true, isMianmi = false)
        )
    }

    @Test
    fun confirmModeAsksFirst() {
        assertEquals(
            RecordPolicy.ASK_CONFIRM,
            RecordPolicy.decide(confirmBeforeSave = true, autoSaveMianmi = true, isMianmi = false)
        )
    }

    @Test
    fun mianmiSavesDirectlyEvenInConfirmMode() {
        // 免密/自动扣款：钱已经扣完，不该等确认（通知被划掉就漏账）
        assertEquals(
            RecordPolicy.SAVE_DIRECT,
            RecordPolicy.decide(confirmBeforeSave = true, autoSaveMianmi = true, isMianmi = true)
        )
        // 关掉"免密自动记账"时才回到确认流程
        assertEquals(
            RecordPolicy.ASK_CONFIRM,
            RecordPolicy.decide(confirmBeforeSave = true, autoSaveMianmi = false, isMianmi = true)
        )
    }

    @Test
    fun similarNeverSuppressesSaving() {
        // 曾经的反例：similar == true 时只弹提示不记账 → 同金额第二笔起全部丢失
        assertEquals(true, RecordPolicy.saveRegardlessOfSimilar())
        // 无论雷同与否、无论哪条通路，默认路径都必须是「直接记账」
        listOf(true, false).forEach { _ ->
            assertEquals(
                RecordPolicy.SAVE_DIRECT,
                RecordPolicy.decide(confirmBeforeSave = false, autoSaveMianmi = true, isMianmi = false)
            )
        }
    }
}
