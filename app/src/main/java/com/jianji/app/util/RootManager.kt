package com.jianji.app.util

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.jianji.app.core.HookProtocol
import com.jianji.app.core.NotificationDumpParser
import com.jianji.app.core.PaymentParser
import com.jianji.app.service.NotifyPipe
import com.jianji.app.service.NotifyListenerService

/**
 * Root 增强功能。
 *
 * 有 root 时能做到系统不允许普通应用做的事：
 * 1. **一键初始化** —— 权限、无障碍、通知读取、电池白名单一次设好（免 ADB、免手动点）；
 * 2. **直读通知栏** —— `dumpsys notification --noredact` 读出通知里的消费信息，
 *    即使无障碍和通知读取都没开也能记账；
 * 3. 无障碍被系统关掉时**直接写回系统设置**。
 */
object RootManager {

    data class Step(val label: String, val ok: Boolean)

    /** 是否检测到可用 root */
    fun isRooted(): Boolean = RootShell.isAvailable()

    /** 只读缓存（不执行 su，UI 线程可用）：null = 还没检测过 */
    fun rootedCached(): Boolean? = RootShell.cachedAvailability()

    /** 后台检测一次 root（第一次需要弹 su 授权框，不能在 UI 线程做） */
    fun checkAsync(onDone: (Boolean) -> Unit) {
        Thread {
            val ok = RootShell.isAvailable()
            onDone(ok)
        }.start()
    }

    private fun component(ctx: Context): String =
        ComponentName(ctx.packageName, "com.jianji.app.service.AutoCaptureService").flattenToString()

    private fun listener(ctx: Context): String =
        ComponentName(ctx.packageName, NotifyListenerService::class.java.name).flattenToString()

