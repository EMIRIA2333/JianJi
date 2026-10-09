package com.jianji.app.db

import android.content.ContentValues
import android.content.Context
import com.jianji.app.core.CategoryMemory

/**
 * 分类记忆存取（必须在后台线程调用）。
 *
 * 学习来源只有**用户的明确动作**：手动改分类、手动记一笔。
 * 每次 +1 票，之后相似的账单按「相似度 → 票数 → 最近使用」自动套用分类。
 */
class CategoryMemoryDao(context: Context) {

    private val helper = DbHelper(context)

    /**
     * 学一票：一次调用同时更新三个维度的记忆
     * - 商户（最强）
     * - 备注 / 标签（"这类消费"）
     * - 商户 + 金额档（同一家店同一价位最可靠）
     */
    fun learn(merchant: String, note: String, amount: Double, category: String) {
        if (category.isBlank()) return
        val keys = listOf(
            CategoryMemory.merchantKey(merchant),
            CategoryMemory.noteKey(note),
            CategoryMemory.amountKey(merchant, amount)
        ).filter { it.length > 2 && !it.endsWith(":") }
        keys.forEach { put(it, category) }
    }

    /** 学一票（只有商户时） */
    fun learn(merchant: String, category: String) {
        learn(merchant, "", 0.0, category)
    }

    private fun put(key: String, category: String) {
        val now = System.currentTimeMillis()
        val db = helper.writableDatabase
        val updated = db.compileStatement(
            "UPDATE category_memory SET hits = hits + 1, updated_at = ? WHERE mkey = ? AND category = ?"
        ).use { st ->
            st.bindLong(1, now)
            st.bindString(2, key)
            st.bindString(3, category)
            st.executeUpdateDelete()
        }
        if (updated <= 0) {
            val values = ContentValues().apply {
                put("mkey", key)
                put("category", category)
                put("hits", 1)
                put("updated_at", now)
            }
            runCatching { db.insertWithOnConflict("category_memory", null, values, 4) }
        }
    }

    /** 全部记忆（票数多的在前），供匹配与界面展示 */
    fun all(): List<CategoryMemory.Entry> {
        val out = ArrayList<CategoryMemory.Entry>()
        runCatching {
            helper.readableDatabase.rawQuery(
                "SELECT mkey, category, hits, updated_at FROM category_memory ORDER BY hits DESC, updated_at DESC LIMIT 300",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        CategoryMemory.Entry(
                            key = c.getString(0),
                            category = c.getString(1),
                            hits = c.getInt(2),
                            updatedAt = c.getLong(3)
                        )
                    )
                }
            }
        }
        return out
    }

    /** 对某个商户/备注/金额给出学到的分类（没有匹配返回 null） */
    fun suggest(merchant: String, note: String, amount: Double): String? =
        CategoryMemory.pickBest(merchant, note, amount, all())?.category

    /** 只按商户查（兼容旧调用） */
    fun suggest(merchant: String): String? = suggest(merchant, "", 0.0)

    fun clear() {
        runCatching { helper.writableDatabase.execSQL("DELETE FROM category_memory") }
    }
}
