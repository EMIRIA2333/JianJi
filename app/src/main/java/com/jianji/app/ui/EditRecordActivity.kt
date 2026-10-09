package com.jianji.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.jianji.app.App
import com.jianji.app.R
import com.jianji.app.core.Categories
import com.jianji.app.core.Record
import com.jianji.app.core.RecordSource
import com.jianji.app.core.RecordType
import com.jianji.app.databinding.ActivityEditRecordBinding
import com.jianji.app.util.CategoryLearner
import com.jianji.app.util.CustomCategories
import com.jianji.app.util.CsvFormat
import com.jianji.app.util.ImageStore
import com.jianji.app.util.Edits
import com.jianji.app.util.Notifier
import com.jianji.app.util.PendingRecord
import com.jianji.app.util.RecordWriter
import com.jianji.app.util.TimeUtil
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 账单编辑 / 确认页，两种模式：
 *  1. 编辑已有账单（传入 record id）：改金额、方向、分类、备注、标签、日期时间，可删除；
 *  2. 确认识别结果（传 PendingRecord）：字段已自动填好，用户只需「保存」或稍作修改。
 */
class EditRecordActivity : BaseActivity() {

    private lateinit var binding: ActivityEditRecordBinding
    private var record: Record? = null
    private var pending: PendingRecord? = null
    private var isNew = false

