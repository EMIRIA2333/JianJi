package com.jianji.app.util

import android.content.Context
import android.content.Intent
import com.jianji.app.core.Categories
import com.jianji.app.core.Record
import com.jianji.app.core.RecordType

/**
 * 一笔「已识别、等待用户确认」的账单。
 * 可通过悬浮窗按钮、通知按钮或确认页完成，全程无需手动输入。
 */
data class PendingRecord(
    val amount: Double,
    val type: Int,
    val merchant: String,
    val category: String,
    val time: Long,
    val source: Int,
    val similar: Boolean = false,
    val payMethod: String = "",
    val notifyId: Int = 0,
    /** 账单页面截图（相对 filesDir 的路径，「账单图片」开启时才有） */
    val imagePath: String = "",
    /** 自动提取的备注（记入标签） */
    val remark: String = ""
) {

    fun toRecord(): Record = Record(
        amount = amount,
        type = type,
        category = category.ifBlank { Categories.default(type == RecordType.INCOME) },
        note = merchant,
        tag = remark,
        payMethod = payMethod,
        source = source,
        time = time,
        imagePath = imagePath
    )

    /** 摘要：如「支出 17.00 · 餐饮 · 零钱 · 10-01 18:04」 */
    fun summary(ctx: Context): String {
        val sign = if (type == RecordType.INCOME) "收入" else "支出"
        val amountText = if (Prefs.isNotifyHideAmount(ctx)) "**" else Money.plain(amount)
        val pay = if (payMethod.isBlank()) "" else " · $payMethod"
        return "$sign $amountText · $category$pay · ${TimeUtil.formatList(time)}"
    }

    /** 通知摘要（隐私模式隐藏金额） */
    fun summaryNoAmount(ctx: Context): String {
        val pay = if (payMethod.isBlank()) "" else " · $payMethod"
        return "${if (type == RecordType.INCOME) "收入" else "支出"} · $category$pay · ${TimeUtil.formatList(time)}"
    }

    fun putExtras(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_AMOUNT, amount)
        putExtra(EXTRA_TYPE, type)
        putExtra(EXTRA_MERCHANT, merchant)
        putExtra(EXTRA_CATEGORY, category)
        putExtra(EXTRA_TIME, time)
        putExtra(EXTRA_SOURCE, source)
        putExtra(EXTRA_SIMILAR, similar)
        putExtra(EXTRA_PAY_METHOD, payMethod)
        putExtra(EXTRA_NOTIFY_ID, notifyId)
        putExtra(EXTRA_IMAGE_PATH, imagePath)
        putExtra(EXTRA_REMARK, remark)
    }

    companion object {
        const val EXTRA_AMOUNT = "pending_amount"
        const val EXTRA_TYPE = "pending_type"
        const val EXTRA_MERCHANT = "pending_merchant"
        const val EXTRA_CATEGORY = "pending_category"
        const val EXTRA_TIME = "pending_time"
        const val EXTRA_SOURCE = "pending_source"
        const val EXTRA_SIMILAR = "pending_similar"
        const val EXTRA_PAY_METHOD = "pending_pay_method"
        const val EXTRA_NOTIFY_ID = "pending_notify_id"
        const val EXTRA_IMAGE_PATH = "pending_image_path"
        const val EXTRA_REMARK = "pending_remark"

        fun from(intent: Intent): PendingRecord? {
            if (!intent.hasExtra(EXTRA_AMOUNT)) return null
            val amount = intent.getDoubleExtra(EXTRA_AMOUNT, 0.0)
            if (amount <= 0.0) return null
            return PendingRecord(
                amount = amount,
                type = intent.getIntExtra(EXTRA_TYPE, RecordType.EXPENSE),
                merchant = intent.getStringExtra(EXTRA_MERCHANT).orEmpty(),
                category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty(),
                time = intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis()),
                source = intent.getIntExtra(EXTRA_SOURCE, 0),
                similar = intent.getBooleanExtra(EXTRA_SIMILAR, false),
                payMethod = intent.getStringExtra(EXTRA_PAY_METHOD).orEmpty(),
                notifyId = intent.getIntExtra(EXTRA_NOTIFY_ID, 0),
                imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH).orEmpty(),
                remark = intent.getStringExtra(EXTRA_REMARK).orEmpty()
            )
        }
    }
}
