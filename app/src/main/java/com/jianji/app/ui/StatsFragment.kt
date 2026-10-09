package com.jianji.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jianji.app.App
import com.jianji.app.R
import com.jianji.app.core.Categories
import com.jianji.app.core.Record
import com.jianji.app.core.RecordSource
import com.jianji.app.core.RecordType
import com.jianji.app.databinding.FragmentStatsBinding
import com.jianji.app.databinding.ItemRecordBinding
import com.jianji.app.databinding.ItemStatsDayBinding
import com.jianji.app.db.RecordDao
import com.jianji.app.util.CsvFormat
import com.jianji.app.util.AppLog
import com.jianji.app.util.Money
import com.jianji.app.util.ScrollMemory
import com.jianji.app.util.TimeUtil
import com.jianji.app.view.PieChartView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 统计页：按月（可翻任意月份）、按年（12 个月快捷跳转）、自定义区间，
 * 支出扇形占比 + 分类排行，以及「按天查看」逐日账单。
 */
class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!
    private val legendAdapter = CategoryStatAdapter()

    private enum class Mode { MONTH, YEAR, CUSTOM }

    private var mode = Mode.MONTH
    private var monthCursor: Long = 0L
    private var yearCursor: Long = 0L

    private var rangeStart: Long = 0L
    private var rangeEnd: Long = Long.MAX_VALUE

    /** 按天列表：day -> 条目绑定（就地展开用） */
    private val dayRows = LinkedHashMap<Long, ItemStatsDayBinding>()

    private var customStart: Long = 0L
    private var customEnd: Long = 0L

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val dayFmt = DateTimeFormatter.ofPattern("MM-dd")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        // 整段兜异常：界面初始化再出问题也要能打开应用（异常进日志）
        runCatching {
        binding.rvLegend.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLegend.adapter = legendAdapter
        // 点分类排行 → 进入该分类的账单界面（新页面）
        legendAdapter.onClick = { cat -> openCategory(cat) }

        val now = System.currentTimeMillis()
        monthCursor = TimeUtil.monthStart(now)
        yearCursor = TimeUtil.yearStart(now)

        binding.toggleRange.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnMonth -> switchMode(Mode.MONTH)
                R.id.btnYear -> switchMode(Mode.YEAR)
                R.id.btnCustom -> switchMode(Mode.CUSTOM)
            }
        }
        binding.btnPrevMonth.setOnClickListener {
            monthCursor = TimeUtil.shiftMonth(monthCursor, -1)
            refresh()
        }
        binding.btnNextMonth.setOnClickListener {
            // 不允许切到未来月份
            if (TimeUtil.shiftMonth(monthCursor, 1) > currentMonthStart()) {
                toast("已经是最新的月份了")
                return@setOnClickListener
            }
            monthCursor = TimeUtil.shiftMonth(monthCursor, 1)
            refresh()
        }
        // 按年模式的年份切换（不允许未来年份）
        binding.btnPrevYear.setOnClickListener {
            yearCursor = TimeUtil.shiftMonth(yearCursor, -12)
            buildMonthChips()
            refresh()
        }
        binding.btnNextYear.setOnClickListener {
            if (TimeUtil.shiftMonth(yearCursor, 12) > currentMonthStart()) {
                toast("已经是最新的年份了")
                return@setOnClickListener
            }
            yearCursor = TimeUtil.shiftMonth(yearCursor, 12)
            buildMonthChips()
            refresh()
        }
        binding.btnStart.setOnClickListener { pickCustomDate(true) }
        binding.btnEnd.setOnClickListener { pickCustomDate(false) }
        binding.pieChart.onSliceSelected = { _, slice ->
            binding.tvPieHint.text = if (slice == null) {
                "点击扇区查看该分类金额"
            } else {
                String.format(Locale.US, "%s：¥%.2f", slice.label, slice.value)
            }
        }

        if (binding.toggleRange.checkedButtonId == View.NO_ID) binding.toggleRange.check(R.id.btnMonth)
        switchMode(Mode.MONTH)
        }.onFailure { AppLog.e("界面", it) }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        runCatching { refresh() }.onFailure { AppLog.e("统计", it) }
        runCatching { refreshDay() }.onFailure { AppLog.e("统计", it) }
        // 切主题重建后恢复滚动位置
        ScrollMemory.restore(requireContext(), "stats", _binding?.root)
    }

    override fun onPause() {
        super.onPause()
        ScrollMemory.save(requireContext(), "stats", _binding?.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------------- 模式与区间 ----------------

    private fun switchMode(newMode: Mode) {
        mode = newMode
        binding.rowMonthNav.visibility = if (newMode == Mode.MONTH) View.VISIBLE else View.GONE
        // 12 个月快捷入口现在跟着"按月"（按年模式用下面的年份切换）
        binding.cgYearMonths.visibility = if (newMode == Mode.MONTH) View.VISIBLE else View.GONE
        binding.rowYearNav.visibility = if (newMode == Mode.YEAR) View.VISIBLE else View.GONE
        // 按天查看只对"按月"有意义（按年/自定义下面的整月日历没意义）
        binding.cardDay.visibility = if (newMode == Mode.MONTH) View.VISIBLE else View.GONE
        binding.rowCustom.visibility = if (newMode == Mode.CUSTOM) View.VISIBLE else View.GONE
        if (newMode == Mode.CUSTOM && customStart == 0L) {
            val today = LocalDate.now()
            customStart = today.minusDays(29).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            customEnd = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            updateCustomButtons()
        }
        buildMonthChips()
        applyRange()
        refresh()
    }

    private fun applyRange() {
        when (mode) {
            Mode.MONTH -> {
                rangeStart = monthCursor
                rangeEnd = TimeUtil.shiftMonth(monthCursor, 1)
                binding.tvMonthLabel.text = TimeUtil.formatMonthTitle(monthCursor)
            }
            Mode.YEAR -> {
                rangeStart = yearCursor
                rangeEnd = TimeUtil.shiftMonth(yearCursor, 12)
                val year = Instant.ofEpochMilli(yearCursor).atZone(ZoneId.systemDefault()).toLocalDate().year
                binding.tvRange.text = "$year 年"
            }
            Mode.CUSTOM -> {
                rangeStart = customStart
                rangeEnd = customEnd
            }
        }
    }

    /** 当前月的起点（用于禁止切到未来月份 / 年份） */
    private fun currentMonthStart(): Long = TimeUtil.monthStart(System.currentTimeMillis())

    /**
     * **按月模式**下的 12 个月快捷入口（跟着当前月份的年份），点击即切到该月。
     * 未来月份置灰不可点 —— 还没到的月份没有数据，不该能选。
     */
    private fun buildMonthChips() {
        binding.cgYearMonths.removeAllViews()
        val anchor = if (mode == Mode.YEAR) yearCursor else monthCursor
        val year = Instant.ofEpochMilli(anchor).atZone(ZoneId.systemDefault()).toLocalDate().year
        val yearStart = TimeUtil.yearStart(anchor)
        val currentMonth = currentMonthStart()
        binding.tvYearLabel.text = "$year 年"
        for (i in 0 until 12) {
            val m = TimeUtil.shiftMonth(yearStart, i)
            val future = m > currentMonth
            val chip = Chip(requireContext()).apply {
                text = "${i + 1} 月"
                isCheckable = true
                isEnabled = !future
                alpha = if (future) 0.35f else 1f
                id = View.generateViewId()
                isChecked = (m == monthCursor)
            }
            chip.setOnClickListener {
                if (future) {
                    toast("还没有到 $year 年 ${i + 1} 月")
                    return@setOnClickListener
                }
                monthCursor = m
                binding.toggleRange.check(R.id.btnMonth)
                switchMode(Mode.MONTH)
                toast("已切换到 $year-${String.format(Locale.US, "%02d", i + 1)}")
            }
            binding.cgYearMonths.addView(chip)
        }
        binding.tvMonthHint.text = "$year 年"
    }

    private fun updateCustomButtons() {
        binding.btnStart.text = Instant.ofEpochMilli(customStart).atZone(ZoneId.systemDefault()).toLocalDate().format(dateFmt)
        binding.btnEnd.text = Instant.ofEpochMilli(customEnd).atZone(ZoneId.systemDefault()).toLocalDate().minusDays(1).format(dateFmt)
    }

    private fun pickCustomDate(isStart: Boolean) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setSelection(if (isStart) customStart else customEnd - 1)
            .build()
        picker.addOnPositiveButtonClickListener { utcMillis ->
            val day = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
            if (isStart) {
                customStart = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } else {
                customEnd = day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            updateCustomButtons()
            applyRange()
            refresh()
        }
        picker.show(parentFragmentManager, "custom_date")
    }


    // ---------------- 数据 ----------------

    private fun refresh() {
        applyRange()
        // ★ 按天查看必须跟着当前区间一起刷新（以前漏了这一句，
        //   所以切了月份下面的按天列表还是旧月份 —— 你反馈的正是这个）
        refreshDay()
        val app = requireActivity().application as App
        val s = rangeStart
        val e = rangeEnd
        app.post {
            val grouped = app.dao.rangeGrouped(s, e)
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                render(grouped, s, e)
            }
        }
    }

    private fun render(grouped: List<RecordDao.CatTotal>, s: Long, e: Long) {
        val ctx = requireContext()
        var expense = 0.0
        var income = 0.0
        grouped.forEach { if (it.type == RecordType.EXPENSE) expense += it.total else income += it.total }

        binding.tvTotalExpense.text = Money.yuan(ctx, expense)
        binding.tvTotalIncome.text = Money.yuan(ctx, income)
        binding.tvTotalBalance.text = Money.balanceYuan(ctx, income - expense)
        if (mode != Mode.YEAR) {
            binding.tvRange.text = "${TimeUtil.formatDayHeader(s)} ~ ${TimeUtil.formatDayHeader(e - 1)}"
        }

        val slices = grouped.filter { it.type == RecordType.EXPENSE }
            .map { PieChartView.Slice(it.category, it.total, Categories.bgColor(it.category)) }
        binding.pieChart.masked = Money.isMasked(ctx)
        binding.pieChart.centerTitle = "支出"
        binding.pieChart.centerSubtitle = if (expense > 0) "共 ¥" + Money.plain(expense) else ""
        binding.pieChart.setSlices(slices)

        legendAdapter.submit(grouped)
        binding.tvEmpty.visibility = if (grouped.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 按天查看：列出**当前区间**（按月就是本月）每一天的汇总，点某天展开当天明细。
     *
     * 以前这里是"单日 + 前后一天箭头"，跟月份切换没关系；现在跟着当前月份/年份/自定义区间走。
     */
    private fun refreshDay() {
        val app = requireActivity().application as App
        val s = rangeStart
        val e = rangeEnd
        app.post {
            val records = runCatching { app.dao.range(s, e) }.getOrDefault(emptyList())
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                renderDayList(records)
            }
        }
    }

    private fun renderDayList(records: List<Record>) {
        val ctx = requireContext()
        binding.llDayList.removeAllViews()
        dayRows.clear()

        binding.tvDayCardTitle.text = "按天查看 · ${TimeUtil.formatMonthTitle(monthCursor)}"
        // 空月份也要清空列表 + 收起展开（避免残留上个月的内容）
        if (records.isEmpty()) {
            binding.tvDaySummary.text = "这个月还没有账单"
            return
        }

        if (records.isEmpty()) {
            binding.tvDaySummary.text = "这个月还没有账单"
            return
        }

        val byDay = LinkedHashMap<Long, MutableList<Record>>()
        records.forEach { r ->
            val day = TimeUtil.dayStart(r.time)
            byDay.getOrPut(day) { ArrayList() }.add(r)
        }
        val days = byDay.keys.sortedDescending()
        var totalExpense = 0.0
        var totalIncome = 0.0
        byDay.values.flatten().forEach {
            if (it.type == RecordType.EXPENSE) totalExpense += it.amount else totalIncome += it.amount
        }
        binding.tvDaySummary.text = String.format(
            Locale.US, "共 %d 天 · %d 笔 · 支出 %s · 收入 %s",
            days.size, records.size, Money.yuan(ctx, totalExpense), Money.yuan(ctx, totalIncome)
        )

        days.forEach { day ->
            val list = byDay.getValue(day)
            var expense = 0.0
            var income = 0.0
            list.forEach { if (it.type == RecordType.EXPENSE) expense += it.amount else income += it.amount }

            val item = ItemStatsDayBinding.inflate(layoutInflater, binding.llDayList, false)
            item.tvDayDate.text = Instant.ofEpochMilli(day)
                .atZone(ZoneId.systemDefault()).toLocalDate()
                .format(DateTimeFormatter.ofPattern("M月d日"))
            item.tvDayWeek.text = "${TimeUtil.formatWeekday(day)} · ${list.size} 笔"
            item.tvDayExpense.text = "支出 " + Money.yuan(ctx, expense)
            item.tvDayExpense.visibility = if (expense > 0) View.VISIBLE else View.GONE
            item.tvDayIncome.text = "收入 " + Money.yuan(ctx, income)
            item.tvDayIncome.visibility = if (income > 0) View.VISIBLE else View.GONE
            item.rowDay.setOnClickListener { toggleDay(day, list) }
            binding.llDayList.addView(item.root)
            dayRows[day] = item
        }
    }

    /**
     * 就地展开/收起某一天的账单 —— 展开内容插在**这一条下面**（不再统一跑到最底部），
     * 同时把箭头翻转、把当天日期高亮，避免"不知道是哪天展开了"。
     */
    private fun toggleDay(day: Long, records: List<Record>) {
        val item = dayRows[day] ?: return
        val expand = item.llDayExpand
        val opening = expand.visibility != View.VISIBLE

        // 其它已展开的先收起（一次只看一天，列表不乱）
        dayRows.forEach { (d, other) ->
            if (d != day && other.llDayExpand.visibility == View.VISIBLE) {
                other.llDayExpand.visibility = View.GONE
                other.llDayExpand.removeAllViews()
                other.ivDayArrow.rotation = 0f
                other.tvDayDate.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            }
        }

        if (!opening) {
            expand.visibility = View.GONE
            expand.removeAllViews()
            item.ivDayArrow.rotation = 0f
            item.tvDayDate.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            return
        }

        expand.removeAllViews()
        expand.visibility = View.VISIBLE
        item.ivDayArrow.rotation = 180f
        item.tvDayDate.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
        renderRecordsInto(expand, records)
    }

    /** 渲染一组账单行（点开编辑、长按删除） */
    private fun renderRecordsInto(container: LinearLayout, records: List<Record>) {
        val ctx = requireContext()
        container.removeAllViews()
        records.sortedByDescending { it.time }.take(DAY_LIST_LIMIT).forEach { r ->
            val row = ItemRecordBinding.inflate(layoutInflater, container, false)
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
            row.root.setOnClickListener { startActivity(EditRecordActivity.intent(ctx, r.id)) }
            row.root.setOnLongClickListener { confirmDelete(r); true }
            container.addView(row.root)
        }
        if (records.size > DAY_LIST_LIMIT) {
            container.addView(TextView(ctx).apply {
                text = "仅显示前 $DAY_LIST_LIMIT 笔"
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(6), 0, dp(6))
            })
        }
    }
    private fun confirmDelete(r: Record) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除账单")
            .setMessage("将「${r.category} ${Money.plain(r.amount)}」移入回收站？可在回收站还原。")
            .setPositiveButton("移入回收站") { _, _ ->
                val app = requireActivity().application as App
                app.post {
                    app.dao.softDelete(r.id)
                    activity?.runOnUiThread {
                        refresh()
                        refreshDay()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }


    /** 进入该分类的账单界面（当前区间） */
    private fun openCategory(category: String) {
        startActivity(
            CategoryRecordsActivity.intent(requireContext(), category, rangeStart, rangeEnd)
        )
    }
    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val DAY_LIST_LIMIT = 100
    }
}
