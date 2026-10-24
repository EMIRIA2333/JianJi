package com.jianji.app.util

import android.content.Context
import com.jianji.app.core.UpdateChecker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 更新检查的调度器（把"什么时候查、查到后怎么办"和网络逻辑分开）。
 *
 * 规则：
 * - 只在用户开启「检查更新」时work；默认**每 12 小时最多查一次**（避免无谓流量）；
 * - 全程在**后台线程**，失败静默（更新检查绝不影响记账）；
 * - 只读取 GitHub Releases 的公开版本号，不发送任何设备/账号/账本信息；
 * - 查到新版本时：缓存版本号 + 发一条通知（点开是 Release 页面，由用户自行下载安装）。
 */
object UpdateAgent {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jianji-update").apply { priority = Thread.MIN_PRIORITY }
    }
    private val running = AtomicBoolean(false)

    private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

    /** 后台静默检查（启动时调用，按间隔节流） */
    fun maybeCheck(ctx: Context, currentVersion: String) {
        if (!Prefs.isUpdateCheckEnabled(ctx)) return
        val now = System.currentTimeMillis()
        if (now - Prefs.lastUpdateCheckAt(ctx) < CHECK_INTERVAL_MS) return
        check(ctx.applicationContext, currentVersion, notifyIfNew = true)
    }

    /** 用户手动点「检查更新」：一定发请求，结果通过 [onDone] 回调（主线程） */
    fun checkNow(ctx: Context, currentVersion: String, onDone: (String) -> Unit) {
        val appCtx = ctx.applicationContext
        AppLog.i("更新", "手动检查更新（当前 $currentVersion）")
        check(appCtx, currentVersion, notifyIfNew = false) { msg ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(msg) }
        }
    }

    /** @param onDone 传 null 表示不回调（静默） */
    private fun check(
        ctx: Context,
        currentVersion: String,
        notifyIfNew: Boolean,
        onDone: ((String) -> Unit)? = null
    ) {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                val latest = UpdateChecker.fetchLatest(currentVersion)
                Prefs.setLastUpdateCheckAt(ctx, System.currentTimeMillis())
                if (latest == null) {
                    AppLog.w("更新", "检查失败（网络/限流），稍后再试")
                    onDone?.invoke("检查失败：无法访问 GitHub（网络或限流）")
                    return@execute
                }
                Prefs.setLatestVersion(ctx, latest.version)
                val newer = UpdateChecker.isNewer(latest.version, currentVersion)
                AppLog.i("更新", "最新=${latest.version} 当前=$currentVersion 有新版本=$newer")
                if (newer && notifyIfNew) {
                    Notifier.notifyUpdate(ctx, latest.version, latest.releaseUrl)
                }
                onDone?.invoke(
                    if (newer) "发现新版本 ${latest.version}（当前 $currentVersion），点这里去下载"
                    else "已是最新版本（$currentVersion）"
                )
            } catch (t: Throwable) {
                AppLog.e("更新", t)
                onDone?.invoke("检查失败：${t.javaClass.simpleName}")
            } finally {
                running.set(false)
            }
        }
    }
}
