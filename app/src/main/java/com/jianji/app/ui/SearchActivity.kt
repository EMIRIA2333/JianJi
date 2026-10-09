package com.jianji.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jianji.app.App
import com.jianji.app.R
import com.jianji.app.core.Categories
import com.jianji.app.core.Record
import com.jianji.app.core.RecordType
import com.jianji.app.databinding.ActivitySearchBinding
import com.jianji.app.db.RecordDao
import com.jianji.app.util.Money
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 账单搜索：支持按关键词（备注/分类/标签）、收支方向、分类、标签、金额区间、时间区间组合查询。
 */
class SearchActivity : BaseActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val adapter by lazy {
        SearchAdapter(
            onClick = { startActivity(EditRecordActivity.intent(this, it.id)) },
            onLongClick = { confirmDelete(it) }
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val dateFmt = DateTimeFormatter.ofPattern("MM-dd")

    private var typeFilter = -1
    private var categoryFilter: String? = null
    private var tagFilter: String? = null
    private var startFilter: Long? = null
    private var endFilter: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter

        buildCategoryChips()
        loadTags()

        binding.etQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ runSearch() }, 260)
            }
        })
        binding.toggleType.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            typeFilter = when (checkedId) {
                R.id.btnExpenseOnly -> RecordType.EXPENSE
                R.id.btnIncomeOnly -> RecordType.INCOME
                else -> -1
            }
            runSearch()
        }
        binding.etMin.addTextChangedListener(simpleWatcher { runSearch() })
        binding.etMax.addTextChangedListener(simpleWatcher { runSearch() })
        binding.btnDateStart.setOnClickListener { pickDate(true) }
        binding.btnDateEnd.setOnClickListener { pickDate(false) }
        binding.btnClearFilters.setOnClickListener { clearFilters() }

        runSearch()
    }

    private fun simpleWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ onChange() }, 260)
        }
    }

    private fun buildCategoryChips() {
        binding.cgCategories.removeAllViews()
        fun addChip(label: String, isAll: Boolean, category: String?) {
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                id = View.generateViewId()
                isChecked = isAll
                if (!isAll && category != null) {
                    chipIcon = ContextCompat.getDrawable(this@SearchActivity, CategoryIcons.iconRes(category))
                    isChipIconVisible = true
                    chipIconTint = android.content.res.ColorStateList.valueOf(Categories.bgColor(category))
                }
            }
            chip.setOnClickListener {
                categoryFilter = category
                runSearch()
            }
            binding.cgCategories.addView(chip)
        }
        addChip("全部分类", true, null)
        Categories.EXPENSE_LIST.forEach { addChip(it, false, it) }
        Categories.INCOME_LIST.forEach { addChip(it, false, it) }
    }

    private fun loadTags() {
        val app = application as App
        app.post {
            val tags = app.dao.allTags()
            runOnUiThread {
                binding.cgTags.removeAllViews()
                if (tags.isEmpty()) {
                    binding.scrollTags.visibility = View.GONE
                    return@runOnUiThread
                }
                binding.scrollTags.visibility = View.VISIBLE
                val allChip = Chip(this).apply {
                    text = "全部标签"
                    isCheckable = true
                    id = View.generateViewId()
                    isChecked = true
                    setOnClickListener {
                        tagFilter = null
                        runSearch()
                    }
                }
                binding.cgTags.addView(allChip)
                tags.forEach { tag ->
                    val chip = Chip(this).apply {
                        text = "#$tag"
                        isCheckable = true
                        id = View.generateViewId()
                        setOnClickListener {
                            tagFilter = tag
                            runSearch()
                        }
                    }
                    binding.cgTags.addView(chip)
                }
            }
        }
    }

    private fun pickDate(isStart: Boolean) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(if (isStart) "选择开始日期" else "选择结束日期")
            .setSelection(startFilter ?: System.currentTimeMillis())
            .build()
        picker.addOnPositiveButtonClickListener { utcMillis ->
            val day = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
            if (isStart) {
                startFilter = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                binding.btnDateStart.text = day.format(dateFmt)
            } else {
                endFilter = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                binding.btnDateEnd.text = day.format(dateFmt)
            }
            runSearch()
        }
        picker.show(supportFragmentManager, "search_date")
    }

    private fun clearFilters() {
        binding.etQuery.setText("")
        binding.etMin.setText("")
        binding.etMax.setText("")
        binding.toggleType.check(R.id.btnAll)
        categoryFilter = null
        tagFilter = null
        startFilter = null
        endFilter = null
        binding.btnDateStart.text = "开始日期"
        binding.btnDateEnd.text = "结束日期"
        typeFilter = -1
        buildCategoryChips()
        loadTags()
        runSearch()
    }

    private fun runSearch() {
        val q = RecordDao.Query(
            text = binding.etQuery.text?.toString().orEmpty(),
            type = typeFilter,
            category = categoryFilter,
            tag = tagFilter,
            minAmount = binding.etMin.text?.toString()?.trim()?.toDoubleOrNull(),
            maxAmount = binding.etMax.text?.toString()?.trim()?.toDoubleOrNull(),
            start = startFilter,
            end = endFilter
        )
        val app = application as App
        app.post {
            val list = app.dao.search(q)
            runOnUiThread { render(list) }
        }
    }

    private fun render(list: List<Record>) {
        adapter.submit(list)
        var expense = 0.0
        var income = 0.0
        list.forEach { if (it.type == RecordType.EXPENSE) expense += it.amount else income += it.amount }
        binding.tvSummary.text = String.format(
            Locale.US, "共 %d 条 · 支出 %s · 收入 %s",
            list.size, Money.yuan(this, expense), Money.yuan(this, income)
        )
    }

    private fun confirmDelete(r: Record) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除账单")
            .setMessage("将「${r.category} ${Money.plain(r.amount)}」移入回收站？可在回收站还原。")
            .setPositiveButton("移入回收站") { _, _ ->
                val app = application as App
                app.post {
                    app.dao.softDelete(r.id)
                    runOnUiThread {
                        Toast.makeText(this, "已移入回收站", Toast.LENGTH_SHORT).show()
                        runSearch()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
