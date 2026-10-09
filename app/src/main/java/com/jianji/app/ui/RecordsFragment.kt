package com.jianji.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jianji.app.App
import com.jianji.app.R
import com.jianji.app.core.Categories
import com.jianji.app.core.Record
import com.jianji.app.core.RecordType
import com.jianji.app.databinding.DialogBatchCategoryBinding
import com.jianji.app.databinding.DialogTagBinding
import com.jianji.app.databinding.FragmentRecordsBinding
import com.jianji.app.util.Budget
import com.jianji.app.util.Edits
import com.jianji.app.util.AppLog
import com.jianji.app.util.Money
import com.jianji.app.util.GlassHelper
import com.jianji.app.util.Prefs
import com.jianji.app.util.ScrollMemory
import com.jianji.app.util.RecordWriter
import com.jianji.app.util.TimeUtil
import com.jianji.app.view.BarChartView
import com.google.android.material.chip.Chip
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 明细页：头图 + 本月收支 + 预算进度 + 近 7 日支出柱状图 + 按日分组账单。
 * 支持：点击编辑、长按菜单（编辑 / 多选 / 删除）、多选批量改分类/时间/标签/删除、搜索入口。
 */
class RecordsFragment : Fragment(), RecordsAdapter.Host {

    private var _binding: FragmentRecordsBinding? = null
    private val binding get() = _binding!!
    private val adapter by lazy { RecordsAdapter(this) }

    /** 列表条目大致高度（dp）：柱状图跳转时用于第一步估算，第二步会用真实高度精修 */
    private val ITEM_APPROX_DP = 72

    /** 0 = 本月，-1 = 上月，1 = 下月 */
    private var monthOffset = 0

    /** 柱状图当前显示的类型：false=支出（默认） true=收入 */
    private var chartIsIncome = false
    private var weekRecordsCache: List<Record> = emptyList()
    private var weekStartCache: Long = 0L

