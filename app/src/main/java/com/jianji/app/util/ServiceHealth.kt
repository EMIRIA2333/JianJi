package com.jianji.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import com.jianji.app.core.HookProtocol
import com.jianji.app.service.AutoCaptureService

/**
 * 运行状态自检与系统设置跳转（对应「第1步/第2步」权限引导、自启动、电池优化等）。
 *
 * 为什么需要它：自动记账完全依赖无障碍服务，**更新 APK 或系统清理后台后，
 * 无障碍服务经常被系统关掉**，表现就是「突然不记账了」而界面上看不出任何异常。
 * 这里提供：状态检测 + 一键跳转 + 停用提醒（每天最多一次）。
 */
object ServiceHealth {

    /** 无障碍服务是否已开启（直接读系统设置，无需额外权限） */
    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val expected = ComponentName(ctx.packageName, AutoCaptureService::class.java.name)
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val name = splitter.next()
            if (name.equals(expected.flattenToString(), true) ||
                name.equals(expected.flattenToShortString(), true)
            ) return true
        }
        return false
    }

    /** 是否已忽略电池优化（图二的「已忽略 / 未设置」） */
    fun isBatteryOptimizationIgnored(ctx: Context): Boolean = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    /** 是否已授予「通知使用权」（通知读取服务的开关） */
    fun isNotificationListenerEnabled(ctx: Context): Boolean = runCatching {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx)
            .contains(ctx.packageName)
    }.getOrDefault(false)

    /** 打开「通知使用权」设置页 */
    fun openNotificationListenerSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { openAppDetails(ctx) }
    }

    /** 是否已通过 ADB 授权「高级修复」（WRITE_SECURE_SETTINGS） */
    fun isAdbAuthorized(ctx: Context): Boolean = runCatching {
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 授权「高级修复」用的命令（复制给用户在电脑上执行一次即可） */
    fun adbGrantCommand(ctx: Context): String =
        "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /**
     * 自动恢复无障碍服务（需要先 ADB 授权）。
     *
     * 这样系统把无障碍关掉后，应用可以自己把它写回系统设置，用户不用手动去点。
     * @return true = 本次刚刚恢复了
     */
    fun autoRestoreAccessibility(ctx: Context): Boolean {
        if (!isAdbAuthorized(ctx)) return false
        if (isAccessibilityEnabled(ctx)) return false
        return runCatching {
            val resolver = ctx.contentResolver
            val me = ComponentName(ctx.packageName, AutoCaptureService::class.java.name).flattenToString()
            val current = Settings.Secure.getString(
                resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val list = current.split(':').filter { it.isNotBlank() }.toMutableList()
            if (list.none { it.equals(me, true) }) list.add(me)
            Settings.Secure.putString(
                resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":")
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }

    /**
     * 保活自检：无障碍被关掉时尝试自动恢复（限流，最多每 [ALIVE_CHECK_INTERVAL_MS] 一次）。
     * 由常驻的「通知读取」服务顺带调用 —— 通知一来就顺手查一眼。
     */
    fun ensureAccessibilityAlive(ctx: Context) {
        if (!Prefs.isAutoEnabled(ctx)) return
        if (isAccessibilityEnabled(ctx)) return
        if (autoRestoreAccessibility(ctx)) {
            RecognitionLog.add("检测到无障碍被系统关闭 → 已自动恢复（高级修复已授权）")
        }
    }

    /** 打开系统无障碍设置（用户在这里开启「简记」） */
    fun openAccessibilitySettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 打开自启动 / 后台管理（小米走 MIUI 安全中心，其它品牌走应用详情页） */
    fun openAutoStartSettings(ctx: Context) {
        val miui = Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val huawei = Intent().apply {
            component = ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val oppo = Intent().apply {
            component = ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        listOf(miui, huawei, oppo).forEach { intent ->
            if (runCatching { ctx.startActivity(intent) }.isSuccess) return
        }
        openAppDetails(ctx)
    }

    /** 打开电池优化设置列表 */
    fun openBatterySettings(ctx: Context) {
        val ok = runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!ok) openAppDetails(ctx)
    }

    /** 打开本应用的系统详情页 */
    fun openAppDetails(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 无障碍服务被关闭时提醒用户（每天最多一次）。
     * 由界面进入前台时调用（服务本身无法感知自己被杀）。
     */
    /**
     * 记录本次 APK 的更新时间，并在"模块已更新但 Hook 仍在跑旧代码"时提醒一次。
     * 在 App.onCreate 调用（进程随时会因 Hook 广播而启动，比等用户打开界面更及时）。
     */
    fun recordUpdateAndWarn(ctx: Context) {
        val updateAt = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        if (updateAt > 0) Prefs.setLastUpdateAt(ctx, updateAt)

        runCatching { warnIfHookSilent(ctx) }
        if (!hookReloadNeeded(ctx)) return
        val now = System.currentTimeMillis()
        if (now - Prefs.hookReloadWarnedAt(ctx) < DAY_MS) return
        Prefs.setHookReloadWarnedAt(ctx, now)
        AppLog.i("Hook", "检测到模块已更新但 Hook 上报时间更早 → 提示重启手机")
        Notifier.notifyHookReloadNeeded(ctx)
    }

    /**
     * Hook "加载了却收不到通知"检测。
     *
     * 判据：心跳新鲜（15 分钟内，说明模块在跑）+ 最近 6 小时**没有任何经由 Hook 记到的通知**
     *      + 但确实有通知从别的通道进来（说明是这条通道的问题，不是"最近没支付"）。
     * 命中后提示：去 LSPosed 勾作用域（系统框架 + 微信/支付宝）、重启手机、打开 root 扫描兜底。
     *
     * 真实案例：心跳正常、LSPosed 日志里也有 `应用进程 hook 已接入`，
     * 但整段日志 `[Hook]` 通道 0 条，支付宝/微信通知全部漏记 —— 光看"已生效"根本发现不了。
     */
    fun warnIfHookSilent(ctx: Context) {
        if (!Prefs.isAutoEnabled(ctx)) return
        val now = System.currentTimeMillis()
        if (!HookProtocol.isActive(Prefs.hookActiveAt(ctx), now)) return
        val hookAt = Prefs.lastHookNotifyAt(ctx)
        val anyAt = Prefs.lastNotifyAt(ctx)
        val silent = (hookAt == 0L || now - hookAt > HOOK_SILENT_MS) && anyAt > 0
        if (!silent) return
        if (now - Prefs.hookSilentWarnedAt(ctx) < DAY_MS) return
        Prefs.setHookSilentWarnedAt(ctx, now)
        AppLog.i("Hook", "Hook 心跳正常但 6 小时内未转发任何通知 → 提示检查作用域/重启")
        Notifier.notifyHookSilent(ctx)
    }

    /** Hook 静默判定窗口：6 小时 */
    private const val HOOK_SILENT_MS = 6L * 60 * 60 * 1000

    /**
     * Hook 是否还在跑**更新前**的代码。
     *
     * 判据：Hook 最近上报时间 < 本 APK 更新时间。
     * LSPosed 只在进程启动时注入，所以更新后未重启的系统框架/应用里仍是旧代码：
     * 表现为"心跳正常但一条通知都收不到"。
     */
    fun hookReloadNeeded(ctx: Context): Boolean {
        val up = Prefs.lastUpdateAt(ctx)
        val at = Prefs.hookActiveAt(ctx)
        return up > 0 && at > 0 && at < up
    }

    fun warnIfDisabled(ctx: Context) {
        if (!Prefs.isAutoEnabled(ctx)) return
        if (isAccessibilityEnabled(ctx)) return
        // Hook 直读已生效时不再催无障碍：这时候通知照样能记，
        // 反复提示只会让人以为出问题了（用户要求「开了 Hook 就应能完全代替无障碍」）。
        if (HookProtocol.isActive(Prefs.hookActiveAt(ctx), System.currentTimeMillis())) return
        val now = System.currentTimeMillis()
        if (now - Prefs.serviceWarnedAt(ctx) < DAY_MS) return
        Prefs.setServiceWarnedAt(ctx, now)
        Notifier.notifyServiceOff(ctx)
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** 无障碍保活自检的间隔 */
    private const val ALIVE_CHECK_INTERVAL_MS = 5 * 60 * 1000L

    /** 上次保活自检时间（内存标记，够用且零开销） */
    @Volatile
    private var lastAliveCheckAt = 0L

    /** 限流后的保活自检（供通知读取服务调用） */
    fun ensureAccessibilityAliveThrottled(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAliveCheckAt < ALIVE_CHECK_INTERVAL_MS) return
        lastAliveCheckAt = now
        ensureAccessibilityAlive(ctx)
    }
}