    /**
     * 一键初始化：逐条执行并汇报结果。
     * 需要 root；执行完无障碍/通知读取/电池白名单都会就位，用户不用再手动点。
     */
    fun setup(ctx: Context): List<Step> {
        val pkg = ctx.packageName
        val steps = ArrayList<Step>()
        if (!isRooted()) return listOf(Step("获取 root 权限", false))

        steps.add(Step("授予「高级修复」权限", RootShell.run("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS").ok))
        steps.add(Step("授予短信读取权限", RootShell.run("pm grant $pkg android.permission.RECEIVE_SMS").ok))
        steps.add(Step("授予短信权限（读取）", RootShell.run("pm grant $pkg android.permission.READ_SMS").ok))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            steps.add(Step("授予通知权限", RootShell.run("pm grant $pkg android.permission.POST_NOTIFICATIONS").ok))
        }
        // 通知读取（Android 11+ 有 cmd 接口）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            steps.add(Step("开启通知读取权限", RootShell.run("cmd notification allow_listener ${listener(ctx)}").ok))
        }
        steps.add(Step("开启无障碍服务", enableAccessibility(ctx)))
        steps.add(Step("加入电池优化白名单", RootShell.run("dumpsys deviceidle whitelist +$pkg").ok))
        steps.add(Step("允许后台弹出界面", RootShell.run("appops set $pkg SYSTEM_ALERT_WINDOW allow").ok))
        return steps
    }

    /** 直接用 root 把无障碍写回系统设置（比 WRITE_SECURE_SETTINGS 更可靠） */
    fun enableAccessibility(ctx: Context): Boolean {
        val me = component(ctx)
        val current = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val merged = if (current.isBlank() || current.contains(me)) {
            if (current.isBlank()) me else current
        } else {
            "$current:$me"
        }
        val a = RootShell.run(
            "settings put secure enabled_accessibility_services '$merged'"
        ).ok
        val b = RootShell.run("settings put secure accessibility_enabled 1").ok
        return a && b
    }

    /** 是否需要在无障碍被关闭时用 root 自动恢复 */
    fun autoRestoreWithRoot(ctx: Context) {
        if (!Prefs.isAutoEnabled(ctx)) return
        if (ServiceHealth.isAccessibilityEnabled(ctx)) return
        if (!isRooted()) return
        if (enableAccessibility(ctx)) {
            RecognitionLog.add("检测到无障碍被系统关闭 → 已用 root 自动恢复")
        }
    }

    /**
     * root 直读通知栏：读出当前所有通知里的消费信息并记账。
     *
     * @return 读到的通知条数（0 表示没读到或没有可识别的消费信息）
     */
    fun scanNotifications(ctx: Context): Int {
        if (!isRooted()) return 0
        val r = RootShell.run("dumpsys notification --noredact", timeoutMs = 12000)
        if (!r.ok || r.output.length < 20) {
            RecognitionLog.add("root 读取通知栏失败（可能被系统限制）")
            return 0
        }
        val items = NotificationDumpParser.parse(r.output)
            .filter { isWatched(it.pkg) && it.pkg != ctx.packageName }
        // 同一条通知只处理一次（指纹里带通知时间戳），
        // 否则通知栏里没清理的消息会被每轮轮询重复记账
        items.forEach { NotifyPipe.handle(ctx, it.pkg, it.title, it.text, "root", it.notifyTime) }
        RecognitionLog.add("root 扫描通知栏：读到 ${items.size} 条相关通知")
        return items.size
    }

    /**
     * 取消「高级修复」的 ADB 授权（WRITE_SECURE_SETTINGS）。
     *
     * 有 root 时直接执行 `pm revoke`；没有 root 时只能把命令给用户去电脑执行。
     */
    fun revokeAdbGrant(ctx: Context): Boolean {
        if (!isRooted()) return false
        val ok = RootShell.run(
            "pm revoke ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS",
            timeoutMs = 10000
        ).ok
        if (ok) RecognitionLog.add("已取消 ADB 授权（WRITE_SECURE_SETTINGS）")
        return ok
    }

    /** 取消授权用的命令（无 root 时给用户） */
    fun revokeAdbCommand(ctx: Context): String =
        "adb shell pm revoke ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /**
     * 读取 Hook 模块写在各应用数据目录里的诊断日志（需要 root）。
     * 用于判断「LSPosed 已启用但主程序收不到心跳」时，模块到底有没有跑起来。
     */
    fun readHookDiag(ctx: Context): String {
        if (!isRooted()) return "需要 root 权限才能读取诊断日志"
        val pkgs = listOf(PaymentParser.PKG_WECHAT, PaymentParser.PKG_ALIPAY)
        return pkgs.joinToString("\n\n") { p ->
            val r = RootShell.run("cat /data/data/$p/files/jianji_hook.txt 2>/dev/null | tail -n 12")
            if (r.output.isBlank()) {
                "$p：无记录\n（说明模块没有在这个应用的进程里跑起来 → 检查 LSPosed 作用域、并强制停止该应用后重开）"
            } else {
                "$p：\n" + r.output.trim().lines().joinToString("\n") { line ->
                    val sp = line.indexOf(' ')
                    if (sp > 0) {
                        val ts = line.substring(0, sp).toLongOrNull()
                        val time = if (ts != null) TimeUtil.formatFull(ts).takeLast(8) else ""
                        "$time  ${line.substring(sp + 1)}"
                    } else line
                }
            }
        }
    }

    /**
     * 读取 LSPosed 日志里与本模块相关的行（需要 root）。
     *
     * 注意：LSPosed 2.x 的日志文件名是 `modules_<时间戳>.log` / `verbose_<时间戳>.log`，
     * 所以必须**通配所有日志**再搜，不能只读 `modules.log`（那个文件并不存在，
     * 曾经因此得出"模块从未加载"的错误结论）。
     */
    fun readLsposedLog(ctx: Context): String {
        if (!isRooted()) return "需要 root 权限"
        val sb = StringBuilder()

        sb.append("【模块自证文件】（模块被加载就会写，最可靠）\n")
        val systemFile = RootShell.run("cat /data/system/jianji_hook.txt 2>/dev/null | tail -n 10", timeoutMs = 6000).output
        sb.append("· /data/system/jianji_hook.txt（系统框架路线）：")
        sb.append(if (systemFile.isBlank()) "无\n" else "\n${systemFile.trim()}\n")
        listOf(PaymentParser.PKG_WECHAT, PaymentParser.PKG_ALIPAY).forEach { p ->
            val f = RootShell.run("cat /data/data/$p/files/jianji_hook.txt 2>/dev/null | tail -n 10", timeoutMs = 6000).output
            sb.append("· $p：").append(if (f.isBlank()) "无\n" else "\n${f.trim()}\n")
        }

        sb.append("\n【/data/adb/lspd/log 目录】\n")
        sb.append(
            RootShell.run("ls -la /data/adb/lspd/log 2>/dev/null", timeoutMs = 6000).output
                .trim().ifBlank { "（读不到）" }
        )

        sb.append("\n\n【所有日志里含「简记」的行】\n")
        val hit = RootShell.run(
            "grep -a -h 简记 /data/adb/lspd/log/*.log 2>/dev/null | tail -n 30",
            timeoutMs = 10000
        ).output
        sb.append(hit.trim().ifBlank { "（无）" })

        sb.append("\n\n【config 目录里提到简记的文件】\n")
        val cfg = RootShell.run(
            "grep -a -l com.jianji.app /data/adb/lspd/config/* 2>/dev/null",
            timeoutMs = 8000
        ).output
        sb.append(cfg.trim().ifBlank { "（没有任何配置文件提到 com.jianji.app）" })

        sb.append("\n\n【config 目录清单】\n")
        sb.append(
            RootShell.run("ls -la /data/adb/lspd/config 2>/dev/null", timeoutMs = 6000).output
                .trim().ifBlank { "（读不到）" }
        )
        return sb.toString()
    }

    /**
     * Hook 环境诊断（需要 root）：一次性抓出「模块为什么没被注入」所需的全部事实。
     *
     * 包含：装了哪些注入框架模块及其版本、LSPosed 配置里有没有登记简记、
     * LSPosed 日志目录、模块日志里有没有简记的记录。
     */
    fun diagnoseHookEnv(ctx: Context): String {
        if (!isRooted()) return "需要 root 权限"
        val sb = StringBuilder()

        // 0) 模块心跳状态（模块是否真的把消息送进来了）
        val active = HookProtocol.isActive(Prefs.hookActiveAt(ctx), System.currentTimeMillis())
        sb.append("【模块心跳】")
        sb.append(if (active) "已收到 ✅ 来源：" else "从未收到 ❌ ")
        sb.append(Prefs.hookLastSource(ctx).ifBlank { "（无）" })
        sb.append("\n心跳时间：")
        sb.append(if (Prefs.hookActiveAt(ctx) > 0) TimeUtil.formatFull(Prefs.hookActiveAt(ctx)) else "从未")
        sb.append("\n最近收到 Hook 广播：")
        sb.append(
            if (Prefs.hookLastDeliveryAt(ctx) > 0) TimeUtil.formatFull(Prefs.hookLastDeliveryAt(ctx))
            else "从未（广播没送达 → 多半是进程被系统清理/后台限制）"
        )
        sb.append("\n无障碍被自动恢复次数：").append(Prefs.a11yRestoreCount(ctx))
        sb.append("\n无障碍当前状态：")
        sb.append(
            if (ServiceHealth.isAccessibilityEnabled(ctx)) "运行中 ✅"
            else "已关闭 ❌（打开应用才恢复 = 被系统反复杀掉）"
        )
        sb.append("\n守护服务：")
        sb.append(if (Prefs.isHookGuard(ctx)) "已开启" else "未开启（关掉就只能靠打开应用补记）")
        sb.append("\n模块上报的现场日志：\n")
        sb.append(Prefs.hookDiag(ctx).ifBlank { "（无）" })
        sb.append("\n\n")

        val ids = RootShell.run("ls -1 /data/adb/modules 2>/dev/null").output
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        sb.append("【注入框架模块】\n")
        if (ids.isEmpty()) {
            sb.append("（读不到 /data/adb/modules —— 可能不是 Magisk/KernelSU 环境，或不支持 root）\n")
        } else {
            ids.forEach { id ->
                val prop = RootShell.run(
                    "grep -E '^(name|version|versionCode)=' /data/adb/modules/$id/module.prop 2>/dev/null | head -3",
                    timeoutMs = 4000
                ).output
                if (prop.isNotBlank()) sb.append("· ").append(prop.trim().replace("\n", " / ")).append("  [").append(id).append("]\n")
            }
        }

        sb.append("\n【LSPosed 配置里是否登记简记】\n")
        val cfgHit = RootShell.run(
            "grep -a -l com.jianji.app /data/adb/lspd/config/* 2>/dev/null",
            timeoutMs = 8000
        ).output
        sb.append(
            if (cfgHit.isBlank()) {
                "没有任何配置文件提到 com.jianji.app ❌ → LSPosed 里这个模块的开关/作用域没保存成功\n"
            } else {
                "出现在这些配置里 ✅：\n$cfgHit"
            }
        )

        sb.append("\n【LSPosed 日志目录】\n")
        val logs = RootShell.run("ls -t /data/adb/lspd/log 2>/dev/null | head -6", timeoutMs = 5000).output
        sb.append(logs.trim().ifBlank { "（无 /data/adb/lspd/log，可能是其它分支/LSPatch）" }).append("\n")

        sb.append("\n【所有日志里含「简记」的行】\n")
        val hit = RootShell.run(
            "grep -a -h 简记 /data/adb/lspd/log/*.log 2>/dev/null | tail -n 20",
            timeoutMs = 10000
        ).output
        sb.append(
            hit.trim().ifBlank {
                "（无）→ 说明模块的入口类确实没跑\n" +
                    "      优先检查：LSPosed 里开关是否真的保存了、是否重启过、作用域是否包含 system"
            }
        )
        return sb.toString()
    }

    private fun isWatched(pkg: String): Boolean =
        pkg == PaymentParser.PKG_WECHAT || pkg == PaymentParser.PKG_ALIPAY ||
            PaymentParser.APP_NAMES.containsKey(pkg)
}
