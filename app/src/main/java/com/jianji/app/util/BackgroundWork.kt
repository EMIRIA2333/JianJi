package com.jianji.app.util

import android.content.Context
import android.content.Intent
import com.jianji.app.core.HookProtocol
import com.jianji.app.core.RuntimePolicy

/**
 * 统一按 [RuntimePolicy] 启停两个前台服务。
 *
 * 目的：**开关没开就绝对不跑后台**。任何一处（设置页、开机、应用启动）
 * 都只调这里，避免出现"某个开关没开、服务却在跑"的耗电。
 */
object BackgroundWork {

    /** 按当前设置与运行状态同步两个服务的启停 */
    fun sync(ctx: Context) {
    AppLog.i("后台", "sync：按开关同步所有后台工作")
        syncRootScan(ctx)
        syncGuard(ctx)
    }

    fun syncRootScan(ctx: Context) {
        val allow = RuntimePolicy.rootScanEnabled(
            autoEnabled = Prefs.isAutoEnabled(ctx),
            rootScanOn = Prefs.isRootAutoScan(ctx),
            a11yEnabled = ServiceHealth.isAccessibilityEnabled(ctx)
        )
        runCatching {
            if (allow) {
                ctx.startForegroundService(Intent(ctx, com.jianji.app.service.RootScanService::class.java))
            } else {
                ctx.stopService(Intent(ctx, com.jianji.app.service.RootScanService::class.java))
            }
        }
    }

    fun syncGuard(ctx: Context) {
        val hookActive = HookProtocol.isActive(Prefs.hookActiveAt(ctx), System.currentTimeMillis())
        val canRestore = ServiceHealth.isAdbAuthorized(ctx) ||
            RootManager.rootedCached() == true
        val allow = RuntimePolicy.guardEnabled(
            autoEnabled = Prefs.isAutoEnabled(ctx),
            guardOn = Prefs.isHookGuard(ctx),
            hookActive = hookActive,
            canRestoreA11y = canRestore
        )
        runCatching {
            if (allow) {
                ctx.startForegroundService(Intent(ctx, com.jianji.app.service.HookGuardService::class.java))
            } else {
                ctx.stopService(Intent(ctx, com.jianji.app.service.HookGuardService::class.java))
            }
        }
    }

    /** 总开关关掉时：立刻停掉一切后台服务 */
    fun stopAll(ctx: Context) {
    AppLog.i("后台", "stopAll：停止所有后台工作")
        runCatching { ctx.stopService(Intent(ctx, com.jianji.app.service.RootScanService::class.java)) }
        runCatching { ctx.stopService(Intent(ctx, com.jianji.app.service.HookGuardService::class.java)) }
    }
}
