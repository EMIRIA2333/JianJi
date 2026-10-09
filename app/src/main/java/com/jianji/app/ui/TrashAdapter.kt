package com.jianji.app.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jianji.app.R
import com.jianji.app.core.Categories
import com.jianji.app.core.Record
import com.jianji.app.core.RecordType
import com.jianji.app.databinding.ItemRecordBinding
import com.jianji.app.util.CsvFormat
import com.jianji.app.util.GlassHelper
import com.jianji.app.util.Money
import com.jianji.app.util.TimeUtil
import com.jianji.app.util.TrashPolicy

/** 回收站列表：显示删除时间与剩余保留天数，支持多选还原/彻底删除 */
class TrashAdapter(
    private val onToggle: (Record) -> Unit,
    private val onLongPress: (Record) -> Unit
) : RecyclerView.Adapter<TrashAdapter.VH>() {

    private val items = ArrayList<Record>()
    private val selected = HashSet<Long>()

    class VH(val b: ItemRecordBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<Record>, selectedIds: Set<Long>) {
        items.clear()
        items.addAll(list)
        selected.clear()
        selected.addAll(selectedIds)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val ctx = holder.b.root.context
        val isSelected = selected.contains(r.id)

        holder.b.ivCheck.visibility = View.VISIBLE
        holder.b.ivCheck.setImageResource(if (isSelected) R.drawable.ic_check_on else R.drawable.ic_check_off)
        holder.b.ivCheck.imageTintList = android.content.res.ColorStateList.valueOf(
            ctx.getColor(if (isSelected) R.color.primary else R.color.text_secondary)
        )
        holder.b.root.setBackgroundResource(
            GlassHelper.itemBgRes(ctx, isSelected)
        )

        holder.b.ivIcon.setImageResource(CategoryIcons.iconRes(r.category))
        holder.b.ivIcon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Categories.bgColor(r.category))
        }
        holder.b.tvTitle.text = buildString {
            append(r.category)
            if (r.note.isNotBlank()) append(" · ").append(r.note)
        }
        val remain = TrashPolicy.remainingDays(r.deletedAt, System.currentTimeMillis())
        holder.b.tvTime.text = buildString {
            append("原记录 ").append(TimeUtil.formatList(r.time))
            if (r.payMethod.isNotBlank()) append(" · ").append(r.payMethod)
            append(" · 删除于 ").append(TimeUtil.formatList(r.deletedAt))
            append(" · 剩余 ").append(remain).append(" 天")
            if (r.source != com.jianji.app.core.RecordSource.MANUAL) {
                append(" · ").append(CsvFormat.sourceName(r.source))
            }
        }
        holder.b.tvAmount.text = Money.signedYuan(ctx, r.type, r.amount)
        holder.b.tvAmount.setTextColor(
            ctx.getColor(if (r.type == RecordType.INCOME) R.color.income_green else R.color.expense_red)
        )
        holder.b.root.setOnClickListener { onToggle(r) }
        holder.b.root.setOnLongClickListener { onLongPress(r); true }
    }
}