    private val selected = LinkedHashSet<Long>()
    private var selectionMode = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRecordsBinding.inflate(inflater, container, false)
        // 整段兜异常：界面初始化再出问题也要能打开应用（异常进日志，便于排查）
        runCatching {
            binding.rvRecords.layoutManager = LinearLayoutManager(requireContext())
            binding.rvRecords.adapter = adapter
            // 切换主题会重建界面：恢复上次看的月份与列表位置，避免"回到最上方"
            val ctx = requireContext()
            monthOffset = Prefs.recordsMonthOffset(ctx)
            binding.btnBatchCancel.setOnClickListener { exitSelection() }
            binding.btnBatchCategory.setOnClickListener { showBatchCategoryDialog() }
            binding.btnBatchTime.setOnClickListener { showBatchTimePicker() }
            binding.btnBatchTag.setOnClickListener { showBatchTagDialog() }
            binding.btnBatchDelete.setOnClickListener { confirmBatchDelete() }
            // 「回到最上方」按钮在 MainActivity 里（与「记一笔」同一父容器，保证左右对齐）；
            // 这里只负责滚动位置变化时通知它显示/隐藏
            binding.rvRecords.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val far = ((rv.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0) > 2
                    (activity as? MainActivity)?.setTopFabVisible(far)
                }
            })
        }.onFailure { AppLog.e("界面", it) }
        return binding.root
    }

    /** 供 MainActivity 的「回到最上方」按钮调用 */
    fun scrollToTop() {
        runCatching {
            binding.rvRecords.stopScroll()
            (binding.rvRecords.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0)
        }
    }

    override fun onResume() {
        super.onResume()
        // 毛玻璃开关切换后，让列表头部的背景图重新应用一次（不做 Activity 重建）
        BannerBackgrounds.onChanged = {
            if (_binding != null) runCatching { adapter.notifyItemChanged(0) }
        }
        load()
    }

    override fun onPause() {
        super.onPause()
        BannerBackgrounds.onChanged = null
        // 记住当前月份与滚动位置（切主题/重建后原地恢复）
        runCatching {
            val ctx = requireContext()
            Prefs.setRecordsMonthOffset(ctx, monthOffset)
            ScrollMemory.save(ctx, "records", binding.rvRecords)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------------- 数据加载 ----------------

    private fun load() {
        val app = requireActivity().application as App
        val offset = monthOffset
        app.post {
            val now = System.currentTimeMillis()
            val monthStart = TimeUtil.shiftMonth(TimeUtil.monthStart(now), offset)
            val monthEnd = TimeUtil.shiftMonth(monthStart, 1)
            val monthRecords = app.dao.range(monthStart, monthEnd)

            val todayStart = TimeUtil.dayStart(now)
            val weekStart = TimeUtil.dayStart(todayStart - 6 * DAY_MS)
            val weekRecords = app.dao.range(weekStart, todayStart + DAY_MS)
            weekRecordsCache = weekRecords
            weekStartCache = weekStart

            val rows = buildRows(monthRecords)
            var expense = 0.0
            var income = 0.0
            monthRecords.forEach { if (it.type == RecordType.EXPENSE) expense += it.amount else income += it.amount }
            val bars = buildBars(weekRecords, weekStart)
            val budgetRange = Prefs.budgetRange(requireContext())
            val budgetSpent = app.dao.expenseSum(budgetRange.start, budgetRange.end)
            val budgetStatus = Budget.status(budgetSpent, Prefs.monthlyBudget(requireContext()))
            val periodLabel = if (Prefs.budgetPeriodType(requireContext()) == com.jianji.app.util.BudgetPeriod.TYPE_CUSTOM) {
                "自定义 " + budgetRange.label()
            } else {
                "每月 " + budgetRange.label()
            }

            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                adapter.monthTitle = TimeUtil.formatMonthTitle(monthStart)
                adapter.monthExpense = expense
                adapter.monthIncome = income
                adapter.balance = income - expense
                adapter.bars = bars
                adapter.chartTotal = bars.sumOf { it.value }
                adapter.chartIsIncome = chartIsIncome
                adapter.budget = budgetStatus
                adapter.budgetPeriodLabel = periodLabel
                adapter.submit(rows)
                adapter.setSelection(selectionMode, selected)
                // 恢复上次的滚动位置（切主题重建后不回到最上方）
                val saved = Prefs.scrollY(requireContext(), "records")
                if (saved > 0) {
                    binding.rvRecords.post {
                        runCatching {
                            (binding.rvRecords.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(saved, 0)
                        }
                    }
                }
            }
        }
    }

    private fun buildRows(records: List<Record>): List<RecordsAdapter.Row> {
        val byDay = LinkedHashMap<Long, MutableList<Record>>()
        records.forEach { r ->
            val day = TimeUtil.dayStart(r.time)
            byDay.getOrPut(day) { ArrayList() }.add(r)
        }
        val out = ArrayList<RecordsAdapter.Row>()
        byDay.entries.sortedByDescending { it.key }.forEach { (day, list) ->
            var expense = 0.0
            var income = 0.0
            list.forEach { if (it.type == RecordType.EXPENSE) expense += it.amount else income += it.amount }
            out.add(RecordsAdapter.Row.Header(day, expense, income))
            list.sortedByDescending { it.time }.forEach { out.add(RecordsAdapter.Row.Rec(it)) }
        }
        return out
    }

    private fun buildBars(weekRecords: List<Record>, weekStart: Long): List<BarChartView.Bar> {
        val sums = HashMap<Long, Double>()
        val want = if (chartIsIncome) RecordType.INCOME else RecordType.EXPENSE
        weekRecords.forEach { r ->
            if (r.type == want) {
                val day = TimeUtil.dayStart(r.time)
                sums[day] = (sums[day] ?: 0.0) + r.amount
            }
        }
        return (0 until 7).map { i ->
            val day = TimeUtil.dayStart(weekStart + i * DAY_MS)
            // key 存当天的起始毫秒：短按/长按要据此跳转与查询
            BarChartView.Bar(TimeUtil.weekdayShort(day), sums[day] ?: 0.0, day.toString())
        }
    }


    /** 点柱状图标题：支出 ⇄ 收入 */
    override fun onToggleChartType() {
        chartIsIncome = !chartIsIncome
        // 重算 7 天柱（已缓存的周数据，无需再查库）
        if (weekStartCache > 0) {
            adapter.bars = buildBars(weekRecordsCache, weekStartCache)
            adapter.chartTotal = adapter.bars.sumOf { it.value }
            adapter.notifyItemChanged(2)
        } else {
            load()
        }
        toast(if (chartIsIncome) "已切换为「最近 7 日收入」" else "已切换为「最近 7 日支出」")
    }
    // ---------------- 柱状图交互：短按跳当天 / 长按看明细 ----------------

    /**
     * 短按柱子：跳到**当天的最后一条记录**，并让这条记录**停在屏幕中间**。
     *
     * 之前是滚到"当天的分组标题"，标题贴顶、看不见记录 —— 你要的是把记录本身放到屏幕正中。
     * 实现：找到该天在列表里的**最后一条**记录行，用自定义 SmoothScroller 让它居中。
     */
    override fun onChartBarTap(key: String, value: Double) {
        val day = key.toLongOrNull() ?: return
        // 该天最后一条记录（列表按时间倒序，所以"最后一条"= 当天列表末尾那条）
        val pos = adapter.currentRows.indexOfLast {
            it is RecordsAdapter.Row.Rec && TimeUtil.dayStart(it.record.time) == day
        }
        val label = TimeUtil.formatWeekdayLabel(day)
        if (pos < 0) {
            toast("$label 没有记录")
            return
        }
        centerItem(pos)
        val (expense, income, count) = dayTotals(day)
        toast(
            buildString {
                append(label).append("：")
                if (expense > 0) append("支出 ¥").append(Money.plain(expense)).append("　")
                if (income > 0) append("收入 ¥").append(Money.plain(income)).append("　")
                append(count).append(" 笔")
            }
        )
    }

    /**
     * 把第 position 项**立即**放到屏幕垂直中间。
     *
     * 为什么不用平滑滚动：目标可能跨十几天的记录，平滑滚动要滚很久（"跳转太慢"就是这个原因，
     * 而且我上一版把速度参数写成了标准的 ~5 倍慢）。现在两步定位：
     * ① 按条目大致高度估算偏移，直接跳过去；② 布局完成后用该项**真实高度**再精修一次。
     * 两帧内到位，没有等待感。
     */
    private fun centerItem(position: Int) {
        val rv = binding.rvRecords
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        runCatching {
            val approx = (resources.displayMetrics.density * ITEM_APPROX_DP).toInt()
            lm.scrollToPositionWithOffset(position, ((rv.height - approx) / 2).coerceAtLeast(0))
            rv.post {
                runCatching {
                    val child = lm.findViewByPosition(position) ?: return@runCatching
                    lm.scrollToPositionWithOffset(
                        position,
                        ((rv.height - child.height) / 2).coerceAtLeast(0)
                    )
                }
            }
        }
    }

    /** 长按柱子：当天明细（时间 / 分类 / 商户 / 金额），可直接跳过去 */
    override fun onChartBarLongPress(key: String, value: Double) {
        val day = key.toLongOrNull() ?: return
        val list = adapter.currentRows.filterIsInstance<RecordsAdapter.Row.Rec>()
            .map { it.record }
            .filter { TimeUtil.dayStart(it.time) == day }
            .sortedByDescending { it.time }
        val (expense, income, _) = dayTotals(day)
        val label = TimeUtil.formatWeekdayLabel(day)
        if (list.isEmpty()) {
            toast("$label 没有记录")
            return
        }
        val body = buildString {
            appendLine("$label　支出 ¥${Money.plain(expense)}　收入 ¥${Money.plain(income)}")
            appendLine("共 ${list.size} 笔")
            appendLine("————————————")
            list.take(20).forEach { r ->
                val sign = if (r.type == RecordType.INCOME) "+" else "-"
                append(TimeUtil.formatList(r.time)).append("　")
                    .append(r.category)
                if (r.displayName.isNotBlank()) append("　").append(r.displayName)
                append("　").append(sign).append("¥").append(Money.plain(r.amount))
                appendLine()
            }
            if (list.size > 20) appendLine("…另有 ${list.size - 20} 笔")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("$label 明细")
            .setMessage(body)
            .setPositiveButton("跳到列表") { _, _ -> onChartBarTap(key, value) }
            .setNeutralButton("复制") { _, _ ->
                val cm = requireContext()
                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("简记 $label", body))
                toast("已复制")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 某天的支出 / 收入 / 笔数 */
    private fun dayTotals(day: Long): Triple<Double, Double, Int> {
        var expense = 0.0
        var income = 0.0
        var count = 0
        adapter.currentRows.filterIsInstance<RecordsAdapter.Row.Rec>().forEach { row ->
            val r = row.record
            if (TimeUtil.dayStart(r.time) == day) {
                count++
                if (r.type == RecordType.INCOME) income += r.amount else expense += r.amount
            }
        }
        return Triple(expense, income, count)
    }

    // ---------------- 单选交互 ----------------

    override fun onRecordClick(r: Record) {
        if (selectionMode) {
            toggleSelection(r.id)
        } else {
            startActivity(EditRecordActivity.intent(requireContext(), r.id))
        }
    }

    override fun onRecordLongPress(r: Record) {
        if (selectionMode) {
            toggleSelection(r.id)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${r.category} " + Money.plain(r.amount))
            .setItems(arrayOf("编辑这条", "多选批量操作", "删除这条")) { _, which ->
                when (which) {
                    0 -> startActivity(EditRecordActivity.intent(requireContext(), r.id))
                    1 -> enterSelection(r.id)
                    else -> confirmDelete(r)
                }
            }
            .show()
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
                        toast("已移入回收站")
                        load()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 多选与批量操作 ----------------

    private fun enterSelection(id: Long) {
        selectionMode = true
        selected.clear()
        selected.add(id)
        refreshSelectionUi()
    }

    private fun toggleSelection(id: Long) {
        if (!selected.remove(id)) selected.add(id)
        if (selected.isEmpty()) exitSelection() else refreshSelectionUi()
    }

    private fun exitSelection() {
        selectionMode = false
        selected.clear()
        refreshSelectionUi()
    }

    private fun refreshSelectionUi() {
        binding.batchBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        binding.tvBatchCount.text = "已选 ${selected.size} 项"
        (activity as? MainActivity)?.setFabVisible(!selectionMode)
        adapter.setSelection(selectionMode, selected)
    }

    private fun showBatchCategoryDialog() {
        if (selected.isEmpty()) return
        val dialogBinding = DialogBatchCategoryBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("批量修改分类（已选 ${selected.size} 项）")
            .setView(dialogBinding.root)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()

        fun addChips(group: com.google.android.material.chip.ChipGroup, names: List<String>, isIncome: Boolean) {
            names.forEach { name ->
                val chip = Chip(requireContext()).apply {
                    text = name
                    isCheckable = true
                    id = View.generateViewId()
                    chipIcon = androidx.core.content.ContextCompat.getDrawable(requireContext(), CategoryIcons.iconRes(name))
                    isChipIconVisible = true
                    chipIconTint = android.content.res.ColorStateList.valueOf(Categories.bgColor(name))
                    setOnClickListener {
                        applyBatchCategory(name, if (isIncome) RecordType.INCOME else RecordType.EXPENSE)
                        dialog.dismiss()
                    }
                }
                group.addView(chip)
            }
        }
        addChips(dialogBinding.cgExpense, Categories.EXPENSE_LIST, false)
        addChips(dialogBinding.cgIncome, Categories.INCOME_LIST, true)
    }

    private fun applyBatchCategory(category: String, type: Int) {
        val ids = selected.toList()
        val app = requireActivity().application as App
        app.post {
            val n = app.dao.updateCategory(ids, category, type)
            activity?.runOnUiThread {
                toast("已把 $n 条记录改为「$category」")
                exitSelection()
                load()
            }
        }
    }

    private fun showBatchTimePicker() {
        if (selected.isEmpty()) return
        val picker = MaterialDatePicker.Builder.datePicker().setTitleText("选择新的日期（保留原时刻）").build()
        picker.addOnPositiveButtonClickListener { utcMillis ->
            val day = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
            val dayStart = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val ids = selected.toList()
            val app = requireActivity().application as App
            app.post {
                val records = app.dao.byIds(ids).map { Edits.shiftTimeToDay(it.time, dayStart).let { t -> it.copy(time = t) } }
                val n = RecordWriter.updateAll(requireContext(), records)
                activity?.runOnUiThread {
                    toast("已修改 $n 条记录的时间")
                    exitSelection()
                    load()
                }
            }
        }
        picker.show(parentFragmentManager, "batch_date")
    }

    private fun showBatchTagDialog() {
        if (selected.isEmpty()) return
        val dialogBinding = DialogTagBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("批量加标签（已选 ${selected.size} 项）")
            .setView(dialogBinding.root)
            .setPositiveButton("添加", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val tag = dialogBinding.etTagInput.text?.toString()?.trim().orEmpty()
            if (tag.isEmpty()) {
                toast("请输入标签内容")
                return@setOnClickListener
            }
            val ids = selected.toList()
            val app = requireActivity().application as App
            app.post {
                val n = app.dao.updateTag(ids, tag)
                activity?.runOnUiThread {
                    toast("已为 $n 条记录添加标签 #$tag")
                    dialog.dismiss()
                    exitSelection()
                    load()
                }
            }
        }
    }

    private fun confirmBatchDelete() {
        if (selected.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("批量删除")
            .setMessage("将选中的 ${selected.size} 条记录移入回收站（可在回收站还原）。")
            .setPositiveButton("移入回收站") { _, _ ->
                val ids = selected.toList()
                val app = requireActivity().application as App
                app.post {
                    val n = app.dao.softDeleteMany(ids)
                    activity?.runOnUiThread {
                        toast("已移入回收站 $n 条")
                        exitSelection()
                        load()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 导航与预算 ----------------

    override fun onPrevMonth() {
        monthOffset -= 1
        load()
    }

    override fun onNextMonth() {
        monthOffset += 1
        load()
    }

    override fun onOpenStats() {
        (activity as? MainActivity)?.goToStats()
    }

    override fun onOpenSettings() {
        (activity as? MainActivity)?.goToProfile()
    }

    override fun onOpenSearch() {
        startActivity(Intent(requireContext(), SearchActivity::class.java))
    }

    override fun onOpenBudget() {
        BudgetDialog.show(this) { load() }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
