package com.jianji.app.util

import android.content.Context

/**
 * 自定义分类：用户可以加自己的分类（会出现在记账/编辑的分类选择里）。
 *
 * 存储用 `|` 分隔的一行字符串，避免为一个短列表动数据库。
 */
object CustomCategories {

    private const val KEY_EXPENSE = "custom_cat_expense"
    private const val KEY_INCOME = "custom_cat_income"
    private const val SEP = "|"

    fun expense(c: Context): List<String> = parse(Prefs.sp(c).getString(KEY_EXPENSE, "").orEmpty())
    fun income(c: Context): List<String> = parse(Prefs.sp(c).getString(KEY_INCOME, "").orEmpty())

    fun add(c: Context, name: String, isIncome: Boolean): Boolean {
        val n = name.trim().take(8)
        if (n.isEmpty()) return false
        val key = if (isIncome) KEY_INCOME else KEY_EXPENSE
        val cur = if (isIncome) income(c) else expense(c)
        if (cur.contains(n)) return false
        Prefs.sp(c).edit().putString(key, (cur + n).joinToString(SEP)).apply()
        return true
    }

    fun remove(c: Context, name: String, isIncome: Boolean) {
        val key = if (isIncome) KEY_INCOME else KEY_EXPENSE
        val cur = if (isIncome) income(c) else expense(c)
        Prefs.sp(c).edit().putString(key, cur.filter { it != name }.joinToString(SEP)).apply()
    }

    private fun parse(raw: String): List<String> =
        raw.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }
}
