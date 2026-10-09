package com.jianji.app.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jianji.app.R
import com.jianji.app.core.Categories
import com.jianji.app.core.Record
import com.jianji.app.core.RecordSource
import com.jianji.app.core.RecordType
import com.jianji.app.databinding.ItemDateHeaderBinding
import com.jianji.app.databinding.ItemEmptyBinding
import com.jianji.app.databinding.ItemRecordBinding
import com.jianji.app.databinding.ItemRecordsBannerBinding
import com.jianji.app.databinding.ItemRecordsBudgetBinding
import com.jianji.app.databinding.ItemRecordsChartBinding
import com.jianji.app.util.Budget
import com.jianji.app.util.CsvFormat
import com.jianji.app.util.GlassHelper
import com.jianji.app.util.Money
import com.jianji.app.util.TimeUtil
import com.jianji.app.view.BarChartView

/**
 * 明细页列表：头图 banner + 近 7 日柱状图 + 预算卡 + 按日分组的账单。
 * 支持多选模式（长按触发），条目带入场动画。
 */
class RecordsAdapter(private val host: Host) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Host {
        fun onRecordClick(r: Record)
        fun onRecordLongPress(r: Record)
        fun onPrevMonth()
        fun onNextMonth()
        fun onOpenStats()
        fun onOpenSettings()
        fun onOpenSearch()
        fun onOpenBudget()

        /** 短按柱状图某一天（key = 当天 0 点毫秒）→ 跳到当天数据 */
        fun onChartBarTap(key: String, value: Double)

        /** 长按柱状图某一天 → 看当天明细 */
        fun onChartBarLongPress(key: String, value: Double)

