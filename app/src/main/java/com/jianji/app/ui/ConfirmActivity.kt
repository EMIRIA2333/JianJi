package com.jianji.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jianji.app.App
import com.jianji.app.R
import com.jianji.app.core.Categories
import com.jianji.app.core.Record
import com.jianji.app.core.RecordType
import com.jianji.app.databinding.ActivityConfirmBinding
import com.jianji.app.util.CsvFormat
import com.jianji.app.util.RecordWriter
import com.jianji.app.util.TimeUtil
import java.util.Locale

/**
 * 确认录入页：
 * 1) 检测到疑似重复账单时，让用户选择「仍然录入 / 忽略」；
 * 2) 弱证据（无强关键词）时，让用户确认后再入库。
 */
class ConfirmActivity : BaseActivity() {

    private lateinit var binding: ActivityConfirmBinding

    private var type = RecordType.EXPENSE
    private var amount = 0.0
    private var merchant = ""
    private var time = 0L
    private var source = 0
    private var similar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        type = intent.getIntExtra(EXTRA_TYPE, RecordType.EXPENSE)
        amount = intent.getDoubleExtra(EXTRA_AMOUNT, 0.0)
        merchant = intent.getStringExtra(EXTRA_MERCHANT).orEmpty()
        time = intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis())
        source = intent.getIntExtra(EXTRA_SOURCE, 0)
        similar = intent.getBooleanExtra(EXTRA_SIMILAR, false)

        render()
        binding.btnConfirm.setOnClickListener { confirmInsert() }
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun render() {
        binding.tvTitle.text = if (similar) "检测到相似账单" else "待确认账单"
        val sign = if (type == RecordType.INCOME) "+" else "-"
        binding.tvAmount.text = String.format(Locale.US, "%s¥%.2f", sign, amount)
        binding.tvAmount.setTextColor(
            getColor(if (type == RecordType.INCOME) R.color.income_green else R.color.expense_red)
        )
        binding.tvType.text = if (type == RecordType.INCOME) "收入" else "支出"
        binding.tvMerchant.text = merchant.ifBlank { "未识别商户" }
        binding.tvTime.text = "时间：${TimeUtil.formatFull(time)}"
        binding.tvSource.text = "来源：${CsvFormat.sourceName(source)}"
        binding.tvWarn.text = if (similar) {
            "近期已有一笔相同金额的账单，可能为重复通知（如通知+页面各一次）。若确认为两笔独立交易，可选择继续录入。"
        } else {
            "该笔账单证据较弱（未出现「支付成功」等强关键词），请核对信息后确认。"
        }
        binding.btnConfirm.text = if (similar) "仍然录入" else "确认录入"
        // 分类预测
        val guess = Categories.guess(merchant, type == RecordType.INCOME)
        binding.tvCategory.text = "预计分类：$guess（录入后可长按删除重记）"
    }

    private fun confirmInsert() {
        if (similar) {
            MaterialAlertDialogBuilder(this)
                .setTitle("确定继续录入？")
                .setMessage("该金额与近期账单重复，继续录入可能出现两条相同金额的记录。")
                .setPositiveButton("仍要录入") { _, _ -> doInsert() }
                .setNegativeButton("再想想", null)
                .show()
        } else {
            doInsert()
        }
    }

    private fun doInsert() {
        val app = application as App
        val rec = Record(
            amount = amount,
            type = type,
            category = Categories.guess(merchant, type == RecordType.INCOME),
            note = merchant,
            source = source,
            time = time
        )
        // 用户明确选择录入：清掉去重窗口里的同款，避免后续事件再次弹确认
        app.deduper.remove(type, amount, merchant)
        app.post {
            RecordWriter.insert(this, rec)
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    "已录入：" + String.format(Locale.US, "%.2f", amount),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_AMOUNT = "amount"
        private const val EXTRA_MERCHANT = "merchant"
        private const val EXTRA_TIME = "time"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_SIMILAR = "similar"

        fun intent(
            c: Context,
            type: Int,
            amount: Double,
            merchant: String,
            time: Long,
            source: Int,
            similar: Boolean
        ): Intent = Intent(c, ConfirmActivity::class.java).apply {
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_AMOUNT, amount)
            putExtra(EXTRA_MERCHANT, merchant)
            putExtra(EXTRA_TIME, time)
            putExtra(EXTRA_SOURCE, source)
            putExtra(EXTRA_SIMILAR, similar)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }
}
