package com.jianji.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.jianji.app.core.RuntimePolicy
import com.jianji.app.util.Notifier
import com.jianji.app.util.Prefs
import com.jianji.app.util.RecognitionLog
import com.jianji.app.util.RootManager
import com.jianji.app.util.ServiceHealth
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **root 版自动读通知**（不需要 LSPosed、不需要无障碍、不需要通知使用权）。
 *
 * 原理：以 root 身份周期性执行 `dumpsys notification --noredact`，把通知栏里的
 * 消费信息读出来交给统一记账管道。息屏时不做任何事（省电），只在亮屏期间按设定间隔扫。
 *
 * 这是给「LSPosed 装不上/不生效」或「无障碍被系统杀掉」时准备的**兜底通道**。
 * 需要前台服务常驻（通知栏一条低优先级提示），用户可在设置里关掉。
 */
class RootScanService : Service() {

    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 门控：总开关、用户开关、无障碍状态任一不满足 → 立刻退出，绝不后台常驻
        if (!allowed()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.getAndSet(true)) return START_STICKY
        return try {
            startForeground(Notifier.ROOT_SCAN_ID, Notifier.rootScanNotification(this, "正在读取通知栏（root）"))
            RecognitionLog.add("root 自动读通知已启动")
            Thread({ loop() }, "jianji-root-scan").apply { isDaemon = true }.start()
            START_STICKY
        } catch (t: Throwable) {
            // Android 14 起后台启动前台服务会被拒；此时静默失败，不影响其它通道
            running.set(false)
            RecognitionLog.add("root 自动读通知启动失败：${t.message}")
            stopSelf()
            START_NOT_STICKY
        }
    }

    /** 是否允许运行：自动记账开 + 用户开了该功能 + 无障碍也开着（按用户要求：无障碍没开就不扫描） */
    private fun allowed(): Boolean = RuntimePolicy.rootScanEnabled(
        autoEnabled = Prefs.isAutoEnabled(this),
        rootScanOn = Prefs.isRootAutoScan(this),
        a11yEnabled = ServiceHealth.isAccessibilityEnabled(this)
    ) && RootManager.isRooted()

    private fun loop() {
        var lastCount = -1
        // 启动后先等一个完整间隔再扫，避免刚开机就连续两次重负载
        sleep(3_000L)
        while (running.get()) {
            try {
                // 每轮都重新确认门控：任一开关被关掉 / 无障碍被打开，立刻停止后台
                if (!allowed()) break
                val interval = Prefs.rootScanIntervalSec(this) * 1000L
                // 保活：无障碍被系统关掉时用 root 直接写回（更新 APK 后最常见的不记账原因）
                runCatching {
                    if (!ServiceHealth.isAccessibilityEnabled(this)) {
                        if (RootManager.enableAccessibility(this)) {
                            RecognitionLog.add("无障碍被系统关闭 → 已用 root 自动恢复")
                        }
                    }
                }
                if (isScreenOn()) {
                    val n = RootManager.scanNotifications(this)
                    if (n > 0) {
                        lastCount = n
                        Notifier.updateRootScanNotification(this, "已读取 $n 条相关通知，等待识别")
                    }
                    sleep(interval)
                } else {
                    // 息屏：完全不读通知栏（dumpsys 很重），低频空转
                    if (lastCount >= 0) {
                        Notifier.updateRootScanNotification(this, "息屏中，亮屏后继续读取")
                    }
                    sleep(10_000L)
                }
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                RecognitionLog.add("root 自动读通知异常：${t.message}")
                sleep(30_000L)
            }
        }
        running.set(false)
        stopSelf()
    }

    private fun isScreenOn(): Boolean = runCatching {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isInteractive
    }.getOrDefault(true)

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    override fun onDestroy() {
        running.set(false)
        super.onDestroy()
    }

    companion object {
        /** 按开关状态启动或停止（统一走 BackgroundWork 门控） */
        fun sync(ctx: Context) {
            com.jianji.app.util.BackgroundWork.syncRootScan(ctx)
        }
    }
}
