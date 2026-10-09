package com.jianji.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.jianji.app.App
import com.jianji.app.R
import com.jianji.app.databinding.DialogBackgroundBinding
import com.jianji.app.databinding.FragmentProfileBinding
import com.jianji.app.databinding.ItemBackgroundThumbBinding
import com.jianji.app.databinding.ItemProfileRowBinding
import com.jianji.app.util.BackupHelper
import com.jianji.app.util.BudgetPeriod
import com.jianji.app.util.AppLog
import com.jianji.app.util.CustomCategories
import com.jianji.app.util.ScrollMemory
import com.jianji.app.util.SettingsGuard
import com.jianji.app.util.GlassHelper
import com.jianji.app.util.Money
import com.jianji.app.util.Notifier
import com.jianji.app.util.Prefs
import com.jianji.app.util.RootManager
import com.jianji.app.util.ServiceHealth
import java.util.Locale

/**
 * 「我的」页：隐私、预算、备份、自动记账入口（点进二级页）、运行稳定性、外观。
 *
 * 说明：自动记账的十几项设置已收进二级页 [AutoCaptureSettingsActivity]，
 * 这里只保留一个入口；所有开关回调通过 [configureRow] + [updating] 绑定，
 * 刷新界面不会触发业务回调（避免历史版本出现过的闪屏问题）。
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val exportCsvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) doExport(uri, BackupHelper.Format.CSV)
        }

    private val exportXlsxLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri -> if (uri != null) doExport(uri, BackupHelper.Format.XLSX) }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doImport(uri)
    }

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext()
            toast("正在处理图片…")
            val app = requireActivity().application as App
            app.post {
                val ok = BannerBackgrounds.saveCustom(ctx, uri)
                activity?.runOnUiThread {
                    toast(if (ok) "已设置为自定义背景" else "图片读取失败，请换一张试试")
                    refresh()
                }
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        // 整段兜异常：界面初始化再出问题也要能打开应用（异常进日志）
        runCatching {
            setupPrivacyRows()
            setupBudgetRow()
            setupBackupRows()
            setupAutoEntryRow()
            setupStabilityRows()
            setupAppearanceRows()
            binding.tvVersion.text = getString(R.string.version_info)
        }.onFailure { AppLog.e("界面", it) }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // 切换主题会重建界面：恢复原来的滚动位置（不再"回到最顶上"）
        ScrollMemory.restore(requireContext(), "profile", _binding?.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------------- 隐私 ----------------

    private fun setupPrivacyRows() {
        row(binding.rowMask).apply {
            configureRow(
                icon = R.drawable.ic_eye_off,
                title = "金额隐藏",
                subtitle = "列表和统计里的金额显示成 ****",
                switch = true,
                initial = Prefs.isPrivacyMask(requireContext())
            ) { checked -> Prefs.setPrivacyMask(requireContext(), checked) }
        }

        row(binding.rowNotifyHide).apply {
            configureRow(
                icon = R.drawable.ic_bell_off,
                title = "通知隐藏金额",
                subtitle = "记账提醒只说记好了，不露金额",
                switch = true,
                initial = Prefs.isNotifyHideAmount(requireContext())
            ) { checked -> Prefs.setNotifyHideAmount(requireContext(), checked) }
        }
    }

    // ---------------- 预算 ----------------

    private fun setupBudgetRow() {
        row(binding.rowBudget).apply {
            ivRowIcon.setImageResource(R.drawable.ic_cat_income)
            tvRowTitle.text = "预算与预警"
            tvRowSubtitle.visibility = View.VISIBLE
            ivChevron.visibility = View.VISIBLE
            swRow.visibility = View.GONE
            root.setOnClickListener { BudgetDialog.show(this@ProfileFragment) { refresh() } }
        }
    }

    // ---------------- 备份 ----------------

    private fun setupBackupRows() {
        row(binding.rowExport).apply {
            configureRow(
                icon = R.drawable.ic_export,
                title = "导出账单",
                subtitle = "支持 CSV 与 Excel（.xlsx）",
                switch = false,
                initial = false
            ) { showExportFormatDialog() }
        }

        row(binding.rowImport).apply {
            configureRow(
                icon = R.drawable.ic_import,
                title = "导入账单",
                subtitle = "自动识别 CSV / Excel，重复记录会被跳过",
                switch = false,
                initial = false
            ) { importLauncher.launch(arrayOf("*/*")) }
        }

        row(binding.rowTrash).apply {
            configureRow(
                icon = R.drawable.ic_trash,
                title = "回收站",
                subtitle = "查看回收站",
                switch = false,
                initial = false
            ) { startActivity(Intent(requireContext(), TrashActivity::class.java)) }
        }

        row(binding.rowClear).apply {
            configureRow(
                icon = R.drawable.ic_trash,
                title = "清空全部记录",
                subtitle = "会先移入回收站，可在回收站还原",
                switch = false,
                initial = false
            ) { confirmClear() }
        }
    }

    private fun showExportFormatDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("导出格式")
            .setItems(arrayOf(BackupHelper.Format.CSV.label, BackupHelper.Format.XLSX.label)) { _, which ->
                val format = if (which == 0) BackupHelper.Format.CSV else BackupHelper.Format.XLSX
                val name = BackupHelper.defaultFileName(format)
                if (format == BackupHelper.Format.CSV) exportCsvLauncher.launch(name)
                else exportXlsxLauncher.launch(name)
            }
            .show()
    }

    // ---------------- 自动记账（二级页入口） ----------------

    private fun setupAutoEntryRow() {
        row(binding.rowAutoEntry).apply {
            configureRow(
                icon = R.drawable.ic_bolt,
                title = "自动记账",
                subtitle = "识别与确认、账单页、省电、权限",
                switch = false,
                initial = false
            ) { startActivity(Intent(requireContext(), AutoCaptureSettingsActivity::class.java)) }
        }
    }

    // ---------------- 运行稳定性 ----------------

    private fun setupStabilityRows() {
        row(binding.rowHealth).apply {
            configureRow(
                icon = R.drawable.ic_shield,
                title = "无障碍服务",
                subtitle = "检测中…",
                switch = false,
                initial = false
            ) {
                if (ServiceHealth.isAccessibilityEnabled(requireContext())) {
                    toast("服务在跑，自动记账正常 ✅")
                } else {
                    ServiceHealth.openAccessibilitySettings(requireContext())
                }
            }
        }

        row(binding.rowSystemAccess).apply {
            configureRow(
                icon = R.drawable.ic_gear,
                title = "系统放行设置",
                subtitle = "检测中…",
                switch = false,
                initial = false
            ) { showSystemAccessDialog(requireContext()) }
        }

        // 运行日志：可查看 / 复制 / 导出成文件（发给开发者定位问题）
        row(binding.rowLog).apply {
            configureRow(
                icon = R.drawable.ic_shield,
                title = "运行日志",
                subtitle = "查看 / 复制 / 保存到下载 / 分享（含环境信息，不含账单明细）",
                switch = false,
                initial = false
            ) { startActivity(Intent(requireContext(), LogActivity::class.java)) }
        }
        row(binding.rowAdvancedFix).apply {
            configureRow(
                icon = R.drawable.ic_key,
                title = "高级修复",
                subtitle = "ADB 授权后，无障碍被关闭时可自动恢复",
                switch = false,
                initial = false
            ) { showAdbFixDialog() }
        }
    }

    /** 三项系统设置合并成一个弹窗（自启动 / 电池 / 悬浮窗） */
    private fun showSystemAccessDialog(ctx: android.content.Context) {
        val battery = ServiceHealth.isBatteryOptimizationIgnored(ctx)
        val overlay = android.provider.Settings.canDrawOverlays(ctx)
        val items = arrayOf(
            "自启动（允许）—— 让简记一直在后台活着",
            "电池优化：" + if (battery) "已放行 ✅" else "未放行，点这里设置",
            "显示在其他应用上层：" + if (overlay) "已开启 ✅" else "未开启（选开）"
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle("系统放行设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> ServiceHealth.openAutoStartSettings(ctx)
                    1 -> ServiceHealth.openBatterySettings(ctx)
                    else -> openOverlayPermission()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 高级修复（ADB）：显示**是否已授权**；授权后无障碍被系统关闭时应用可自动恢复。
     */
    private fun showAdbFixDialog() {
        val ctx = requireContext()
        val authorized = ServiceHealth.isAdbAuthorized(ctx)
        val grantCmd = ServiceHealth.adbGrantCommand(ctx)
        val manualCmd = "adb shell settings put secure enabled_accessibility_services " +
            "com.jianji.app/com.jianji.app.service.AutoCaptureService && " +
            "adb shell settings put secure accessibility_enabled 1"

        if (authorized) {
            // 注意：setItems() 与 setMessage() 同时使用会互相顶掉（列表不渲染），
            // 所以这里的动作一律用按钮承载
            MaterialAlertDialogBuilder(ctx)
                .setTitle("高级修复：已授权 ✅")
                .setMessage(
                    "无障碍被系统关掉时，简记会自动帮你重新开启，不用手动去点。\n\n" +
                        "「立即检查并恢复」= 现在就查一次；\n" +
                        "「取消 ADB 授权」= 撤销这个能力（不影响其它功能）。"
                )
                .setPositiveButton("取消 ADB 授权") { _, _ -> confirmRevokeAdb(ctx) }
                .setNeutralButton("立即检查并恢复") { _, _ ->
                    val msg = when {
                        ServiceHealth.isAccessibilityEnabled(ctx) -> "无障碍正常运行中 ✅"
                        ServiceHealth.autoRestoreAccessibility(ctx) -> "已自动恢复 ✅"
                        else -> "恢复失败，请手动开启"
                    }
                    toast(msg)
                    refresh()
                }
                .setNegativeButton("关闭", null)
                .show()
        } else {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("高级修复（ADB，强烈建议）")
                .setMessage(
                    "授权后，无障碍被系统清理关掉时，简记能**自动恢复**，不用你手动去开。\n\n" +
                        "手机连电脑、开启 USB 调试，执行下面这条命令一次即可：\n\n$grantCmd"
                )
                .setPositiveButton("复制授权命令") { _, _ -> copyToClipboard(grantCmd, "已复制授权命令 📋") }
                .setNeutralButton("查看手动开启命令") { _, _ ->
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("手动开启无障碍")
                        .setMessage(manualCmd)
                        .setPositiveButton("复制") { _, _ -> copyToClipboard(manualCmd, "已复制 📋") }
                        .setNegativeButton("关闭", null)
                        .show()
                }
                .setNegativeButton("知道了", null)
                .show()
        }
    }

    /**
     * 取消 ADB 授权（WRITE_SECURE_SETTINGS）。
     * 有 root 直接用 `pm revoke` 执行；没有 root 就把命令给用户去电脑执行。
     */
    private fun confirmRevokeAdb(ctx: android.content.Context) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("取消 ADB 授权？")
            .setMessage(
                "取消后简记将**不能再自动恢复无障碍**（被系统关掉时需要你手动去开），其它功能不受影响。\n\n" +
                    "随时可以用同一条命令重新授权。"
            )
            .setPositiveButton("确认取消") { _, _ ->
                if (RootManager.rootedCached() == true) {
                    toast("正在取消…")
                    Thread {
                        val ok = RootManager.revokeAdbGrant(ctx)
                        activity?.runOnUiThread {
                            toast(if (ok) "已取消 ADB 授权 ✅" else "取消失败，可复制命令到电脑执行")
                            refresh()
                        }
                    }.start()
                } else {
                    copyToClipboard(RootManager.revokeAdbCommand(ctx), "无 root：已复制取消命令，请在电脑执行 📋")
                }
            }
            .setNeutralButton("复制取消命令") { _, _ ->
                copyToClipboard(RootManager.revokeAdbCommand(ctx), "已复制取消命令 📋")
            }
            .setNegativeButton("不取消", null)
            .show()
    }

    private fun copyToClipboard(text: String, tip: String) {
        val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jianji-adb", text))
        toast(tip)
    }

    /** 「显示在其他应用上层」权限页 */
    private fun openOverlayPermission() {
        val ctx = requireContext()
        if (android.provider.Settings.canDrawOverlays(ctx)) {
            toast("已开启 👍")
            return
        }
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", ctx.packageName, null)
        )
        runCatching { startActivity(intent) }.onFailure { ServiceHealth.openAppDetails(ctx) }
    }

    // ---------------- 外观 ----------------

    private fun setupAppearanceRows() {
        // 主题：跟随系统 / 纯白 / 纯黑
        row(binding.rowTheme).apply {
            configureRow(
                icon = R.drawable.ic_shield,
                title = "主题",
                subtitle = "跟随系统 / 纯白 / 纯黑",
                switch = false,
                initial = false
            ) { showThemeDialog() }
        }

        // 毛玻璃：开关即可（**配色跟随主题**：浅色=白玻璃，深色/纯黑=黑玻璃）
        row(binding.rowGlass).apply {
            configureRow(
                icon = R.drawable.ic_photo,
                title = "毛玻璃效果",
                subtitle = "卡片与面板半透明，透出模糊底（配色跟随主题，背景图保持清晰）",
                switch = true,
                initial = Prefs.isGlass(requireContext())
            ) { on ->
                if (SettingsGuard.isSwitchSuppressed()) {
                    // 重建期间的误触（点击穿透）：不写设置，回到真实状态
                    AppLog.w("毛玻璃", "忽略一次开关回调（防误触窗口内，on=$on）")
                    setSwitchState(row(binding.rowGlass).swRow, Prefs.isGlass(requireContext()))
                    return@configureRow
                }
                Prefs.setGlassMode(requireContext(), if (on) 1 else 0)
                AppLog.i("毛玻璃", "开关切换 → ${if (on) "开" else "关"}")
                BannerBackgrounds.invalidate()
                // 当场应用到当前界面（不 recreate → 不闪退、不滚回顶部）
                GlassHelper.apply(requireActivity())
                toast(if (on) "已开启毛玻璃（跟随主题配色）" else "已关闭")
                refresh()
            }
        }

        // 自定义分类
        row(binding.rowCustomCat).apply {
            configureRow(
                icon = R.drawable.ic_cat_fun,
                title = "自定义分类",
                subtitle = "加自己的分类，记账时可直接选",
                switch = false,
                initial = false
            ) { showCustomCategoryDialog() }
        }

        row(binding.rowBackground).apply {
            configureRow(
                icon = R.drawable.ic_cat_fun,
                title = "首页背景图",
                subtitle = "内置 5 张 + 相册自定义",
                switch = false,
                initial = false
            ) { showBackgroundDialog() }
        }
    }

    /** 主题选择：跟随系统 / 纯白 / 纯黑 */
    private fun showThemeDialog() {
        val ctx = requireContext()
        val labels = arrayOf("跟随系统", "纯白（浅色）", "纯黑（深色）")
        MaterialAlertDialogBuilder(ctx)
            .setTitle("主题")
            .setSingleChoiceItems(labels, Prefs.themeMode(ctx).coerceIn(0, 2)) { d, which ->
                Prefs.setThemeMode(ctx, which)
                // 记录：重建后位置由 ScrollMemory 恢复，日志里能看到这条以便核对
                AppLog.i(
                    "主题",
                    "切换为「${labels[which]}」；切换前 我的页滚动=${Prefs.scrollY(ctx, "profile")} " +
                        "明细页滚动=${Prefs.scrollY(ctx, "records")} 标签页=${Prefs.lastTab(ctx)}"
                )
                d.dismiss()
                // 关键（日志实锤的 bug）：主题切换会重建界面，而"点对话框这一击"会**穿透**到
                // 重建后的新界面 —— 恰好落在「毛玻璃」开关那一行 → 把设置改成关闭。
                // 两道防线：① 延后 350ms 再重建（先让触摸事件结束）；② 抑制窗口内忽略开关回调。
                SettingsGuard.suppressSwitchChanges(1800L)
                val app = requireActivity().application as App
                binding.root.postDelayed({ runCatching { app.applyTheme() } }, 350L)
                toast("已切换为「${labels[which]}」")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 自定义分类：添加 / 删除（支出与收入分开） */
    private fun showCustomCategoryDialog() {
        val ctx = requireContext()
        val expense = CustomCategories.expense(ctx)
        val income = CustomCategories.income(ctx)
        val items = buildList {
            add("➕ 新增分类")
            expense.forEach { add("支出 · $it（点一下删除）") }
            income.forEach { add("收入 · $it（点一下删除）") }
        }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("自定义分类")
            .setItems(items) { _, which ->
                if (which == 0) {
                    addCustomCategory()
                } else {
                    val label = items[which]
                    val isIncome = label.startsWith("收入")
                    val name = label.substringAfter("· ").substringBefore("（")
                    CustomCategories.remove(ctx, name, isIncome)
                    toast("已删除「$name」")
                    refresh()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 新增自定义分类（输入名字 → 选支出/收入） */
    private fun addCustomCategory() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = "分类名称（最多 8 个字）"
            setSingleLine()
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("新增分类")
            .setView(input)
            .setPositiveButton("加到支出") { _, _ ->
                if (CustomCategories.add(ctx, input.text.toString(), isIncome = false)) {
                    toast("已添加")
                    refresh()
                } else toast("名称为空或已存在")
            }
            .setNeutralButton("加到收入") { _, _ ->
                if (CustomCategories.add(ctx, input.text.toString(), isIncome = true)) {
                    toast("已添加")
                    refresh()
                } else toast("名称为空或已存在")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBackgroundDialog() {
        val ctx = requireContext()
        val dialogBinding = DialogBackgroundBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("首页背景图")
            .setView(dialogBinding.root)
            .setNegativeButton("关闭", null)
            .create()

        BannerBackgrounds.ENTRIES.forEach { entry ->
            val item = ItemBackgroundThumbBinding.inflate(layoutInflater, dialogBinding.llBackgrounds, false)
            item.ivThumb.setImageResource(entry.resId)
            item.tvThumbLabel.text = entry.label
            item.root.setOnClickListener {
                Prefs.setBackgroundId(ctx, entry.id)
                toast("已切换为「${entry.label}」")
                dialog.dismiss()
                refresh()
            }
            val lp = (item.root.layoutParams as? android.widget.LinearLayout.LayoutParams)
                ?: android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            lp.marginEnd = (resources.displayMetrics.density * 10).toInt()
            item.root.layoutParams = lp
            dialogBinding.llBackgrounds.addView(item.root)
        }
        dialogBinding.btnPickGallery.setOnClickListener {
            galleryLauncher.launch("image/*")
            dialog.dismiss()
        }
        dialog.show()
    }

    // ---------------- 行绑定工具 ----------------

    private fun RowAccessor.configureRow(
        icon: Int,
        title: String,
        subtitle: String?,
        switch: Boolean,
        initial: Boolean,
        onClick: (Boolean) -> Unit
    ) {
        ivRowIcon.setImageResource(icon)
        tvRowTitle.text = title
        if (subtitle.isNullOrEmpty()) {
            tvRowSubtitle.visibility = View.GONE
        } else {
            tvRowSubtitle.visibility = View.VISIBLE
            tvRowSubtitle.text = subtitle
        }
        swRow.setOnCheckedChangeListener(null)
        if (switch) {
            swRow.visibility = View.VISIBLE
            ivChevron.visibility = View.GONE
            swRow.isChecked = initial
            swRow.setOnCheckedChangeListener { _, checked ->
                if (!updating) onClick(checked)
            }
            root.setOnClickListener { swRow.toggle() }
        } else {
            swRow.visibility = View.GONE
            ivChevron.visibility = View.VISIBLE
            root.setOnClickListener { onClick(false) }
        }
    }

    /** 刷新时只更新开关状态；[updating] 保证不会触发业务回调 */
    private var updating = false

    private fun setSwitchState(sw: MaterialSwitch, checked: Boolean) {
        if (sw.isChecked == checked) return
        updating = true
        sw.isChecked = checked
        updating = false
    }

    private fun refresh() {
        val ctx = requireContext()
        setSwitchState(row(binding.rowMask).swRow, Prefs.isPrivacyMask(ctx))
        setSwitchState(row(binding.rowNotifyHide).swRow, Prefs.isNotifyHideAmount(ctx))

        // 自动记账入口的摘要：总开关 + 关键选项状态
        val autoOn = Prefs.isAutoEnabled(ctx)
        row(binding.rowAutoEntry).tvRowSubtitle.text = buildString {
            append(if (autoOn) "已开启" else "已关闭")
            if (Prefs.isOverlayEnabled(ctx)) append(" · 悬浮窗")
            if (Prefs.isAutoSaveMianmi(ctx)) append(" · 免密自动记")
            if (Prefs.isPowerSave(ctx)) append(" · 省电")
        }

        val budget = Prefs.monthlyBudget(ctx)
        row(binding.rowBudget).tvRowSubtitle.text = if (budget > 0) {
            Money.plain(budget) + " 元 · " + BudgetPeriod.describe(
                Prefs.budgetPeriodType(ctx), Prefs.budgetCustomStart(ctx), Prefs.budgetCustomEnd(ctx)
            )
        } else {
            "未设置，点击设置额度与周期"
        }

        // 运行状态：一眼看出服务是否还活着 / 权限是否放行
        row(binding.rowHealth).tvRowSubtitle.text =
            if (ServiceHealth.isAccessibilityEnabled(ctx)) "运行中，一切正常 ✅"
            else "被系统关掉了！点这里一键恢复"
        val batteryOk = ServiceHealth.isBatteryOptimizationIgnored(ctx)
        val overlayOk = android.provider.Settings.canDrawOverlays(ctx)
        row(binding.rowSystemAccess).tvRowSubtitle.text = buildList {
            add(if (batteryOk) "电池 ✅" else "电池 ❌")
            add(if (overlayOk) "悬浮窗 ✅" else "悬浮窗 ❌")
            add("自启动")
        }.joinToString(" · ")
        row(binding.rowAdvancedFix).tvRowSubtitle.text =
            if (ServiceHealth.isAdbAuthorized(ctx)) "已授权 ✅ 无障碍被关闭会自动恢复"
            else "未授权：ADB 授权后可自动恢复"
        row(binding.rowBackground).tvRowSubtitle.text = "当前：" + BannerBackgrounds.labelOf(ctx)
        row(binding.rowTheme).tvRowSubtitle.text = when (Prefs.themeMode(ctx)) {
            1 -> "纯白（浅色）"
            2 -> "纯黑（深色）"
            else -> "跟随系统"
        }
        setSwitchState(row(binding.rowGlass).swRow, Prefs.isGlass(ctx))
        row(binding.rowGlass).tvRowSubtitle.text = if (Prefs.isGlass(ctx)) {
            "已开启 · 配色跟随主题（浅色=白玻璃 / 深色=黑玻璃）"
        } else {
            "关闭：卡片为实心"
        }
        val customCount = CustomCategories.expense(ctx).size + CustomCategories.income(ctx).size
        row(binding.rowCustomCat).tvRowSubtitle.text =
            if (customCount > 0) "已有 $customCount 个自定义分类" else "加自己的分类，记账时可直接选"

        // 状态通知不需要在这里刷（二级页里管理），但切换后要保持一致
        if (!Prefs.isStatusNotification(ctx)) Notifier.updateStatusNotification(ctx, false, 0)

        val app = requireActivity().application as App
        app.post {
            val count = app.dao.count()
            val trashCount = app.dao.trashCount()
            activity?.runOnUiThread {
                if (_binding != null) {
                    binding.tvRecordCount.text = "共 $count 条账单"
                    row(binding.rowTrash).tvRowSubtitle.text =
                        if (trashCount > 0) "回收站有 $trashCount 条，可还原" else "回收站为空"
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        ScrollMemory.save(requireContext(), "profile", _binding?.root)
    }
    private fun doExport(uri: Uri, format: BackupHelper.Format) {
        val app = requireActivity().application as App
        val ctx = requireContext()
        app.post {
            val count = BackupHelper.export(ctx, uri, format)
            activity?.runOnUiThread {
                toast(if (count >= 0) "已导出 $count 条账单" else "导出失败")
            }
        }
    }

    private fun doImport(uri: Uri) {
        val app = requireActivity().application as App
        val ctx = requireContext()
        toast("正在导入…")
        app.post {
            val s = BackupHelper.importFrom(ctx, uri)
            activity?.runOnUiThread {
                val msg = if (s.badFormat) {
                    "文件格式无法识别（需为简记导出的 CSV 或 Excel）"
                } else {
                    String.format(Locale.US, "导入完成：新增 %d 条，跳过重复 %d 条，无效 %d 行", s.added, s.duplicated, s.invalid)
                }
                toast(msg)
                refresh()
            }
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清空全部记录")
            .setMessage("所有账单会先移入回收站（不是直接删除），可在「回收站」里还原或彻底删除。确定继续吗？")
            .setPositiveButton("移入回收站") { _, _ ->
                val app = requireActivity().application as App
                app.post {
                    val n = app.dao.softDeleteAll()
                    app.deduper.clear()
                    activity?.runOnUiThread {
                        toast("已移入回收站 $n 条，可在回收站还原")
                        refresh()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun row(b: ItemProfileRowBinding) = RowAccessor(b)

    /** 轻量包装，统一 include 布局的字段访问 */
    inner class RowAccessor(private val b: ItemProfileRowBinding) {
        val root get() = b.root
        val ivRowIcon get() = b.ivRowIcon
        val tvRowTitle get() = b.tvRowTitle
        val tvRowSubtitle get() = b.tvRowSubtitle
        val swRow get() = b.swRow
        val ivChevron get() = b.ivChevron
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
