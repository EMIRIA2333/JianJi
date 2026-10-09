package com.jianji.app.util

import android.content.Context
import com.jianji.app.App
import com.jianji.app.core.Categories
import com.jianji.app.core.Record

/**
 * 账单写入统一入口：入库/更新后顺带做预算超支检查（支持每月 / 自定义周期）。
 * 必须在后台线程调用（App.post 内）。
 */
object RecordWriter {

    /**
     * 入库前统一校验方向：分类若是「退款/收款/红包」等只可能是收入的分类，
     * 则方向强制为收入（修复「商品退款被记成支出」）。
     */
    private fun normalize(r: Record): Record {
        val t = Categories.normalizeType(r.type, r.category)
        return if (t == r.type) r else r.copy(type = t)
    }

    fun insert(ctx: Context, r: Record): Long {
        val app = ctx.applicationContext as App
        val id = app.dao.insert(normalize(r))
        if (id > 0) checkBudget(ctx)
        return id
    }

    fun update(ctx: Context, r: Record): Int {
        val app = ctx.applicationContext as App
        val n = app.dao.update(normalize(r))
        if (n > 0) checkBudget(ctx)
        return n
    }

    fun updateAll(ctx: Context, list: List<Record>): Int {
        val app = ctx.applicationContext as App
        val n = app.dao.updateAll(list.map { normalize(it) })
        if (n > 0) checkBudget(ctx)
        return n
    }

    /** 当前预算周期内的支出是否接近/超过额度；同周期内各提醒一次 */
    fun checkBudget(ctx: Context) {
        val budget = Prefs.monthlyBudget(ctx)
        if (budget <= 0.0) return
        val app = ctx.applicationContext as App
        val range = Prefs.budgetRange(ctx)
        val spent = app.dao.expenseSum(range.start, range.end)
        val status = Budget.status(spent, budget)
        val key = range.key()

        when {
            status.level == Budget.LEVEL_OVER -> {
                if (Prefs.budgetWarnedOver(ctx) == key) return
                Prefs.setBudgetWarnedOver(ctx, key)
                Prefs.setBudgetWarnedNear(ctx, key) // 超支后不必再提醒「接近预算」
                Notifier.notifyBudget(ctx, status)
            }
            status.level == Budget.LEVEL_NEAR -> {
                if (Prefs.budgetWarnedNear(ctx) == key) return
                Prefs.setBudgetWarnedNear(ctx, key)
                Notifier.notifyBudget(ctx, status)
            }
        }
    }
}
