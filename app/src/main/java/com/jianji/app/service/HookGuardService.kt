package com.jianji.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.jianji.app.util.Notifier
import com.jianji.app.util.Prefs
import com.jianji.app.util.RecognitionLog
import com.jianji.app.util.RootManager
import com.jianji.app.util.ServiceHealth

/**
 * **Hook 直读守护**：只做一件事 —— 让简记进程常驻。
 *
 * 为什么需要：Hook 模块把通知内容用**广播**发给简记。如果简记进程已经被系统清理，
 * 广播在某些 ROM（MIUI/HyperOS 等）上无法把进程拉起来，表现就是
 * **「打开应用才开始记账」**（其实是打开应用时的 root 扫描顺手补上了）。
 *
 * 这个前台服务本身不做轮询、不耗 CPU，只维持进程存活 + 定期检查无障碍是否被关掉。
 * 由用户在「自动记账 → Hook 模块 → 保持后台常驻」里开启，随时可关。
 */
class HookGuardService : Service() {

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 门控：总开关 + 用户开关 + "确实有需要守护的事"，任一不满足就不常驻
        if (!allowed()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        running = true
        return try {
            startForeground(Notifier.HOOK_GUARD_ID, Notifier.hookGuardNotification(this, "常驻中，Hook 通知随时可送达"))
            RecognitionLog.add("Hook 直读守护已启动（保证广播能送达）")
            Thread({ loop() }, "jianji-hook-guard").apply { isDaemon = true }.start()
            START_STICKY
        } catch (t: Throwable) {
            running = false
            RecognitionLog.add("Hook 守护启动失败：${t.message}")
            stopSelf()
            START_NOT_STICKY
        }
    }

    /** 只有"有它服务的事"时才允许常驻（Hook 生效需要进程收广播，或能自动恢复无障碍） */
    private fun allowed(): Boolean {
        val hookActive = com.jianji.app.core.HookProtocol.isActive(
            Prefs.hookActiveAt(this), System.currentTimeMillis()
        )
        val canRestore = ServiceHealth.isAdbAuthorized(this) || RootManager.rootedCached() == true
        return com.jianji.app.core.RuntimePolicy.guardEnabled(
            autoEnabled = Prefs.isAutoEnabled(this),
            guardOn = Prefs.isHookGuard(this),
            hookActive = hookActive,
            canRestoreA11y = canRestore
        )
    }

    /** 低频循环：每 5 分钟确认无障碍还在（被系统杀掉就恢复）。刻意放慢，避免自己成为耗电大户 */
    private fun loop() {
        var lastA11y = true
        while (running) {
            try {
                if (!allowed()) break
                val alive = ServiceHealth.isAccessibilityEnabled(this)
                if (!alive) {
                    // 先走便宜的路（已 ADB 授权时直接写系统设置），失败才动用 su
                    val restored = ServiceHealth.autoRestoreAccessibility(this) ||
                        (RootManager.rootedCached() == true && RootManager.enableAccessibility(this))
                    val n = Prefs.bumpA11yRestoreCount(this)
                    RecognitionLog.add(
                        if (restored) "无障碍被系统关闭 → 已自动恢复（第 $n 次）"
                        else "无障碍被系统关闭，自动恢复失败（第 $n 次）"
                    )
                    Notifier.updateHookGuardNotification(
                        this,
                        if (restored) "已自动恢复无障碍（第 $n 次）" else "无障碍被关掉了，请在设置里重新开启"
                    )
                    lastA11y = restored
                } else if (!lastA11y) {
                    lastA11y = true
                    Notifier.updateHookGuardNotification(this, "常驻中，Hook 通知随时可送达")
                }
                Thread.sleep(5 * 60 * 1000L)
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                try {
                    Thread.sleep(2 * 60 * 1000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        running = false
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        /** 按开关状态启动 / 停止（统一走 BackgroundWork 门控） */
        fun sync(ctx: Context) {
            com.jianji.app.util.BackgroundWork.syncGuard(ctx)
        }
    }
}
