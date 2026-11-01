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
    fun checkNow(ctx: Context, currentVersion: String, onDone: (String, UpdateChecker.Latest?) -> Unit) {
        val appCtx = ctx.applicationContext
        AppLog.i("更新", "手动检查更新（当前 $currentVersion）")
        check(appCtx, currentVersion, notifyIfNew = false) { msg, latest ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(msg, latest) }
        }
    }

    /** @param onDone 传 null 表示不回调（静默） */
    private fun check(
        ctx: Context,
        currentVersion: String,
        notifyIfNew: Boolean,
        onDone: ((String, UpdateChecker.Latest?) -> Unit)? = null
    ) {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                val latest = UpdateChecker.fetchLatest(currentVersion)
                Prefs.setLastUpdateCheckAt(ctx, System.currentTimeMillis())
                if (latest == null) {
                    AppLog.w("更新", "检查失败（网络/限流），稍后再试")
                    onDone?.invoke("检查失败：无法访问 GitHub（网络或限流）", null)
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
                    else "已是最新版本（$currentVersion）",
                    latest
                )
            } catch (t: Throwable) {
                AppLog.e("更新", t)
                onDone?.invoke("检查失败：${t.javaClass.simpleName}", null)
            } finally {
                running.set(false)
            }
        }
    }

    // ---------------- 应用内下载 + 调起安装 ----------------

    private val dlExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jianji-update-dl").apply { priority = Thread.MIN_PRIORITY }
    }

    /**
     * 下载 APK 并调起系统安装器（用户仍需在系统界面上点「安装」）。
     *
     * @param onProgress 0f..1f（负数表示不确定）
     * @param onDone 成功=null；失败=错误描述
     */
    fun downloadAndInstall(
        ctx: Context,
        latest: UpdateChecker.Latest,
        onProgress: (Float) -> Unit,
        onDone: (String?) -> Unit
    ) {
        val url = latest.apkUrl
        if (url.isNullOrBlank()) {
            onDone("这个版本没有提供 APK 下载，请到 Release 页面下载")
            return
        }
        val appCtx = ctx.applicationContext
        val dir = java.io.File(appCtx.getExternalFilesDir(null), "update").apply { mkdirs() }
        val name = latest.apkName.ifBlank { "jianji-${latest.version}.apk" }
        val outFile = java.io.File(dir, name)
        AppLog.i("更新", "开始下载 $name（${url}）")
        dlExecutor.execute {
            try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "JianJi/${latest.version}")
                }
                if (conn.responseCode != 200) {
                    onDone("下载失败：HTTP ${conn.responseCode}")
                    return@execute
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: latest.apkSize
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        var lastPct = -1
                        while (input.read(buf).also { read = it } > 0) {
                            output.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(done.toFloat() / total) }
                            }
                        }
                    }
                }
                runCatching { conn.disconnect() }
                AppLog.i("更新", "下载完成：${outFile.absolutePath}（${outFile.length()} 字节）")
                if (outFile.length() <= 0) { onDone("下载失败：文件为空"); return@execute }
                onProgress(1f)
                // 回到主线程调起安装器
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val err = installApk(appCtx, outFile)
                    onDone(err)
                }
            } catch (t: Throwable) {
                AppLog.e("更新", t)
                onDone("下载失败：${t.javaClass.simpleName}")
            }
        }
    }

    /** 通过 FileProvider 调起系统安装器；返回 null 表示已成功调起 */
    private fun installApk(ctx: Context, file: java.io.File): String? = runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, ctx.packageName + ".fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        Notifier.cancelUpdate(ctx)
        null
    }.getOrElse { t ->
        AppLog.e("更新", t)
        // 常见原因：系统未授予"安装未知应用"权限
        "无法调起安装器（请在系统设置里允许「简记」安装未知应用后重试）"
    }
}
