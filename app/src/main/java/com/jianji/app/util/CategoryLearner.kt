package com.jianji.app.util

import android.content.Context
import com.jianji.app.App
import com.jianji.app.core.Categories
import com.jianji.app.db.CategoryMemoryDao

/**
 * 分类学习器：**把「你改过的分类」变成默认分类**，减少手动编辑。
 *
 * 学习维度有三个（都来自你的明确动作：手动改分类 / 手动记一笔）：
 * 1. **商户名称** —— 最强信号（同一家店的消费通常同类）；
 * 2. **备注 / 标签** —— 「这类消费」的信号；
 * 3. **商户 + 金额档** —— 同一家店同一价位最可靠（例如某店 30 元档是餐饮、300 元档是数码）。
 *
 * 必须在后台线程调用（会读数据库）。开关在「自动记账 → 分类自动学习」。
 */
object CategoryLearner {

    /** 给出建议分类：记忆优先，其次按规则猜 */
    fun suggest(
        ctx: Context,
        merchant: String,
        isIncome: Boolean,
        rawText: String,
        pkg: String,
        note: String = "",
        amount: Double = 0.0
    ): String {
        if (Prefs.isCategoryLearning(ctx)) {
            val learned = runCatching {
                val m = merchant.ifBlank { note }
                (ctx.applicationContext as App).categoryMemory.suggest(m, note, amount)
            }.getOrNull()
            if (!learned.isNullOrBlank()) return learned
        }
        return Categories.guess(merchant, isIncome, rawText, pkg)
    }

    /** 学一票（用户明确改过 / 记过才调用），同时更新三个维度 */
    fun learn(ctx: Context, merchant: String, note: String, amount: Double, category: String) {
        if (!Prefs.isCategoryLearning(ctx)) return
        if (category.isBlank()) return
        if (merchant.isBlank() && note.isBlank()) return
        runCatching {
            (ctx.applicationContext as App).categoryMemory.learn(merchant, note, amount, category)
        }
    }

    /** 学习记录（界面展示用） */
    fun entries(ctx: Context) = runCatching {
        (ctx.applicationContext as App).categoryMemory.all()
    }.getOrDefault(emptyList())

    fun clear(ctx: Context) {
        runCatching { CategoryMemoryDao(ctx).clear() }
    }
}
