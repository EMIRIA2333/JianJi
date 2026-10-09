package com.jianji.app.ui

import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jianji.app.R
import com.jianji.app.databinding.DialogBudgetBinding
import com.jianji.app.util.BudgetPeriod
import com.jianji.app.util.Money
import com.jianji.app.util.Prefs
import com.jianji.app.util.TimeUtil
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 预算设置对话框：额度 + 周期（每月 / 自定义时间段）。
 * 明细页与「我的」页共用，避免逻辑重复。
 */
object BudgetDialog {

    private val dateFmt = DateTimeFormatter.ofPattern("MM-dd")

    fun show(fragment: Fragment, onSaved: () -> Unit) {
        val ctx = fragment.requireContext()
        val b = DialogBudgetBinding.inflate(fragment.layoutInflater)
        var type = Prefs.budgetPeriodType(ctx)
        var start = Prefs.budgetCustomStart(ctx)
        var end = Prefs.budgetCustomEnd(ctx)
        if (end <= start) {
            // 首次进入自定义：默认本月
            start = TimeUtil.monthStart(System.currentTimeMillis())
            end = TimeUtil.nextMonthStart(System.currentTimeMillis())
        }

        val cur = Prefs.monthlyBudget(ctx)
        if (cur > 0) b.etBudgetAmount.setText(String.format(Locale.US, "%.2f", cur))

        fun renderPeriod() {
            val custom = type == BudgetPeriod.TYPE_CUSTOM
            b.rowCustomPeriod.visibility = if (custom) View.VISIBLE else View.GONE
            b.togglePeriod.check(if (custom) R.id.btnPeriodCustom else R.id.btnPeriodMonth)
            if (custom) {
                b.btnPeriodStart.text = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
                    .toLocalDate().format(dateFmt)
                b.btnPeriodEnd.text = Instant.ofEpochMilli(end - 1).atZone(ZoneId.systemDefault())
                    .toLocalDate().format(dateFmt)
            }
        }
        renderPeriod()

        b.togglePeriod.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            type = if (checkedId == R.id.btnPeriodCustom) BudgetPeriod.TYPE_CUSTOM else BudgetPeriod.TYPE_MONTH
            renderPeriod()
        }
        b.btnPeriodStart.setOnClickListener {
            pickDate(fragment, start) { picked ->
                start = picked
                if (end <= start) end = TimeUtil.shiftMonth(start, 1)
                renderPeriod()
            }
        }
        b.btnPeriodEnd.setOnClickListener {
            pickDate(fragment, end - 1) { picked ->
                // 结束日期当天有效，所以存成「次日 0 点」
                end = TimeUtil.dayStart(picked) + DAY_MS
                if (end <= start) {
                    Toast.makeText(ctx, "结束日期需晚于开始日期", Toast.LENGTH_SHORT).show()
                    end = TimeUtil.shiftMonth(start, 1)
                }
                renderPeriod()
            }
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("预算与预警")
            .setView(b.root)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val amount = b.etBudgetAmount.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
            if (amount < 0) {
                Toast.makeText(ctx, "额度不能为负", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (type == BudgetPeriod.TYPE_CUSTOM && end <= start) {
                Toast.makeText(ctx, "自定义周期不合法，请重新选择日期", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.setMonthlyBudget(ctx, amount)
            Prefs.setBudgetPeriod(ctx, type, start, end)
            Prefs.clearBudgetWarnings(ctx)
            val label = if (amount <= 0) {
                "已关闭预算提醒"
            } else {
                "已设置 " + Money.plain(amount) + " 元 · " +
                    BudgetPeriod.describe(type, start, end)
            }
            Toast.makeText(ctx, label, Toast.LENGTH_LONG).show()
            dialog.dismiss()
            onSaved()
        }
    }

    private fun pickDate(fragment: Fragment, selection: Long, onPicked: (Long) -> Unit) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("选择日期")
            .setSelection(selection)
            .build()
        picker.addOnPositiveButtonClickListener { utcMillis ->
            val day = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
            onPicked(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        }
        picker.show(fragment.parentFragmentManager, "budget_date")
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
