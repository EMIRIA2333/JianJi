package com.jianji.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.jianji.app.App
import com.jianji.app.R
import com.jianji.app.core.Categories
import com.jianji.app.core.Record
import com.jianji.app.core.RecordSource
import com.jianji.app.core.RecordType
import com.jianji.app.databinding.ActivityAddRecordBinding
import com.jianji.app.util.CategoryLearner
import com.jianji.app.util.CustomCategories
import com.jianji.app.util.RecordWriter
import com.jianji.app.util.TimeUtil
import java.util.Locale

class AddRecordActivity : BaseActivity() {

    private lateinit var binding: ActivityAddRecordBinding
    private var isIncome = false
    private var payMethod: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.toggleType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isIncome = checkedId == R.id.btnIncome
                renderCategories()
            }
        }
        renderCategories()
        renderPayMethods()
        binding.tvTimeNote.text = "记录时间：${TimeUtil.formatFull(System.currentTimeMillis())}"
        binding.btnSave.setOnClickListener { save() }
    }

    /** 支付方式 / 支出工具（可再次点击取消选择） */
    private fun renderPayMethods() {
        binding.cgPayMethods.removeAllViews()
        EditRecordActivity.COMMON_PAY_METHODS.forEach { name ->
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                id = View.generateViewId()
                isChecked = name == payMethod
                setOnClickListener {
                    payMethod = if (payMethod == name) "" else name
                    renderPayMethods()
                }
            }
            binding.cgPayMethods.addView(chip)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun renderCategories() {
        binding.cgCategories.removeAllViews()
        val base = if (isIncome) Categories.INCOME_LIST else Categories.EXPENSE_LIST
        // 内置分类 + 用户自定义分类
        val custom = if (isIncome) CustomCategories.income(this) else CustomCategories.expense(this)
        val cats = base + custom.filter { !base.contains(it) }
        cats.forEach { name ->
            val chip = Chip(this)
            chip.text = name
            chip.isCheckable = true
            chip.id = View.generateViewId()
            // 每个分类带专属图标，颜色与列表一致
            chip.chipIcon = ContextCompat.getDrawable(this, CategoryIcons.iconRes(name))
            chip.isChipIconVisible = true
            chip.chipIconTint = android.content.res.ColorStateList.valueOf(
                if (isIncome) getColor(R.color.income_green) else Categories.bgColor(name)
            )
            chip.chipStrokeWidth = 1f
            binding.cgCategories.addView(chip)
        }
        binding.cgCategories.check((binding.cgCategories.getChildAt(0) as Chip).id)
    }

    private fun save() {
        val amountText = binding.etAmount.text?.toString()?.trim().orEmpty()
        val amount = amountText.toDoubleOrNull()
        if (amount == null || amount <= 0.0 || amount > MAX_AMOUNT) {
            Toast.makeText(this, "请输入正确的金额", Toast.LENGTH_SHORT).show()
            return
        }
        val checkedId = binding.cgCategories.checkedChipId
        val category = if (checkedId == View.NO_ID) Categories.default(isIncome)
        else binding.cgCategories.findViewById<Chip>(checkedId)?.text?.toString() ?: Categories.default(isIncome)
        val merchant = binding.etMerchant.text?.toString()?.trim().orEmpty()
        val note = binding.etNote.text?.toString()?.trim().orEmpty()
        val tag = binding.etTag.text?.toString()?.trim().orEmpty()

        val app = application as App
        val rec = Record(
            amount = Math.round(amount * 100.0) / 100.0,
            type = if (isIncome) RecordType.INCOME else RecordType.EXPENSE,
            category = category,
            note = note,
            merchant = merchant,
            tag = tag,
            payMethod = payMethod,
            source = RecordSource.MANUAL,
            time = System.currentTimeMillis()
        )
        app.post {
            RecordWriter.insert(this, rec)
            // 学习：手动记一笔也是明确信号，之后相似账单自动套用这个分类
            CategoryLearner.learn(this, merchant, note, amount, category)
            runOnUiThread {
                Toast.makeText(this, "已保存：${rec.category} ${String.format(Locale.US, "%.2f", rec.amount)}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val MAX_AMOUNT = 1_000_000.0
    }
}