        /** 点标题切换柱状图类型：支出 ⇄ 收入 */
        fun onToggleChartType()
    }

    sealed class Row {
        class Header(val dayStart: Long, val expense: Double, val income: Double) : Row()
        class Rec(val record: Record) : Row()
    }

    private val rows = ArrayList<Row>()

    var monthTitle: String = ""
    var monthExpense = 0.0
    var monthIncome = 0.0
    var balance = 0.0
    var bars: List<BarChartView.Bar> = emptyList()

    /** 当前列表数据（供柱状图交互查当天明细） */
    val currentRows: List<Row> get() = rows
    var chartTotal = 0.0
    /** 柱状图当前是收入还是支出（由 Host 控制，用于标题文案） */
    var chartIsIncome = false
    var budget: Budget.Status = Budget.status(0.0, 0.0)
    var budgetPeriodLabel: String = ""

    private var selectionMode = false
    private val selectedIds = HashSet<Long>()

    fun submit(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    fun setSelection(mode: Boolean, ids: Set<Long>) {
        selectionMode = mode
        selectedIds.clear()
        selectedIds.addAll(ids)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (rows.isEmpty()) 4 else rows.size + 3

    override fun getItemViewType(position: Int): Int = when {
        position == 0 -> TYPE_BANNER
        position == 1 -> TYPE_CHART
        position == 2 -> TYPE_BUDGET
        rows.isEmpty() -> TYPE_EMPTY
        rows[position - 3] is Row.Header -> TYPE_HEADER
        else -> TYPE_RECORD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_BANNER -> BannerVH(ItemRecordsBannerBinding.inflate(inflater, parent, false))
            TYPE_CHART -> ChartVH(ItemRecordsChartBinding.inflate(inflater, parent, false))
            TYPE_BUDGET -> BudgetVH(ItemRecordsBudgetBinding.inflate(inflater, parent, false))
            TYPE_HEADER -> HeaderVH(ItemDateHeaderBinding.inflate(inflater, parent, false))
            TYPE_EMPTY -> EmptyVH(ItemEmptyBinding.inflate(inflater, parent, false))
            else -> RecordVH(ItemRecordBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ctx = holder.itemView.context
        when (holder) {
            is BannerVH -> {
                BannerBackgrounds.apply(ctx, holder.b.ivBanner)
                holder.b.tvMonthTitle.text = monthTitle
                holder.b.tvMonthExpense.text = Money.balanceYuan(ctx, monthExpense)
                holder.b.tvMonthIncome.text = "月收入 " + Money.balanceYuan(ctx, monthIncome)
                holder.b.tvMonthBalance.text = "本月结余 " + Money.balanceYuan(ctx, balance)
                holder.b.btnPrevMonth.setOnClickListener { host.onPrevMonth() }
                holder.b.btnNextMonth.setOnClickListener { host.onNextMonth() }
                holder.b.btnGoStats.setOnClickListener { host.onOpenStats() }
                holder.b.btnGoSettings.setOnClickListener { host.onOpenSettings() }
                holder.b.btnGoSearch.setOnClickListener { host.onOpenSearch() }
            }
            is ChartVH -> {
                holder.b.tvChartTotal.text = "总计 " + Money.yuan(ctx, chartTotal)
                holder.b.barChart.setBars(
                    bars,
                    ctx.getColor(R.color.expense_red),
                    ctx.getColor(R.color.bubble_bg)
                )
                // 短按柱子 = 跳到当天数据；长按柱子 = 看当天明细
                holder.b.barChart.onBarClick = { _, bar -> host.onChartBarTap(bar.key, bar.value) }
                holder.b.barChart.onBarLongClick = { _, bar -> host.onChartBarLongPress(bar.key, bar.value) }
                // 点标题切换「支出 ⇄ 收入」
                holder.b.tvChartTitle.text = if (chartIsIncome) "最近 7 日收入 ⇄" else "最近 7 日支出 ⇄"
                holder.b.tvChartTitle.setOnClickListener { host.onToggleChartType() }
                holder.b.barChart.emptyText = if (chartIsIncome) "近 7 日暂无收入" else "近 7 日暂无支出"
            }
            is BudgetVH -> {
                val st = budget
                holder.b.budgetRoot.setOnClickListener { host.onOpenBudget() }
                holder.b.tvBudgetPeriod.text = budgetPeriodLabel
                if (!st.hasBudget) {
                    holder.b.budgetBar.progress = 0
                    holder.b.tvBudgetLeft.text = "未设置预算额度，点击设置"
                    holder.b.tvBudgetTotal.text = ""
                    holder.b.tvBudgetHint.text = "点击设置"
                    holder.b.tvBudgetPeriod.text = "支持每月 / 自定义时间段"
                } else {
                    holder.b.budgetBar.progress = st.percent
                    holder.b.budgetBar.setIndicatorColor(
                        ctx.getColor(
                            when (st.level) {
                                Budget.LEVEL_OVER -> R.color.expense_red
                                Budget.LEVEL_NEAR -> R.color.accent_orange
                                else -> R.color.primary
                            }
                        )
                    )
                    holder.b.tvBudgetLeft.text = if (st.isOver) {
                        "已超支 " + Money.plain(-st.remaining) + " 元"
                    } else {
                        "剩余 " + Money.plain(st.remaining) + " 元"
                    }
                    holder.b.tvBudgetTotal.text = "总额 " + Money.plain(st.budget)
                    holder.b.tvBudgetHint.text = when (st.level) {
                        Budget.LEVEL_OVER -> "已超预算"
                        Budget.LEVEL_NEAR -> "接近预算"
                        else -> "已用 " + st.percent + "%"
                    }
                }
            }
            is HeaderVH -> {
                val row = rows[position - 3] as Row.Header
                holder.b.tvDayTitle.text = TimeUtil.formatDayHeader(row.dayStart)
                holder.b.tvDayWeek.text = TimeUtil.formatWeekdayLabel(row.dayStart)
                val summary = buildString {
                    if (row.expense > 0) append("支 ").append(Money.yuan(ctx, row.expense))
                    if (row.income > 0) {
                        if (isNotEmpty()) append("  ")
                        append("收 ").append(Money.yuan(ctx, row.income))
                    }
                }.ifEmpty { "无收支" }
                holder.b.tvDaySummary.text = summary
            }
            is RecordVH -> {
                val r = (rows[position - 3] as Row.Rec).record
                val isSelected = selectedIds.contains(r.id)
                holder.b.ivCheck.visibility = if (selectionMode) View.VISIBLE else View.GONE
                holder.b.ivCheck.setImageResource(if (isSelected) R.drawable.ic_check_on else R.drawable.ic_check_off)
                holder.b.ivCheck.imageTintList = android.content.res.ColorStateList.valueOf(
                    ctx.getColor(if (isSelected) R.color.primary else R.color.text_secondary)
                )
                holder.b.root.setBackgroundResource(
                    GlassHelper.itemBgRes(ctx, isSelected)
                )
                holder.b.ivIcon.setImageResource(CategoryIcons.iconRes(r.category))
                holder.b.ivIcon.background = circleBg(Categories.bgColor(r.category))
                holder.b.tvTitle.text = buildString {
                    append(r.category)
                    // 商户名称：识别到的或手填的；老数据回退到备注
                    val name = r.displayName
                    if (name.isNotBlank()) append(" · ").append(name)
                    // 备注与商户不同名时再单独显示一次
                    if (r.note.isNotBlank() && r.merchant.isNotBlank() && r.note != r.merchant) {
                        append("（").append(r.note).append("）")
                    }
                }
                holder.b.tvTime.text = buildString {
                    append(TimeUtil.formatList(r.time))
                    if (r.payMethod.isNotBlank()) append(" · ").append(r.payMethod)
                    if (r.tag.isNotBlank()) append("  #").append(r.tag)
                    if (r.imagePath.isNotBlank()) append("  📷")
                    if (r.source != RecordSource.MANUAL) append(" · ").append(CsvFormat.sourceName(r.source))
                }
                holder.b.tvAmount.text = Money.signedYuan(ctx, r.type, r.amount)
                holder.b.tvAmount.setTextColor(
                    ctx.getColor(if (r.type == RecordType.INCOME) R.color.income_green else R.color.expense_red)
                )
                holder.b.root.setOnClickListener { host.onRecordClick(r) }
                holder.b.root.setOnLongClickListener { host.onRecordLongPress(r); true }
                if (!selectionMode) {
                    holder.b.root.alpha = 0f
                    holder.b.root.translationY = 26f
                    holder.b.root.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setStartDelay((position % 8) * 18L)
                        .setDuration(260L)
                        .start()
                } else {
                    holder.b.root.alpha = 1f
                    holder.b.root.translationY = 0f
                }
            }
        }
    }

    private fun circleBg(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        return d
    }

    class BannerVH(val b: ItemRecordsBannerBinding) : RecyclerView.ViewHolder(b.root)
    class ChartVH(val b: ItemRecordsChartBinding) : RecyclerView.ViewHolder(b.root)
    class BudgetVH(val b: ItemRecordsBudgetBinding) : RecyclerView.ViewHolder(b.root)
    class HeaderVH(val b: ItemDateHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class RecordVH(val b: ItemRecordBinding) : RecyclerView.ViewHolder(b.root)
    class EmptyVH(val b: ItemEmptyBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        private const val TYPE_BANNER = 0
        private const val TYPE_CHART = 1
        private const val TYPE_BUDGET = 2
        private const val TYPE_HEADER = 3
        private const val TYPE_RECORD = 4
        private const val TYPE_EMPTY = 5
    }
}
