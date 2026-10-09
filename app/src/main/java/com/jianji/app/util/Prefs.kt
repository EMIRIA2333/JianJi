package com.jianji.app.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object Prefs {
    private const val FILE = "jianji_prefs"
    private const val KEY_AUTO = "auto_enabled"
    private const val KEY_SMS = "sms_enabled"
    private const val KEY_PRIVACY_MASK = "privacy_mask"
    private const val KEY_NOTIFY_HIDE = "notify_hide_amount"
    private const val KEY_COLLAPSED = "collapsed_sections"
    private const val KEY_BUDGET = "monthly_budget"
    private const val KEY_BUDGET_PERIOD = "budget_period_type"
    private const val KEY_BUDGET_START = "budget_period_start"
    private const val KEY_BUDGET_END = "budget_period_end"
    private const val KEY_BUDGET_WARNED_NEAR = "budget_warned_near"
    private const val KEY_BUDGET_WARNED_OVER = "budget_warned_over"
    private const val KEY_CONFIRM_BEFORE_SAVE = "confirm_before_save"
    private const val KEY_AUTO_SAVE_MIANMI = "auto_save_mianmi"
    private const val KEY_BACKGROUND = "banner_background"
    private const val KEY_OVERLAY = "overlay_enabled"
    private const val KEY_BILL_SCAN = "bill_scan_enabled"
    private const val KEY_BILL_SCAN_DAYS = "bill_scan_days"
    private const val KEY_POWER_SAVE = "power_save"
    private const val KEY_EXTRACT_REMARK = "extract_remark"
    private const val KEY_BILL_IMAGE = "bill_image"
    private const val KEY_STATUS_NOTIFICATION = "status_notification"
    private const val KEY_HOOK_ACTIVE_AT = "hook_active_at"
    private const val KEY_HOOK_SOURCE = "hook_source"
    private const val KEY_HOOK_DIAG = "hook_diag"
    private const val KEY_ROOT_AUTO_SCAN = "root_auto_scan"
    private const val KEY_ROOT_SCAN_INTERVAL = "root_scan_interval"
    private const val KEY_CATEGORY_LEARNING = "category_learning"
    private const val KEY_DISCLAIMER = "disclaimer_accepted"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_GLASS_MODE = "glass_mode"
    private const val KEY_LAST_TAB = "last_tab"
    private const val KEY_RECORDS_SCROLL = "records_scroll"
    private const val KEY_RECORDS_MONTH = "records_month"
    /** 通用滚动位置记忆（键 -> 像素/索引） */
    fun scrollY(c: Context, key: String): Int = sp(c).getInt("scroll_$key", 0)
    fun setScrollY(c: Context, key: String, v: Int) = sp(c).edit().putInt("scroll_$key", v).apply()
    private const val KEY_SEEN_NOTIFY = "seen_notify_fps"
    private const val KEY_HOOK_GUARD = "hook_guard"
    private const val KEY_HOOK_DELIVERY_AT = "hook_delivery_at"
    private const val KEY_A11Y_RESTORE_COUNT = "a11y_restore_count"
    private const val KEY_SERVICE_WARNED_AT = "service_warned_at"

    /** 缓存 SharedPreferences 实例：无障碍事件高频读取配置时避免重复查找 */
    @Volatile
    private var cached: android.content.SharedPreferences? = null

    fun sp(c: Context): android.content.SharedPreferences {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .also { cached = it }
        }
    }

    /** 省电模式（默认开启）：放宽页面扫描间隔，仅在屏幕点亮时扫描 */
    fun isPowerSave(c: Context): Boolean = sp(c).getBoolean(KEY_POWER_SAVE, true)
    fun setPowerSave(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_POWER_SAVE, v).apply()

    /**
     * 免密支付 / 自动扣款自动记录（默认开启）：
     * 这类消息代表钱已经扣完了，直接记上并给「撤销」入口，不再等用户点确认，
     * 避免因为通知不弹/被忽略而漏账。
     */
    fun isAutoSaveMianmi(c: Context): Boolean = sp(c).getBoolean(KEY_AUTO_SAVE_MIANMI, true)
    fun setAutoSaveMianmi(c: Context, v: Boolean) {
        AppLog.i("设置", "MIANMI = $v")
        sp(c).edit().putBoolean(KEY_AUTO_SAVE_MIANMI, v).apply()
    }

    /** 自动提取备注（默认开启）：从账单页面提取商品说明 / 备注，记入标签 */
    fun isExtractRemark(c: Context): Boolean = sp(c).getBoolean(KEY_EXTRACT_REMARK, true)
    fun setExtractRemark(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_EXTRACT_REMARK, v).apply()

    /**
     * 账单图片（**默认关闭**）：识别到账单时把账单页面截图存到本机，作为这笔账单的图片。
     * 默认关闭是为了省电与省空间（每张约 60~120KB，仅存本机，删除账单可一并清理）。
     */
    fun isBillImage(c: Context): Boolean = sp(c).getBoolean(KEY_BILL_IMAGE, false)
    fun setBillImage(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_BILL_IMAGE, v).apply()

    /** 通知栏状态（默认关闭）：常驻通知显示「自动记账运行中 · 今日已记 N 笔」 */
    fun isStatusNotification(c: Context): Boolean = sp(c).getBoolean(KEY_STATUS_NOTIFICATION, false)
    fun setStatusNotification(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_STATUS_NOTIFICATION, v).apply()

    /** Hook 模块（LSPosed）最近一次心跳时间：用来判断模块是否已生效 */
    fun hookActiveAt(c: Context): Long = sp(c).getLong(KEY_HOOK_ACTIVE_AT, 0L)
    fun setHookActiveAt(c: Context, at: Long) =
        sp(c).edit().putLong(KEY_HOOK_ACTIVE_AT, at).apply()

    /** 最近心跳来源（包名 · 阶段），用于确认到底是谁在汇报 */
    fun hookLastSource(c: Context): String = sp(c).getString(KEY_HOOK_SOURCE, "").orEmpty()
    fun setHookLastSource(c: Context, v: String) =
        sp(c).edit().putString(KEY_HOOK_SOURCE, v).apply()

    /** 模块侧现场诊断快照（随心跳送达） */
    fun hookDiag(c: Context): String = sp(c).getString(KEY_HOOK_DIAG, "").orEmpty()
    fun setHookDiag(c: Context, v: String) =
        sp(c).edit().putString(KEY_HOOK_DIAG, v).apply()

    /**
     * root 自动读通知（前台服务）开关：**默认关闭**（耗电较高）。
     *
     * `dumpsys notification --noredact` 每次都要把通知栏全部内容序列化一遍，
     * 30 秒一轮会明显吃电（实测过 20%+ 的电量占比）。
     * 只有在「无障碍完全用不了」时才建议打开，并把间隔调到 120 秒以上。
     */
    fun isRootAutoScan(c: Context): Boolean = sp(c).getBoolean(KEY_ROOT_AUTO_SCAN, false)
    fun setRootAutoScan(c: Context, v: Boolean) {
        AppLog.i("设置", "ROOT_AUTO_SCAN = $v")
        sp(c).edit().putBoolean(KEY_ROOT_AUTO_SCAN, v).apply()
    }

    /** root 自动读通知的间隔（秒），默认 120 秒；最小 60 秒（间隔太小很耗电） */
    fun rootScanIntervalSec(c: Context): Int = sp(c).getInt(KEY_ROOT_SCAN_INTERVAL, 120).coerceAtLeast(60)
    fun setRootScanIntervalSec(c: Context, v: Int) =
        sp(c).edit().putInt(KEY_ROOT_SCAN_INTERVAL, v.coerceAtLeast(60)).apply()

    /**
     * 分类自动学习（默认开启）：记住你改过的分类，相似账单自动套用；
     * 关掉后一律用规则猜测。
     */
    fun isCategoryLearning(c: Context): Boolean = sp(c).getBoolean(KEY_CATEGORY_LEARNING, true)
    fun setCategoryLearning(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_CATEGORY_LEARNING, v).apply()

    /** 首次启动的免责声明是否已确认（开源版合规要求） */
    fun isDisclaimerAccepted(c: Context): Boolean = sp(c).getBoolean(KEY_DISCLAIMER, false)
    fun setDisclaimerAccepted(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_DISCLAIMER, v).apply()

    /** 主题：0 = 跟随系统，1 = 纯白（强制浅色），2 = 纯黑（强制深色） */
    fun themeMode(c: Context): Int = sp(c).getInt(KEY_THEME_MODE, 0)
    fun setThemeMode(c: Context, v: Int) {
        AppLog.i("设置", "主题模式写入 = $v")
        sp(c).edit().putInt(KEY_THEME_MODE, v).apply()
    }

    /** 毛玻璃模式：0 = 关闭，1 = 白色玻璃，2 = 黑色玻璃 */
    fun glassMode(c: Context): Int = sp(c).getInt(KEY_GLASS_MODE, 0)
    fun setGlassMode(c: Context, v: Int) {
        AppLog.i("设置", "毛玻璃模式写入 = $v")
        sp(c).edit().putInt(KEY_GLASS_MODE, v).apply()
    }

    /** 兼容旧调用：任意非 0 都算开启毛玻璃 */
    fun isGlass(c: Context): Boolean = glassMode(c) != 0

    @Deprecated("用 setGlassMode", ReplaceWith("setGlassMode(c, if (v) 1 else 0)"))
    fun setGlass(c: Context, v: Boolean) = setGlassMode(c, if (v) 1 else 0)

    // ---------------- 界面位置（重建后恢复，避免"回到最上方"） ----------------

    fun lastTab(c: Context): Int = sp(c).getInt(KEY_LAST_TAB, 0)
    fun setLastTab(c: Context, v: Int) = sp(c).edit().putInt(KEY_LAST_TAB, v).apply()

    fun recordsScroll(c: Context): Int = sp(c).getInt(KEY_RECORDS_SCROLL, 0)
    fun setRecordsScroll(c: Context, v: Int) = sp(c).edit().putInt(KEY_RECORDS_SCROLL, v).apply()

    fun recordsMonthOffset(c: Context): Int = sp(c).getInt(KEY_RECORDS_MONTH, 0)
    fun setRecordsMonthOffset(c: Context, v: Int) = sp(c).edit().putInt(KEY_RECORDS_MONTH, v).apply()

    /**
     * 已处理过的通知指纹（`键:时间` 换行分隔）。
     *
     * 持久化保存，避免进程被杀后同一条留在通知栏的消息被重新记一遍。
     */
    fun seenNotifications(c: Context): String = sp(c).getString(KEY_SEEN_NOTIFY, "").orEmpty()
    fun setSeenNotifications(c: Context, v: String) =
        sp(c).edit().putString(KEY_SEEN_NOTIFY, v).apply()

    /**
     * Hook 直读守护：让进程常驻，保证 Hook 广播能送达、无障碍被杀能立刻恢复。
     * 默认**开启** —— 关掉就会退回"只有打开应用才记账"。
     */
    fun isHookGuard(c: Context): Boolean = sp(c).getBoolean(KEY_HOOK_GUARD, true)
    fun setHookGuard(c: Context, v: Boolean) {
        AppLog.i("设置", "HOOK_GUARD = $v")
        sp(c).edit().putBoolean(KEY_HOOK_GUARD, v).apply()
    }

    /** 最近一次收到 Hook 广播的时间（用于判断"关着应用时广播到底有没有送达"） */
    fun hookLastDeliveryAt(c: Context): Long = sp(c).getLong(KEY_HOOK_DELIVERY_AT, 0L)
    fun setHookLastDeliveryAt(c: Context, v: Long) =
        sp(c).edit().putLong(KEY_HOOK_DELIVERY_AT, v).apply()

    /** 无障碍服务被自动恢复的次数（判断是不是一直被系统杀） */
    fun a11yRestoreCount(c: Context): Int = sp(c).getInt(KEY_A11Y_RESTORE_COUNT, 0)
    fun bumpA11yRestoreCount(c: Context): Int {
        val n = a11yRestoreCount(c) + 1
        sp(c).edit().putInt(KEY_A11Y_RESTORE_COUNT, n).apply()
        return n
    }

    /** 无障碍服务被关闭时的提醒时间（每天最多提醒一次） */
    fun serviceWarnedAt(c: Context): Long = sp(c).getLong(KEY_SERVICE_WARNED_AT, 0L)
    fun setServiceWarnedAt(c: Context, v: Long) = sp(c).edit().putLong(KEY_SERVICE_WARNED_AT, v).apply()

    fun isAutoEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_AUTO, true)
    fun setAutoEnabled(c: Context, v: Boolean) {
        AppLog.i("设置", "AUTO = $v")
        sp(c).edit().putBoolean(KEY_AUTO, v).apply()
    }

    /** 短信记账开关（需先授予 RECEIVE_SMS 权限才有意义） */
    fun isSmsEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_SMS, true)
    fun setSmsEnabled(c: Context, v: Boolean) {
        AppLog.i("设置", "SMS = $v")
        sp(c).edit().putBoolean(KEY_SMS, v).apply()
    }

    // ---------- 隐私保护（已移除「禁止截屏」与「应用锁」） ----------

    /** 金额隐藏：列表/统计中的金额显示为 **** */
    fun isPrivacyMask(c: Context): Boolean = sp(c).getBoolean(KEY_PRIVACY_MASK, false)
    fun setPrivacyMask(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_PRIVACY_MASK, v).apply()

    /** 通知里不显示金额，只提示已记账 */
    fun isNotifyHideAmount(c: Context): Boolean = sp(c).getBoolean(KEY_NOTIFY_HIDE, false)
    fun setNotifyHideAmount(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_NOTIFY_HIDE, v).apply()

    /**
     * 后台隐私保护**不再提供应用内开关**：直接用系统级接口
     * [android.app.Activity.setRecentsScreenshotEnabled]（Android 13+）隐藏最近任务内容，
     * 无需用户设置。此处仅保留一个只读标记，供界面显示当前状态。
     */
    fun isSystemPrivacyActive(c: Context): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU

    /** 我的页面各分区是否收起（二级菜单收纳） */
    fun collapsedSections(c: Context): Set<String> =
        sp(c).getStringSet(KEY_COLLAPSED, emptySet())?.toSet() ?: emptySet()

    fun setSectionCollapsed(c: Context, title: String, collapsed: Boolean) {
        val now = collapsedSections(c).toMutableSet()
        if (collapsed) now.add(title) else now.remove(title)
        sp(c).edit().putStringSet(KEY_COLLAPSED, now).apply()
    }

    // ---------- 预算（支持每月 / 自定义周期） ----------

    /** 预算额度，0 表示未设置 */
    fun monthlyBudget(c: Context): Double =
        sp(c).getString(KEY_BUDGET, "0")?.toDoubleOrNull() ?: 0.0

    fun setMonthlyBudget(c: Context, v: Double) =
        sp(c).edit().putString(KEY_BUDGET, v.toString()).apply()

    fun budgetPeriodType(c: Context): String =
        sp(c).getString(KEY_BUDGET_PERIOD, BudgetPeriod.TYPE_MONTH) ?: BudgetPeriod.TYPE_MONTH

    fun budgetCustomStart(c: Context): Long = sp(c).getLong(KEY_BUDGET_START, 0L)
    fun budgetCustomEnd(c: Context): Long = sp(c).getLong(KEY_BUDGET_END, 0L)

    fun setBudgetPeriod(c: Context, type: String, start: Long, end: Long) =
        sp(c).edit()
            .putString(KEY_BUDGET_PERIOD, type)
            .putLong(KEY_BUDGET_START, start)
            .putLong(KEY_BUDGET_END, end)
            .apply()

    /** 当前预算区间（自定义非法时自动回退自然月） */
    fun budgetRange(c: Context, now: Long = System.currentTimeMillis()): BudgetPeriod.Range =
        BudgetPeriod.resolve(budgetPeriodType(c), budgetCustomStart(c), budgetCustomEnd(c), now)

    /** 预警去重：同一周期内「接近预算」「已超支」各只提醒一次 */
    fun budgetWarnedNear(c: Context): String? = sp(c).getString(KEY_BUDGET_WARNED_NEAR, null)
    fun setBudgetWarnedNear(c: Context, key: String) =
        sp(c).edit().putString(KEY_BUDGET_WARNED_NEAR, key).apply()

    fun budgetWarnedOver(c: Context): String? = sp(c).getString(KEY_BUDGET_WARNED_OVER, null)
    fun setBudgetWarnedOver(c: Context, key: String) =
        sp(c).edit().putString(KEY_BUDGET_WARNED_OVER, key).apply()

    fun clearBudgetWarnings(c: Context) = sp(c).edit()
        .remove(KEY_BUDGET_WARNED_NEAR)
        .remove(KEY_BUDGET_WARNED_OVER)
        .apply()

    // ---------- 记账确认 ----------

    /**
     * true（默认）= 识别到支付后弹出「保存 / 修改」提醒，由用户一键确认；
     * false = 直接自动记账（仍会给出已记账提示）。
     */
    /**
     * 记账前确认（**默认关闭**）：识别到账单后是否先弹窗等你点保存。
     *
     * 默认关闭的原因：开着它时，通知被划掉/悬浮窗被忽略就会漏账；而且同一人同金额的
     * 多笔（转账收支 + 红包）会被"待处理标记"挡住。默认改为**直接记账 + 通知带撤销**，
     * 想逐笔确认的用户可以在设置里打开。
     */
    fun isConfirmBeforeSave(c: Context): Boolean = sp(c).getBoolean(KEY_CONFIRM_BEFORE_SAVE, false)
    fun setConfirmBeforeSave(c: Context, v: Boolean) {
        AppLog.i("设置", "CONFIRM_BEFORE_SAVE = $v")
        sp(c).edit().putBoolean(KEY_CONFIRM_BEFORE_SAVE, v).apply()
    }

    // ---------- 首页背景图 ----------

    /** 内置背景 id 或 "custom"（自定义图片存放于 filesDir/banner_custom.jpg） */
    fun backgroundId(c: Context): String = sp(c).getString(KEY_BACKGROUND, "bg_balloon") ?: "bg_balloon"
    fun setBackgroundId(c: Context, id: String) = sp(c).edit().putString(KEY_BACKGROUND, id).apply()

    // ---------- 悬浮窗与账单页补齐 ----------

    /**
     * 悬浮窗提醒（默认开启）：识别到支付时在支付页面上直接弹出卡片，
     * 可当场改分类并「保存 / 修改 / 忽略」。需要无障碍服务处于开启状态。
     */
    fun isOverlayEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_OVERLAY, true)
    fun setOverlayEnabled(c: Context, v: Boolean) {
        AppLog.i("设置", "OVERLAY = $v")
        sp(c).edit().putBoolean(KEY_OVERLAY, v).apply()
    }

    /** 账单页补齐（默认开启）：打开微信/支付宝账单列表页时，自动补录未记录的账单 */
    fun isBillScanEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_BILL_SCAN, true)
    fun setBillScanEnabled(c: Context, v: Boolean) {
        AppLog.i("设置", "BILL_SCAN = $v")
        sp(c).edit().putBoolean(KEY_BILL_SCAN, v).apply()
    }

    /** 账单页补齐的时间范围（天），默认 3 天 */
    fun billScanDays(c: Context): Int = sp(c).getInt(KEY_BILL_SCAN_DAYS, 3).coerceIn(1, 30)
    fun setBillScanDays(c: Context, days: Int) =
        sp(c).edit().putInt(KEY_BILL_SCAN_DAYS, days.coerceIn(1, 30)).apply()

    fun isSmsPermissionGranted(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, android.Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
}