    private var type: Int = RecordType.EXPENSE
    private var time: Long = System.currentTimeMillis()
    private var category: String = ""
    private var payMethod: String = ""
    private var imagePath: String = ""
    private var source: Int = RecordSource.MANUAL

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.toggleType.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) {
                type = if (checkedId == R.id.btnIncome) RecordType.INCOME else RecordType.EXPENSE
                renderCategories()
            }
        }
        binding.btnDate.setOnClickListener { pickDate() }
        binding.btnTime.setOnClickListener { pickTime() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener { confirmDelete() }

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val fromPending = PendingRecord.from(intent)
        when {
            id > 0 -> loadExisting(id)
            fromPending != null -> bindPending(fromPending)
            else -> {
                finish()
            }
        }
    }

    // ---------------- 已有账单 ----------------

    private fun loadExisting(id: Long) {
        isNew = false
        val app = application as App
        app.post {
            val r = app.dao.byIds(listOf(id)).firstOrNull()
            runOnUiThread {
                if (r == null) {
                    toast("记录不存在")
                    finish()
                } else {
                    record = r
                    type = r.type
                    time = r.time
                    category = r.category
                    source = r.source
                    binding.etAmount.setText(String.format(Locale.US, "%.2f", r.amount))
                    binding.etNote.setText(r.note)
                    binding.etMerchant.setText(r.merchant)
                    binding.etTag.setText(r.tag)
                    payMethod = r.payMethod
                    imagePath = r.imagePath
                    binding.tvSourceInfo.text =
                        "来源：${CsvFormat.sourceName(r.source)} · 记账于 ${TimeUtil.formatFull(r.createTime)}"
                    renderCategories()
                    renderPayMethods()
                    renderImageRow()
                    updateTimeButtons()
                }
            }
        }
    }

    // ---------------- 识别结果确认 ----------------

    private fun bindPending(p: PendingRecord) {
        isNew = true
        pending = p
        type = p.type
        time = p.time
        category = p.category
        source = p.source
        payMethod = p.payMethod
        imagePath = p.imagePath
        supportActionBar?.title = "确认账单"
        binding.btnDelete.visibility = View.GONE
        binding.btnSave.text = "保存记账"
        binding.etAmount.setText(String.format(Locale.US, "%.2f", p.amount))
        binding.etMerchant.setText(p.merchant)
        runOnUiThread {
            binding.tvSourceInfo.text = if (p.similar) {
                "来源：${CsvFormat.sourceName(p.source)} · 检测到相似账单，确认后会再记一笔"
            } else {
                "来源：${CsvFormat.sourceName(p.source)} · 已自动识别，可直接保存"
            }
        }
        renderCategories()
        renderPayMethods()
        renderImageRow()
        updateTimeButtons()
    }

    // ---------------- 账单图片（「账单图片」开关开启时自动截图） ----------------

    /** 有图就显示入口：点击查看，长按删除（删除后同时清掉数据库里的引用） */
    private fun renderImageRow() {
        val ctx = this
        val has = imagePath.isNotBlank() && ImageStore.exists(ctx, imagePath)
        binding.tvBillImage.visibility = if (has) View.VISIBLE else View.GONE
        if (!has) return
        binding.tvBillImage.text = "查看账单图片（长按可删除）"
        binding.tvBillImage.setOnClickListener { showBillImage() }
        binding.tvBillImage.setOnLongClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("删除账单图片")
                .setMessage("只删除这张截图，账单记录本身会保留。")
                .setPositiveButton("删除") { _, _ ->
                    ImageStore.delete(ctx, listOf(imagePath))
                    val id = record?.id ?: 0L
                    imagePath = ""
                    renderImageRow()
                    if (id > 0L) {
                        val app = application as App
                        app.post { app.dao.updateImage(id, "") }
                    }
                    toast("已删除账单图片")
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
    }

    private fun showBillImage() {
        val file = ImageStore.absolute(this, imagePath) ?: run {
            toast("图片已不存在")
            renderImageRow()
            return
        }
        val bitmap = runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1080) sample *= 2
            android.graphics.BitmapFactory.decodeFile(
                file.absolutePath,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()
        if (bitmap == null) {
            toast("图片读取失败")
            return
        }
        val iv = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(24, 24, 24, 24)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("账单图片")
            .setView(iv)
            .setPositiveButton("关闭") { _, _ -> bitmap.recycle() }
            .setNeutralButton("删除") { _, _ ->
                ImageStore.delete(this, listOf(imagePath))
                val id = record?.id ?: 0L
                imagePath = ""
                renderImageRow()
                if (id > 0L) {
                    val app = application as App
                    app.post { app.dao.updateImage(id, "") }
                }
                bitmap.recycle()
            }
            .show()
    }

    /** 支付方式 / 支出工具选择（含识别到的或自定义的方式） */
    private fun renderPayMethods() {
        binding.cgPayMethods.removeAllViews()
        val methods = COMMON_PAY_METHODS.toMutableList()
        if (payMethod.isNotBlank() && !methods.contains(payMethod)) methods.add(0, payMethod)
        methods.forEach { name ->
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

    // ---------------- 交互 ----------------

    private fun renderCategories() {
        binding.cgCategories.removeAllViews()
        val base = if (type == RecordType.INCOME) Categories.INCOME_LIST else Categories.EXPENSE_LIST
        val custom = if (type == RecordType.INCOME) CustomCategories.income(this) else CustomCategories.expense(this)
        val withCustom = base + custom.filter { !base.contains(it) }
        val list = if (category.isNotBlank() && !withCustom.contains(category)) withCustom + category else withCustom
        list.forEach { name ->
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                id = View.generateViewId()
                chipIcon = ContextCompat.getDrawable(this@EditRecordActivity, CategoryIcons.iconRes(name))
                isChipIconVisible = true
                chipIconTint = android.content.res.ColorStateList.valueOf(Categories.bgColor(name))
                isChecked = name == category
            }
            binding.cgCategories.addView(chip)
        }
        if (binding.cgCategories.checkedChipId == View.NO_ID && binding.cgCategories.childCount > 0) {
            binding.cgCategories.check(binding.cgCategories.getChildAt(0).id)
        }
        binding.cgCategories.setOnCheckedStateChangeListener { group, checkedIds ->
            val cid = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            category = (group.findViewById<Chip>(cid))?.text?.toString().orEmpty()
        }
    }

    private fun updateTimeButtons() {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault())
        binding.btnDate.text = dt.format(dateFmt)
        binding.btnTime.text = dt.format(timeFmt)
    }

    private fun pickDate() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("选择日期")
            .setSelection(time)
            .build()
        picker.addOnPositiveButtonClickListener { utcMillis ->
            val day = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
            val dayStart = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            time = Edits.shiftTimeToDay(time, dayStart)
            updateTimeButtons()
        }
        picker.show(supportFragmentManager, "edit_date")
    }

    private fun pickTime() {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault())
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(dt.hour)
            .setMinute(dt.minute)
            .setTitleText("选择时间")
            .build()
        picker.addOnPositiveButtonClickListener {
            time = dt.toLocalDate().atTime(picker.hour, picker.minute)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            updateTimeButtons()
        }
        picker.show(supportFragmentManager, "edit_time")
    }

    private fun save() {
        val amount = binding.etAmount.text?.toString()?.trim()?.toDoubleOrNull()
        if (amount == null || amount <= 0.0 || amount > 1_000_000.0) {
            toast("请输入正确的金额")
            return
        }
        val merchant = binding.etMerchant.text?.toString()?.trim().orEmpty()
        val note = binding.etNote.text?.toString()?.trim().orEmpty()
        val tag = binding.etTag.text?.toString()?.trim().orEmpty()
        val app = application as App
        val finalCategory = category.ifBlank { Categories.default(type == RecordType.INCOME) }

        if (isNew) {
            val newRecord = Record(
                amount = Math.round(amount * 100.0) / 100.0,
                type = type,
                category = finalCategory,
            merchant = merchant,
                note = note,
                tag = tag,
                payMethod = payMethod,
                source = source,
                time = time,
                imagePath = imagePath
            )
            val p = pending
            app.post {
                RecordWriter.insert(this, newRecord)
                if (p != null) {
                    app.deduper.clearPending(p.type, p.amount, p.merchant)
                    Notifier.cancel(this, p.notifyId)
                }
                runOnUiThread {
                    toast("已记账")
                    finish()
                }
            }
            return
        }

        val r = record ?: return
        val updated = r.copy(
            amount = Math.round(amount * 100.0) / 100.0,
            type = type,
            category = finalCategory,
            merchant = merchant,
            note = note,
            tag = tag,
            payMethod = payMethod,
            time = time,
            imagePath = imagePath.ifBlank { r.imagePath }
        )
        app.post {
            RecordWriter.update(this, updated)
            // 学习：你改了分类 / 商户 / 备注，就记一票（商户 + 备注 + 金额三个维度）
            if (finalCategory != r.category || note != r.note || merchant != r.merchant) {
                CategoryLearner.learn(this, merchant.ifBlank { r.displayName }, note, updated.amount, finalCategory)
            }
            runOnUiThread {
                toast("已保存")
                finish()
            }
        }
    }

    private fun confirmDelete() {
        val r = record ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("删除账单")
            .setMessage("将「${r.category} ${String.format(Locale.US, "%.2f", r.amount)}」移入回收站？可在回收站还原。")
            .setPositiveButton("移入回收站") { _, _ ->
                val app = application as App
                app.post {
                    app.dao.softDelete(r.id)
                    runOnUiThread { finish() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_ID = "record_id"

        /** 常用支付方式 / 支出工具（可按需增删；识别到的新方式会自动出现在最前） */
        val COMMON_PAY_METHODS = listOf(
            "零钱", "零钱通", "花呗", "余额宝", "储蓄卡", "信用卡", "银行卡",
            "微信支付", "支付宝", "云闪付", "数字人民币", "Apple Pay", "其他"
        )

        fun intent(c: Context, id: Long): Intent =
            Intent(c, EditRecordActivity::class.java).putExtra(EXTRA_ID, id)

        /** 由通知「修改」按钮 / 悬浮窗「修改」/ 点击通知打开：字段预填，保存即新增 */
        fun pendingIntent(c: Context, p: PendingRecord): Intent =
            p.putExtras(Intent(c, EditRecordActivity::class.java)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
    }
}
