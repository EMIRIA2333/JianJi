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
import com.jianji.app.databinding.ItemRecordBinding
import com.jianji.app.util.CsvFormat
import com.jianji.app.util.Money
import com.jianji.app.util.TimeUtil

/** 搜索结果列表（复用账单行样式） */
class SearchAdapter(
    private val onClick: (Record) -> Unit,
    private val onLongClick: (Record) -> Unit
) : RecyclerView.Adapter<SearchAdapter.VH>() {

    private val items = ArrayList<Record>()

    class VH(val b: ItemRecordBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<Record>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val ctx = holder.b.root.context
        holder.b.ivCheck.visibility = View.GONE
        holder.b.ivIcon.setImageResource(CategoryIcons.iconRes(r.category))
        val d = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Categories.bgColor(r.category))
        }
        holder.b.ivIcon.background = d
        holder.b.tvTitle.text = buildString {
            append(r.category)
            if (r.note.isNotBlank()) append(" · ").append(r.note)
        }
        holder.b.tvTime.text = buildString {
            append(TimeUtil.formatFull(r.time))
            if (r.payMethod.isNotBlank()) append(" · ").append(r.payMethod)
            if (r.tag.isNotBlank()) append("  #").append(r.tag)
            if (r.source != RecordSource.MANUAL) append(" · ").append(CsvFormat.sourceName(r.source))
        }
        holder.b.tvAmount.text = Money.signedYuan(ctx, r.type, r.amount)
        holder.b.tvAmount.setTextColor(
            ctx.getColor(if (r.type == RecordType.INCOME) R.color.income_green else R.color.expense_red)
        )
        holder.b.root.setOnClickListener { onClick(r) }
        holder.b.root.setOnLongClickListener { onLongClick(r); true }
    }
}
