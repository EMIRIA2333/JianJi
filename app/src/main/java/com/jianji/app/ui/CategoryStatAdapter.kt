package com.jianji.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.jianji.app.R
import com.jianji.app.core.Categories
import com.jianji.app.core.RecordType
import com.jianji.app.db.RecordDao.CatTotal
import java.util.Locale

/** 统计页的分类占比列表 */
class CategoryStatAdapter : RecyclerView.Adapter<CategoryStatAdapter.VH>() {

    /** 点某一分类 → 查看该分类的具体账单 */
    var onClick: ((String) -> Unit)? = null

    private val items = ArrayList<CatTotal>()
    private var expenseTotal = 0.0
    private var incomeTotal = 0.0

    class VH(val b: com.jianji.app.databinding.ItemCategoryStatBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<CatTotal>) {
        items.clear()
        items.addAll(list)
        // 占比 = 该分类金额 / 同类型合计（与扇形图口径保持一致）
        expenseTotal = list.filter { it.type == RecordType.EXPENSE }.sumOf { it.total }
        incomeTotal = list.filter { it.type == RecordType.INCOME }.sumOf { it.total }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(com.jianji.app.databinding.ItemCategoryStatBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val isIncome = item.type == RecordType.INCOME
        val typeTotal = if (isIncome) incomeTotal else expenseTotal
        val ratio = if (typeTotal > 0) (item.total / typeTotal).coerceIn(0.0, 1.0) else 0.0

        val ctx = holder.b.root.context

        // 整行可点：进该分类的账单
        holder.b.root.setOnClickListener { onClick?.invoke(item.category) }
        holder.b.root.isClickable = true
        // 用主题自带的点击涟漪（原来是系统 list_selector，在深色下变成一大块黄色 ✗）
        val tv = android.util.TypedValue()
        if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true) && tv.resourceId != 0) {
            holder.b.root.setBackgroundResource(tv.resourceId)
        } else {
            holder.b.root.background = null
        }
        holder.b.ivIcon.setImageResource(CategoryIcons.iconRes(item.category))
        holder.b.ivIcon.background = circleBg(ctx, Categories.bgColor(item.category))
        holder.b.tvCategory.text = item.category
        holder.b.tvAmount.text = String.format(Locale.US, "¥%.2f", item.total)
        holder.b.tvAmount.setTextColor(ctx.getColor(if (isIncome) R.color.income_green else R.color.expense_red))
        holder.b.tvPercent.text = String.format(Locale.US, "%.0f%%", ratio * 100)
        holder.b.bar.progress = (ratio * 100).toInt()
        holder.b.bar.setIndicatorColor(
            ctx.getColor(if (isIncome) R.color.income_green else R.color.expense_red)
        )
    }

    private fun circleBg(ctx: android.content.Context, color: Int): android.graphics.drawable.Drawable {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.OVAL
        d.setColor(color)
        return d
    }
}
