package com.jianji.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.jianji.app.App
import com.jianji.app.R
import com.jianji.app.core.Categories
import com.jianji.app.core.Record
import com.jianji.app.core.RecordSource
import com.jianji.app.core.RecordType
import com.jianji.app.databinding.ActivityCategoryRecordsBinding
import com.jianji.app.databinding.ItemRecordBinding
import com.jianji.app.util.CsvFormat
import com.jianji.app.util.Money
import com.jianji.app.util.TimeUtil

/**
 * **某个分类的具体账单**（点统计页的分类排行进入）。
 *
 * 独立页面而不是弹窗：能滚动、能点进单笔编辑、长按删除，跟明细页体验一致。
 */
class CategoryRecordsActivity : BaseActivity() {

    private lateinit var binding: ActivityCategoryRecordsBinding
    private var category: String = ""
    private var start: Long = 0L
    private var end: Long = Long.MAX_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryRecordsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
        start = intent.getLongExtra(EXTRA_START, 0L)
        end = intent.getLongExtra(EXTRA_END, Long.MAX_VALUE)

        binding.toolbar.title = if (category.isBlank()) "分类账单" else category
        binding.toolbar.setNavigationOnClickListener { finish() }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val app = application as App
        val s = start
        val e = end
        val cat = category
        app.post {
            val all = runCatching { app.dao.range(s, e) }.getOrDefault(emptyList())
            val list = all.filter { cat.isBlank() || it.category == cat }.sortedByDescending { it.time }
            runOnUiThread { render(list) }
        }
    }

    private fun render(list: List<Record>) {
        val ctx = this
        var expense = 0.0
        var income = 0.0
        list.forEach { if (it.type == RecordType.EXPENSE) expense += it.amount else income += it.amount }
        binding.tvSummary.text = buildString {
            append(TimeUtil.formatDayHeader(start)).append(" ~ ").append(TimeUtil.formatDayHeader(end - 1))
            append("　共 ").append(list.size).append(" 笔")
            append("　支出 ").append(Money.yuan(ctx, expense))
            if (income > 0) append("　收入 ").append(Money.yuan(ctx, income))
        }

        binding.llRecords.removeAllViews()
        if (list.isEmpty()) {
            binding.llRecords.addView(TextView(ctx).apply {
                text = "这个区间里「$category」没有账单"
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                textSize = 13f
                setPadding(48, 48, 48, 48)
            })
            return
        }

        list.forEach { r ->
            val row = ItemRecordBinding.inflate(layoutInflater, binding.llRecords, false)
            row.ivCheck.visibility = View.GONE
            row.ivIcon.setImageResource(CategoryIcons.iconRes(r.category))
            row.ivIcon.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Categories.bgColor(r.category))
            }
            row.tvTitle.text = buildString {
                append(r.category)
                if (r.displayName.isNotBlank()) append(" · ").append(r.displayName)
            }
            row.tvTime.text = buildString {
                append(TimeUtil.formatList(r.time))
                if (r.payMethod.isNotBlank()) append(" · ").append(r.payMethod)
                if (r.tag.isNotBlank()) append("  #").append(r.tag)
                if (r.source != RecordSource.MANUAL) append(" · ").append(CsvFormat.sourceName(r.source))
            }
            row.tvAmount.text = Money.signedYuan(ctx, r.type, r.amount)
            row.tvAmount.setTextColor(
                ContextCompat.getColor(ctx, if (r.type == RecordType.INCOME) R.color.income_green else R.color.expense_red)
            )
            row.root.setOnClickListener {
                startActivity(EditRecordActivity.intent(ctx, r.id))
            }
            row.root.setOnLongClickListener { confirmDelete(r); true }
            binding.llRecords.addView(row.root)
        }
    }

    private fun confirmDelete(r: Record) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("删除账单")
            .setMessage("将「${r.category} ${Money.plain(r.amount)}」移入回收站？可在回收站还原。")
            .setPositiveButton("移入回收站") { _, _ ->
                val app = application as App
                app.post {
                    app.dao.softDelete(r.id)
                    runOnUiThread {
                        Toast.makeText(this, "已移入回收站", Toast.LENGTH_SHORT).show()
                        load()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val EXTRA_CATEGORY = "category"
        private const val EXTRA_START = "start"
        private const val EXTRA_END = "end"

        fun intent(ctx: Context, category: String, start: Long, end: Long): Intent =
            Intent(ctx, CategoryRecordsActivity::class.java).apply {
                putExtra(EXTRA_CATEGORY, category)
                putExtra(EXTRA_START, start)
                putExtra(EXTRA_END, end)
            }
    }
}