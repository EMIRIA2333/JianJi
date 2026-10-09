package com.jianji.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.jianji.app.App
import com.jianji.app.R
import com.jianji.app.core.HookProtocol
import com.jianji.app.databinding.ActivityAutoSettingsBinding
import com.jianji.app.databinding.ItemProfileRowBinding
import com.jianji.app.service.HookGuardService
import com.jianji.app.service.RootScanService
import com.jianji.app.util.BackgroundWork
import com.jianji.app.util.CategoryLearner
import com.jianji.app.util.ImageStore
import com.jianji.app.util.Notifier
import com.jianji.app.util.Prefs
import com.jianji.app.util.RecognitionLog
import com.jianji.app.util.RootManager
import com.jianji.app.util.ServiceHealth
import com.jianji.app.util.TimeUtil

/**
 * 二级设置页：自动记账的全部选项（从「我的」点进来）。
 * 把设置摊在一页里，比在首页堆一长条更容易找到，也不用折叠。
 */
class AutoCaptureSettingsActivity : BaseActivity() {

    private lateinit var binding: ActivityAutoSettingsBinding
    private var updating = false

    private val smsPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val notifyPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutoSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setupRows()
        // root 检测需要执行 su（会弹授权框），放到后台线程
        if (RootManager.rootedCached() == null) {
            RootManager.checkAsync { runOnUiThread { if (!isFinishing) refresh() } }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ---------------- 行配置 ----------------

    private fun row(b: ItemProfileRowBinding) = b

    private fun configureRow(
        b: ItemProfileRowBinding,
        icon: Int,
        title: String,
        subtitle: String,
        switch: Boolean,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        b.ivRowIcon.setImageResource(icon)
        b.tvRowTitle.text = title
        b.tvRowSubtitle.text = subtitle
        b.tvRowSubtitle.visibility = View.VISIBLE
        if (switch) {
            b.swRow.visibility = View.VISIBLE
            b.ivChevron.visibility = View.GONE
            b.swRow.setOnCheckedChangeListener(null)
            b.swRow.isChecked = initial
            b.swRow.setOnCheckedChangeListener { _, checked ->
                if (!updating) onChange(checked)
            }
            b.root.setOnClickListener { b.swRow.toggle() }
        } else {
            b.swRow.visibility = View.GONE
            b.ivChevron.visibility = View.VISIBLE
            b.root.setOnClickListener { onChange(false) }
        }
    }

    private fun setSwitchState(sw: MaterialSwitch, checked: Boolean) {
        updating = true
        sw.isChecked = checked
        updating = false
    }

    private fun setupRows() {
        val ctx = this
        row(binding.rowAuto).apply {
            configureRow(
                this, R.drawable.ic_stat_record, "自动记账", "总开关，关掉就完全不记账了",
                switch = true, initial = Prefs.isAutoEnabled(ctx)
            ) {
                Prefs.setAutoEnabled(ctx, it)
                // 总开关：关掉 → 立刻停掉一切后台服务（附属功能不跟随运行）
                if (it) BackgroundWork.sync(ctx) else BackgroundWork.stopAll(ctx)
                toast(if (it) "已开启自动记账" else "已关闭：所有后台扫描与常驻服务已停止")
            }
        }

        row(binding.rowMianmi).apply {
            configureRow(
                this, R.drawable.ic_cat_power, "免密支付自动记录",
                "免密/自动扣款直接记上，通知里可撤销（推荐）",
                switch = true, initial = Prefs.isAutoSaveMianmi(ctx)
            ) { Prefs.setAutoSaveMianmi(ctx, it) }
        }

        row(binding.rowConfirm).apply {
            configureRow(
                this, R.drawable.ic_gear, "记账前确认",
                "默认关闭：识别到就直接记上，通知里可撤销；打开则先问你一句",
                switch = true, initial = Prefs.isConfirmBeforeSave(ctx)
            ) { Prefs.setConfirmBeforeSave(ctx, it) }
        }

        row(binding.rowOverlay).apply {
            configureRow(
                this, R.drawable.ic_cat_fun, "悬浮窗确认",
                "在支付页面上弹卡片，当场改分类",
                switch = true, initial = Prefs.isOverlayEnabled(ctx)
            ) { Prefs.setOverlayEnabled(ctx, it) }
        }

        // 分类自动学习：开关（长按行可查看/清空学习记录，不再多占一行菜单）
        row(binding.rowLearn).apply {
            configureRow(
                this, R.drawable.ic_cat_fun, "分类自动学习",
                "记住你改过的分类，相似账单自动套用（长按查看记录）",
                switch = true, initial = Prefs.isCategoryLearning(ctx)
            ) { on ->
                Prefs.setCategoryLearning(ctx, on)
                toast(if (on) "已开启：会学习你改过的分类" else "已关闭：改用规则猜测")
            }
            root.setOnLongClickListener {
                showLearnedCategories(ctx)
                true
            }
        }

        row(binding.rowBillScan).apply {
            configureRow(
                this, R.drawable.ic_import, "账单页补齐",
                "打开账单页时，把没通知的账单也补上",
                switch = true, initial = Prefs.isBillScanEnabled(ctx)
            ) { Prefs.setBillScanEnabled(ctx, it) }
        }

        row(binding.rowBillDays).apply {
            configureRow(
                this, R.drawable.ic_calendar, "补齐时间范围", "近 3 天",
                switch = false, initial = false
            ) { showBillScanDaysDialog() }
        }

        row(binding.rowRemark).apply {
            configureRow(
                this, R.drawable.ic_import, "自动提取备注",
                "顺手记下商品说明，存成标签可搜索",
                switch = true, initial = Prefs.isExtractRemark(ctx)
            ) { Prefs.setExtractRemark(ctx, it) }
        }

        row(binding.rowBillImage).apply {
            configureRow(
                this, R.drawable.ic_photo, "账单图片",
                "账单页面自动截图存档，会占点空间",
                switch = true, initial = Prefs.isBillImage(ctx)
            ) { Prefs.setBillImage(ctx, it) }
        }

        row(binding.rowSms).apply {
            configureRow(
                this, R.drawable.ic_cat_sms, "短信记账",
                "银行扣款短信也能记（要短信权限）",
                switch = true, initial = Prefs.isSmsEnabled(ctx)
            ) { Prefs.setSmsEnabled(ctx, it) }
        }

        row(binding.rowPowerSave).apply {
            configureRow(
                this, R.drawable.ic_shield, "省电模式",
                "闲着就自动降频，省电（推荐）",
                switch = true, initial = Prefs.isPowerSave(ctx)
            ) { Prefs.setPowerSave(ctx, it) }
        }

        row(binding.rowStatus).apply {
            configureRow(
                this, R.drawable.ic_stat_record, "通知栏状态",
                "状态栏常驻，一眼看出它还在不在",
                switch = true, initial = Prefs.isStatusNotification(ctx)
            ) { checked ->
                Prefs.setStatusNotification(ctx, checked)
                updateStatusNotification()
            }
        }

        row(binding.rowDiag).apply {
            configureRow(
                this, R.drawable.ic_import, "识别记录（排查用）",
                "看看它最近识别到了什么、为什么没记",
                switch = false, initial = false
            ) { showDiagnostics() }
        }

        // ---------------- Root 增强（状态/初始化/手动读取/间隔 合并为一个弹窗） ----------------
        row(binding.rowRoot).apply {
            configureRow(
                this, R.drawable.ic_key, "Root 增强",
                "检测中…",
                switch = false, initial = false
            ) { showRootDialog(ctx) }
        }
        row(binding.rowRootScan).apply {
            configureRow(
                this, R.drawable.ic_bell_off, "自动读取通知栏（root）",
                "关着最省电；开着才定时读，且需要无障碍为开",
                switch = true, initial = Prefs.isRootAutoScan(ctx)
            ) { on ->
                Prefs.setRootAutoScan(ctx, on)
                BackgroundWork.syncRootScan(ctx)
                toast(if (on) "已开启：会常驻一条低优先级通知" else "已关闭（最省电）")
            }
        }

        // ---------------- Hook 模块（状态/常驻/自测/诊断，四项） ----------------
        row(binding.rowHookStatus).apply {
            configureRow(
                this, R.drawable.ic_key, "Hook 模块",
                "检测中…",
                switch = false, initial = false
            ) { showHookStatusDialog(ctx) }
        }
        row(binding.rowHookGuard).apply {
            configureRow(
                this, R.drawable.ic_stat_record, "保持后台常驻",
                "让进程常驻，Hook 通知才能随时送达（有活干才会常驻）",
                switch = true, initial = Prefs.isHookGuard(ctx)
            ) { on ->
                Prefs.setHookGuard(ctx, on)
                BackgroundWork.syncGuard(ctx)
                toast(if (on) "已开启" else "已关闭（打开应用才会补记）")
            }
        }
        row(binding.rowHookTest).apply {
            configureRow(
                this, R.drawable.ic_cat_fun, "自测记账链路",
                "一步跑完「识别 → 记账」和「广播 → 记账」两条路",
                switch = false, initial = false
            ) { runHookSelfTest() }
        }
        row(binding.rowHookDiag).apply {
            configureRow(
                this, R.drawable.ic_import, "Hook 诊断（需 root）",
                "心跳 / 框架环境 / LSPosed 日志，一次抓全",
                switch = false, initial = false
            ) { showHookDiag() }
        }

        // ---------------- 权限与状态（四项合并成一行） ----------------
        row(binding.rowPermission).apply {
            configureRow(
                this, R.drawable.ic_shield, "权限与状态",
                "无障碍 / 通知使用权 / 短信 / 通知权限",
                switch = false, initial = false
            ) { showPermissionDialog(ctx) }
        }
    }

    /** 分类学习记录：看它学到了什么，可一键清空（重新学习） */
    private fun showLearnedCategories(ctx: AutoCaptureSettingsActivity) {
        toast("正在读取学习记录…")
        (application as App).post {
            val list = CategoryLearner.entries(ctx)
            val body = if (list.isEmpty()) {
                "还没有学习记录。\n\n改一次某笔账单的分类、或手动记一笔，它就会记住，\n以后相似账单会自动套用同一个分类。"
            } else {
                list.take(30).joinToString("\n") { "${it.key} → ${it.category}（${it.hits} 次）" } +
                    if (list.size > 30) "\n…共 ${list.size} 条" else ""
            }
            runOnUiThread {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("分类学习记录")
                    .setMessage(body)
                    .setPositiveButton("清空重学") { _, _ ->
                        (application as App).post {
                            CategoryLearner.clear(ctx)
                            runOnUiThread { toast("已清空，之后重新学习") }
                        }
                    }
                    .setNeutralButton("复制") { _, _ -> copyText(body, "已复制 📋") }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    /** 权限与状态：一行看四项，点哪项就跳哪项的授权 */
    private fun showPermissionDialog(ctx: AutoCaptureSettingsActivity) {
        val a11y = ServiceHealth.isAccessibilityEnabled(ctx)
        val listener = ServiceHealth.isNotificationListenerEnabled(ctx)
        val sms = Prefs.isSmsPermissionGranted(ctx)
        val notify = Notifier.canNotify(ctx)
        val items = arrayOf(
            "无障碍服务：" + if (a11y) "运行中 ✅" else "已关闭 ❌（点这里恢复）",
            "通知使用权：" + if (listener) "已开启 ✅" else "未开启 ❌（点这里去开）",
            "短信读取权限：" + if (sms) "已授予 ✅" else "未授予（点这里申请）",
            "通知权限：" + if (notify) "已授予 ✅" else "未授予（点这里申请）"
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle("权限与状态")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> ServiceHealth.openAccessibilitySettings(ctx)
                    1 -> ServiceHealth.openNotificationListenerSettings(ctx)
                    2 -> if (sms) ServiceHealth.openAppDetails(ctx)
                    else smsPermLauncher.launch(android.Manifest.permission.RECEIVE_SMS)
                    else -> if (android.os.Build.VERSION.SDK_INT >= 33) {
                        notifyPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        ServiceHealth.openAppDetails(ctx)
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** Root 增强：状态 + 一键初始化 + 立即读取一次 + 读取间隔 + 取消 ADB 授权 */
    private fun showRootDialog(ctx: AutoCaptureSettingsActivity) {
        val rooted = RootManager.rootedCached() == true
        val authorized = ServiceHealth.isAdbAuthorized(ctx)
        val actions = mutableListOf<String>()
        if (!rooted) actions.add("获取 Root 权限（检测）") else actions.add("一键 root 初始化（设好权限/无障碍/白名单）")
        actions.add("立即读取一次通知栏")
        actions.add("读取间隔：" + Prefs.rootScanIntervalSec(ctx) + " 秒")
        if (authorized) actions.add("取消 ADB 授权")
        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (rooted) "Root 已获取 ✅" else "Root 未获取")
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    actions[0] -> if (!rooted) {
                        RootManager.checkAsync { runOnUiThread { refresh(); toast(if (it) "已获取 Root ✅" else "仍未获取") } }
                    } else {
                        runRootSetup()
                    }
                    actions[1] -> runRootScan()
                    actions[2] -> showRootIntervalDialog()
                    else -> confirmRevokeAdb(ctx)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 取消 ADB 授权（有 root 直接执行，无 root 给命令） */
    private fun confirmRevokeAdb(ctx: AutoCaptureSettingsActivity) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("取消 ADB 授权？")
            .setMessage("取消后简记不能再自动恢复无障碍（其它功能不受影响），随时可重新授权。")
            .setPositiveButton("确认取消") { _, _ ->
                if (RootManager.rootedCached() == true) {
                    toast("正在取消…")
                    Thread {
                        val ok = RootManager.revokeAdbGrant(ctx)
                        runOnUiThread {
                            toast(if (ok) "已取消 ADB 授权 ✅" else "取消失败，可复制命令到电脑执行")
                            refresh()
                        }
                    }.start()
                } else {
                    copyText(RootManager.revokeAdbCommand(ctx), "已复制取消命令 📋")
                }
            }
            .setNeutralButton("复制取消命令") { _, _ ->
                copyText(RootManager.revokeAdbCommand(ctx), "已复制取消命令 📋")
            }
            .setNegativeButton("不取消", null)
            .show()
    }

    private fun copyText(text: String, tip: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jianji", text))
        toast(tip)
    }

    /**
     * 识别记录：把后台最近的判断结果显示出来，方便定位「为什么没记账」。
     * 支持复制（发给开发者）/ 清空。
     */
    private fun showDiagnostics() {        val a11yOn = ServiceHealth.isAccessibilityEnabled(this)
        val notifyAccess = ServiceHealth.isNotificationListenerEnabled(this)
        val log = RecognitionLog.dump()
        val body = buildString {
            append("无障碍服务：")
            append(if (a11yOn) "运行中 ✅" else "已关闭 ❌（这就是不记账的原因，点上面一项去恢复）")
            append("\n通知使用权：")
            append(if (notifyAccess) "已开启 ✅" else "未开启 ❌（开了才能兜住不弹通知的免密支付）")
            append("\n\n最近识别记录：\n")
            if (log.isEmpty()) {
                append("（暂无记录。去微信/支付宝付一笔或打开账单页，再回来看看）")
            } else {
                log.forEach { append(it).append('\n') }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("识别记录")
            .setMessage(body)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("jianji-log", body))
                toast("已复制，可发给开发者排查 📋")
            }
            .setNeutralButton("清空") { _, _ ->
                RecognitionLog.clear()
                toast("已清空")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ---------------- Root 增强 ----------------

    /** Root 状态说明 + 各项能力入口 */
    private fun showRootDialog() {
        if (RootManager.rootedCached() != true) {
            MaterialAlertDialogBuilder(this)
                .setTitle("未检测到 Root 权限")
                .setMessage(
                    "没有 root 也能正常用：打开上面的「无障碍服务」和「通知使用权」即可自动记账。\n\n" +
                        "如果这台手机已经 root（Magisk 等），点「一键 root 初始化」并允许授权，就能解锁下面的能力。"
                )
                .setPositiveButton("重新检测") { _, _ ->
                    RootManager.checkAsync { runOnUiThread { refresh(); toast(if (it) "已获取 Root ✅" else "仍未获取") } }
                }
                .setNegativeButton("知道了", null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Root 已获取 ✅")
            .setMessage(
                "root 能做三件无障碍做不到的事：\n\n" +
                    "1. 一键把权限、无障碍、通知读取、电池白名单全部设好（不用去系统设置里点）\n" +
                    "2. 直接读通知栏里的消费信息 —— 即使无障碍被系统关掉也能记账\n" +
                    "3. 无障碍被关闭时自己写回系统设置，真正「关不掉」"
            )
            .setPositiveButton("一键初始化") { _, _ -> runRootSetup() }
            .setNeutralButton("读取通知栏") { _, _ -> runRootScan() }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 一键 root 初始化，逐条汇报结果 */
    private fun runRootSetup() {
        val ctx = this
        toast("正在初始化，请在弹出的 root 授权框点允许…")
        (application as App).post {
            val steps = RootManager.setup(ctx)
            val text = steps.joinToString("\n") { (if (it.ok) "✅ " else "❌ ") + it.label } +
                "\n\n（❌ 的项可以稍后重试；部分机型限制较严属正常）"
            runOnUiThread {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("root 初始化结果")
                    .setMessage(text)
                    .setPositiveButton("好了") { _, _ -> refresh() }
                    .show()
                RecognitionLog.add("root 初始化：" + steps.joinToString("；") { it.label + if (it.ok) "✓" else "✗" })
            }
        }
    }

    /** 用 root 直接读通知栏，把消费信息交给识别管道（手动触发，也遵守门控） */
    private fun runRootScan() {
        val ctx = this
        if (RootManager.rootedCached() != true) {
            toast("需要 root 权限，先用「一键 root 初始化」授权")
            return
        }
        toast("正在读取通知栏…")
        (application as App).post {
            val n = RootManager.scanNotifications(ctx)
            runOnUiThread {
                toast(
                    if (n > 0) "读到 $n 条相关通知，已交给识别（记好的会在通知栏提示）"
                    else "通知栏里暂时没有相关消费通知"
                )
            }
        }
    }

    // ---------------- Hook 模块（LSPosed） ----------------

    /** Hook 模块状态：状态 + 开启步骤 + 刷新（原来三个入口合并到这里） */
    private fun showHookStatusDialog(ctx: AutoCaptureSettingsActivity) {
        val active = HookProtocol.isActive(Prefs.hookActiveAt(ctx), System.currentTimeMillis())
        val last = Prefs.hookActiveAt(ctx)
        val source = Prefs.hookLastSource(ctx)
        val delivery = Prefs.hookLastDeliveryAt(ctx)
        val items = arrayOf("开启步骤 / 排查清单", "刷新状态")
        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (active) "Hook 模块已生效 ✅" else "Hook 模块未生效")
            .setMessage(
                "最近心跳：" + (if (last > 0) TimeUtil.formatFull(last) else "从未") +
                    (if (source.isNotBlank()) "（$source）" else "") + "\n" +
                    "最近收到广播：" + (if (delivery > 0) TimeUtil.formatFull(delivery) else "从未") + "\n" +
                    "无障碍被自动恢复：" + Prefs.a11yRestoreCount(ctx) + " 次\n\n" +
                    if (active) "Hook 直读 + 无障碍 + 通知读取三条通路都在工作，同一条通知只记一次。"
                    else "请在 LSPosed 里启用「简记」并勾选作用域：系统框架 + 微信 + 支付宝。"
            )
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showHookHelp()
                    else -> {
                        RootManager.checkAsync { runOnUiThread { refresh(); toast("已刷新") } }
                    }
                }
            }
            .setNeutralButton("复制状态") { _, _ ->
                copyText(
                    "hook_active=" + (if (active) "yes" else "no") +
                        "\nlast=" + (if (last > 0) TimeUtil.formatFull(last) else "never") +
                        "\nsource=$source\ndelivery=" +
                        (if (delivery > 0) TimeUtil.formatFull(delivery) else "never") +
                        "\na11y_restores=" + Prefs.a11yRestoreCount(ctx),
                    "已复制 📋"
                )
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** root 自动读通知的间隔选择（间隔越小越耗电，默认 120 秒） */
    private fun showRootIntervalDialog() {
        val ctx = this
        val items = arrayOf("60 秒（较耗电）", "120 秒（推荐）", "180 秒", "300 秒（最省电）")
        val values = intArrayOf(60, 120, 180, 300)
        val current = values.indexOfFirst { it == Prefs.rootScanIntervalSec(ctx) }.coerceAtLeast(1)
        MaterialAlertDialogBuilder(ctx)
            .setTitle("读取间隔")
            .setMessage("间隔越短越及时，但 `dumpsys` 读取比较耗电，建议 120 秒以上。")
            .setSingleChoiceItems(items, current) { d, which ->
                Prefs.setRootScanIntervalSec(ctx, values[which])
                d.dismiss()
                refresh()
                BackgroundWork.syncRootScan(ctx)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** LSPosed 开启步骤 + 排查清单（含 Zygisk Next 组合的坑） */
    private fun showHookHelp() {
        val text = """
            ── 本模块有两条 hook 路线（推荐第一条）──
            【路线一 · 系统框架】hook 系统的 NotificationManagerService：
              **所有应用的通知都必须经过它**，因此不需要注入微信/支付宝。
              只要作用域勾上「系统框架」就能拿到全部通知 —— 对 Zygisk Next 环境也更友好。
            【路线二 · 应用进程】hook 微信/支付宝自己的 notify 调用（需要注入到这两个应用）。
            两条路的同一条通知会按文本去重，不会重复记账。

            ── 开启步骤 ──
            1. 确认注入框架本身是活的：
               · Magisk 自带 Zygisk：Magisk 设置里 Zygisk = 开
               · Zygisk Next（第三方）：模块列表里 Zygisk Next 必须「已启用」；
                 此时把 Magisk 自带 Zygisk 关掉、把「强制执行排除列表(DenyList)」也关掉
                 → 改完**重启手机**
            2. 打开 LSPosed 管理器首页，确认它自己显示「已激活」
            3. LSPosed → 模块 → 打开「简记」
            4. 作用域勾选：**系统框架(system)** + 微信 + 支付宝
            5. **重启手机**（系统框架必须在开机时注入）
            6. 回到本页：状态应变成「已生效 ✅（android · system）」——
               只要这一条出现，说明 hook 已经工作，之后任何支付通知都会直读记账

            ── 还是不行？按这个顺序查 ──
            a) LSPosed → 日志，搜索「简记」：
               有「入口类已实例化」→ 模块被加载了，看后面是否报「系统框架 hook：已安装」
               连这句都没有 → 模块没被加载（框架/Zygisk Next/版本问题）
            b) 状态里显示「android · system」= 系统路线已生效；显示「com.tencent.mm · activity」= 应用路线生效
            c) 点「立即读取一次」能否记上：能 → 简记侧没问题
            d) 还不行就把 LSPosed 日志里带「简记」的行发我

            ── Zygisk Next 常见坑 ──
            · LSPosed 版本要够新（≥1.9.2），老版本配新 Zygisk Next 常不注入
            · KernelSU/APatch 装完 Zygisk Next **必须重启**
            · Magisk 同时开自带 Zygisk + Zygisk Next = 不生效
            · 改完作用域后，系统框架路线要**重启**才生效（应用路线强制停止目标应用即可）

            说明：模块只读通知参数、不改返回值，异常全部吞掉，不会影响系统或微信/支付宝。
        """.trimIndent()
        MaterialAlertDialogBuilder(this)
            .setTitle("LSPosed 开启步骤")
            .setMessage(text)
            .setPositiveButton("复制步骤") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("jianji-hook-help", text))
                toast("已复制 📋")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 自测（一次跑两条路，各自给结论）：
     * - 直连：验证「识别 → 入库」
     * - 广播：验证 Hook 用的那条通路（接收器 → 入库）
     */
    private fun runHookSelfTest() {
        val ctx = this
        val app = application as App
        val title = "交易提醒"
        fun text(amount: Double) =
            "你在智能自助服务有一笔${amount}元的免密/自动扣款支付，点击领取4个支付宝积分。"
        val directAmount = 0.07
        val broadcastAmount = 0.08

        // 路线一：直连记账管道
        com.jianji.app.service.NotifyPipe.handle(
            ctx, com.jianji.app.core.PaymentParser.PKG_ALIPAY, title, text(directAmount), "自测直连"
        )
        // 路线二：真广播（与 Hook 模块完全相同的格式）
        sendBroadcast(
            Intent(HookProtocol.ACTION)
                .setComponent(android.content.ComponentName(packageName, HookProtocol.HOST_RECEIVER))
                .putExtra(HookProtocol.KEY_TOKEN, HookProtocol.TOKEN)
                .putExtra(HookProtocol.KEY_PKG, com.jianji.app.core.PaymentParser.PKG_ALIPAY)
                .putExtra(HookProtocol.KEY_TITLE, title)
                .putExtra(HookProtocol.KEY_TEXT, text(broadcastAmount))
                .putExtra(HookProtocol.KEY_STAGE, HookProtocol.STAGE_SELFTEST)
        )
        toast("已发起自测，2 秒后给结论…")
        Thread {
            Thread.sleep(2000)
            fun count(amount: Double) = runCatching {
                app.dao.countSimilar(
                    amount, com.jianji.app.core.RecordType.EXPENSE, "智能自助服务",
                    System.currentTimeMillis() - 120_000
                )
            }.getOrDefault(0)
            val direct = count(directAmount) > 0
            val broadcast = count(broadcastAmount) > 0
            val report = buildString {
                append("直连记账：").append(if (direct) "✅ 通过" else "❌ 失败").append('\n')
                append("广播链路：").append(if (broadcast) "✅ 通过" else "❌ 失败").append("\n\n")
                append(
                    when {
                        direct && broadcast -> "两条路都通：简记侧完全正常。若 Hook 仍不记账，问题在 LSPosed 侧。"
                        direct -> "直连正常、广播不通：接收器或广播投递有问题。"
                        else -> "两条路都不通：记账管道本身有问题，请把「识别记录」发我。"
                    }
                )
            }
            runOnUiThread {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("自测结果")
                    .setMessage(report + "\n\n（测试会在明细里留下 ¥0.07 / ¥0.08 两笔，可随时删掉）")
                    .setPositiveButton("复制") { _, _ -> copyText(report, "已复制 📋") }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }.start()
    }

    /** Hook 诊断：把心跳/环境/日志三份信息一次抓全（原来三个入口合并） */
    private fun showHookDiag() {
        val ctx = this
        toast("正在收集诊断信息（需要 root，可能要几秒）…")
        (application as App).post {
            val env = runCatching { RootManager.diagnoseHookEnv(ctx) }.getOrDefault("")
            val log = runCatching { RootManager.readLsposedLog(ctx) }.getOrDefault("")
            val files = runCatching { RootManager.readHookDiag(ctx) }.getOrDefault("")
            val text = buildString {
                append(env)
                append("\n\n────────\n").append(log)
                append("\n\n──────── 模块自证文件 ────────\n").append(files)
            }
            runOnUiThread {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Hook 诊断")
                    .setMessage(text)
                    .setPositiveButton("复制") { _, _ -> copyText(text, "已复制，发我即可 📋") }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    private fun showBillScanDaysDialog() {
        val options = listOf(1, 3, 7, 14, 30)
        val labels = options.map { "近 $it 天" }.toTypedArray()
        val checked = options.indexOf(Prefs.billScanDays(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("补齐时间范围")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                Prefs.setBillScanDays(this, options[which])
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 同步常驻状态通知 */
    private fun updateStatusNotification() {
        if (!Prefs.isStatusNotification(this)) {
            Notifier.updateStatusNotification(this, false, 0)
            return
        }
        val app = application as App
        val start = TimeUtil.dayStart(System.currentTimeMillis())
        app.post {
            val n = app.dao.countInRange(start, start + 24L * 60 * 60 * 1000)
            runOnUiThread { Notifier.updateStatusNotification(this, true, n) }
        }
    }

    private fun refresh() {
        val ctx = this
        val smsGranted = Prefs.isSmsPermissionGranted(ctx)

        setSwitchState(row(binding.rowAuto).swRow, Prefs.isAutoEnabled(ctx))
        setSwitchState(row(binding.rowMianmi).swRow, Prefs.isAutoSaveMianmi(ctx))
        setSwitchState(row(binding.rowConfirm).swRow, Prefs.isConfirmBeforeSave(ctx))
        setSwitchState(row(binding.rowOverlay).swRow, Prefs.isOverlayEnabled(ctx))
        setSwitchState(row(binding.rowBillScan).swRow, Prefs.isBillScanEnabled(ctx))
        setSwitchState(row(binding.rowRemark).swRow, Prefs.isExtractRemark(ctx))
        setSwitchState(row(binding.rowBillImage).swRow, Prefs.isBillImage(ctx))
        setSwitchState(row(binding.rowSms).swRow, smsGranted && Prefs.isSmsEnabled(ctx))
        setSwitchState(row(binding.rowPowerSave).swRow, Prefs.isPowerSave(ctx))
        setSwitchState(row(binding.rowStatus).swRow, Prefs.isStatusNotification(ctx))

        row(binding.rowSms).swRow.isEnabled = smsGranted
        row(binding.rowSms).tvRowSubtitle.text =
            if (smsGranted) "银行扣款短信也能记（已授权）" else "要先给短信权限哦"
        row(binding.rowBillDays).tvRowSubtitle.text = "近 " + Prefs.billScanDays(ctx) + " 天"
        row(binding.rowBillImage).tvRowSubtitle.text = if (Prefs.isBillImage(ctx)) {
            "截图存本机 · 已用 " + ImageStore.sizeText(ImageStore.totalBytes(ctx))
        } else {
            "账单页面自动截图存档，会占点空间"
        }

        val a11yOn = ServiceHealth.isAccessibilityEnabled(ctx)
        val notifyAccess = ServiceHealth.isNotificationListenerEnabled(ctx)
        // 权限与状态：一行汇总四项，状态用 ✅/❌ 一眼看清
        val permBits = buildList {
            add(if (a11yOn) "无障碍 ✅" else "无障碍 ❌")
            add(if (notifyAccess) "通知使用权 ✅" else "通知使用权 ❌")
            add(if (smsGranted) "短信 ✅" else "短信 ❌")
            add(if (Notifier.canNotify(ctx)) "通知 ✅" else "通知 ❌")
        }
        row(binding.rowPermission).tvRowSubtitle.text = permBits.joinToString(" · ")

        // Root 增强：状态 + 操作入口（一键初始化/读取/间隔/取消授权都在弹窗里）
        val rooted = RootManager.rootedCached() == true
        val adbAuthorized = ServiceHealth.isAdbAuthorized(ctx)
        row(binding.rowRoot).tvRowSubtitle.text = when (RootManager.rootedCached()) {
            true -> if (adbAuthorized) "已获取 ✅ 高级修复已授权" else "已获取 ✅（可自动恢复无障碍）"
            false -> "未获取（点开可检测 / 授权）"
            null -> "检测中…（首次会请求 root 授权）"
        }
        setSwitchState(row(binding.rowRootScan).swRow, Prefs.isRootAutoScan(ctx))
        row(binding.rowRootScan).swRow.isEnabled = rooted && a11yOn
        row(binding.rowRootScan).tvRowSubtitle.text = when {
            !rooted -> "需要 root 权限"
            !a11yOn -> "无障碍未开启：按设定不扫描"
            Prefs.isRootAutoScan(ctx) -> "运行中 ✅ 每 ${Prefs.rootScanIntervalSec(ctx)} 秒读一次（息屏暂停）"
            else -> "关闭状态（最省电）；开启后才定时读取"
        }

        // Hook 模块状态（只有模块真实心跳才算已生效；自测不计入）
        val hookActive = HookProtocol.isActive(Prefs.hookActiveAt(ctx), System.currentTimeMillis())
        val hookSource = Prefs.hookLastSource(ctx)
        row(binding.rowHookStatus).tvRowSubtitle.text = when {
            hookActive && hookSource.isNotBlank() -> "已生效 ✅ 通知直读（$hookSource）"
            hookActive -> "已生效 ✅ 通知直读（无需无障碍）"
            else -> "未生效：在 LSPosed 里启用并勾选微信、支付宝"
        }
        setSwitchState(row(binding.rowHookGuard).swRow, Prefs.isHookGuard(ctx))
        row(binding.rowHookGuard).tvRowSubtitle.text =
            if (Prefs.isHookGuard(ctx)) "运行中 ✅ 进程常驻，Hook 通知随时送达"
            else "未开启：只有打开应用时才会补记"

        updateStatusNotification()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
