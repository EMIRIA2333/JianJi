package com.jianji.app.util

import android.content.Context
import android.os.Build
import com.jianji.app.App
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内运行日志（可导出）。
 *
 * 目的：出问题时**你能一键把日志导出发我**，而不是只靠描述。
 *
 * 特性：
 * - 内存环形缓冲（最近 [MAX_MEMORY] 行）+ 落盘文件 `filesDir/jianji_log.txt`（滚动截断到 [MAX_FILE] 字节）；
 * - **永不抛异常**（所有写操作 runCatching），不会因为日志本身把应用搞崩；
 * - 记录：启动环境快照、主题/毛玻璃切换、毛玻璃实际生效细节、服务启停、识别与记账、未捕获崩溃堆栈；
 * - 导出：写入 `filesDir/export/` 并通过系统分享面板发给任何人（微信/邮件/文件管理器都行）。
 */
object AppLog {

    private const val MAX_MEMORY = 400
    private const val MAX_FILE = 256 * 1024
    private const val FILE_NAME = "jianji_log.txt"
    private const val TAG = "简记"

    /** 日志行开头是 `MM-dd HH:mm:ss`，取前 5 个字符（MM-dd）判断"是不是今天" */
    private val LINE_DAY = Regex("^[0-9]{2}-[0-9]{2}$")

    private val memory = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null
    @Volatile private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        ready = true
        runCatching { file = File(ctx.filesDir, FILE_NAME) }
        i("日志", "===== 应用启动 =====")
        runCatching { pruneOldDays(ctx) }
        // 每一段都单独兜异常：日志本身绝不能把启动搞崩
        runCatching { i("环境", environment(ctx)) }.onFailure { e("环境", it) }
        runCatching { i("设置", settingsSnapshot(ctx)) }.onFailure { e("设置", it) }
        installCrashHandler(ctx.applicationContext)
    }

    /**
     * 崩溃捕获：**除了写私有目录，还会往系统「下载/简记」写一份**。
     *
     * 为什么必须这样：如果应用启动就闪退，私有目录里的日志根本取不出来；
     * 写到系统媒体库后，你可以直接用「文件管理 → 下载/简记」把它发给我。
     */
    private fun installCrashHandler(appCtx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val detail = "线程 ${t.name} 未捕获异常\n${e.javaClass.name}: ${e.message}\n" + e.stackTraceToString()
                e("崩溃", detail)
                // 记录崩溃时间：下次启动据此进入安全模式（后台拉起的进程不会误触发）
                runCatching { Prefs.sp(appCtx).edit().putLong(KEY_LAST_CRASH_AT, System.currentTimeMillis()).apply() }
                writeCrashToDownloads(
                    appCtx,
                    "简记崩溃-${SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())}.txt"
                )
            }
            runCatching { prev?.uncaughtException(t, e) }
        }
    }

    /** 把最新日志（含崩溃堆栈）写成「下载/简记/xxx.txt」，应用打不开也能取出来 */
    private fun writeCrashToDownloads(ctx: Context, name: String) {
        val text = environment(ctx) + "\n----------------------------------------\n" + snapshot(MAX_MEMORY)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/简记"
                    )
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return@runCatching
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                val done = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                }
                ctx.contentResolver.update(uri, done, null, null)
            } else {
                val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "简记").apply { mkdirs() }
                File(dir, name).writeText(text)
            }
        }
    }

    /** 全部开关的快照：排查"设置被自动关掉"时，对比启动前后即可知道是不是丢设置 */
    fun settingsSnapshot(ctx: Context): String = buildString {
        append("设置快照: 总开关=${Prefs.isAutoEnabled(ctx)} 免密自动=${Prefs.isAutoSaveMianmi(ctx)} ")
        append("确认后保存=${Prefs.isConfirmBeforeSave(ctx)} 悬浮窗=${Prefs.isOverlayEnabled(ctx)} ")
        append("账单页补齐=${Prefs.isBillScanEnabled(ctx)} 短信=${Prefs.isSmsEnabled(ctx)} ")
        append("root扫描=${Prefs.isRootAutoScan(ctx)} 扫描间隔=${Prefs.rootScanIntervalSec(ctx)}s ")
        append("Hook常驻=${Prefs.isHookGuard(ctx)} 分类学习=${Prefs.isCategoryLearning(ctx)} ")
        append("主题=${themeLabel(Prefs.themeMode(ctx))} 毛玻璃=${Prefs.glassMode(ctx)} ")
        val lastAt = Prefs.lastNotifyAt(ctx)
        append("最近通知=${if (lastAt > 0) "${(System.currentTimeMillis() - lastAt) / 1000}s前(${Prefs.lastNotifyVia(ctx)})" else "从未"} ")
        append("Hook需重启=${runCatching { ServiceHealth.hookReloadNeeded(ctx) }.getOrDefault(false)}")
    }

    /**
     * **安全模式**：最近启动时**真的崩溃过** → 本次跳过毛玻璃等可选功能。
     *
     * ⚠️ 曾经的做法是"上次启动没走到主界面就当崩溃"——但简记的进程**经常被后台拉起**
     * （Hook 广播、通知动作、短信、开机…），这些启动永远不会进主界面，于是每次都被判成
     * "上次崩了"，安全模式**永久生效**：日志里连续 4 次「安全模式」就是这么来的，
     * 表现就是"毛玻璃开关是开的，但界面一点玻璃效果都没有"。
     *
     * 现在改成**只看崩溃处理器是否真的写过崩溃时间**，主界面成功启动后清除。
     * 这样：真崩了 → 下次启动降级可选功能；只是后台启动 → 完全不打扰。
     */
    fun enterSafeModeIfLastCrash(ctx: Context): Boolean {
        val at = runCatching { Prefs.sp(ctx).getLong(KEY_LAST_CRASH_AT, 0L) }.getOrDefault(0L)
        val recent = at > 0 && System.currentTimeMillis() - at < CRASH_SAFE_WINDOW_MS
        if (recent) {
            i("安全模式", "最近 24 小时内启动崩溃过 → 本次关闭毛玻璃等可选功能")
        }
        return recent
    }

    /** 主界面起来了：清除崩溃标记，下次启动不再降级 */
    fun markRunOk(ctx: Context) {
        runCatching { Prefs.sp(ctx).edit().putLong(KEY_LAST_CRASH_AT, 0L).apply() }
    }

    /** 崩溃标记有效期：超过一天就不再降级（避免一次偶发崩溃永久关掉毛玻璃） */
    private const val CRASH_SAFE_WINDOW_MS = 24L * 60 * 60 * 1000

    private const val KEY_LAST_CRASH_AT = "last_crash_at"
    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String) = write("E", tag, msg)
    fun e(tag: String, t: Throwable) = write("E", tag, "${t.javaClass.simpleName}: ${t.message}\n" + t.stackTraceToString())

    private fun write(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        synchronized(memory) {
            memory.addLast(line)
            while (memory.size > MAX_MEMORY) memory.removeFirst()
        }
        runCatching {
            val f = file ?: return
            if (f.length() > MAX_FILE) {
                // 超限就只留后半段，避免无限增长
                val tail = f.readLines().takeLast(MAX_MEMORY)
                f.writeText(tail.joinToString("\n") + "\n")
            }
            f.appendText(line + "\n")
        }
    }

    /** 内存里的最近日志（含落盘内容的一部分），供界面展示 */
    fun snapshot(limit: Int = 300): String = synchronized(memory) {
        memory.toList().takeLast(limit).joinToString("\n")
    }

    fun clear(ctx: Context) {
        synchronized(memory) { memory.clear() }
        runCatching { File(ctx.filesDir, FILE_NAME).writeText("") }
        i("日志", "日志已清空")
    }

    /** 导出一份完整日志到 filesDir/export/，返回文件（失败返回 null） */
    fun export(ctx: Context): File? = runCatching {
        val dir = File(ctx.filesDir, "export").apply { mkdirs() }
        val out = File(dir, "jianji-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
        out.writeText(fullText(ctx))
        i("日志", "已导出：${out.name}")
        out
    }.getOrNull()

    /** 环境快照：排查问题时第一眼要看的东西 */
    fun environment(ctx: Context): String = buildString {
        appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ROM: ${Build.DISPLAY}")
        val pi = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        }.getOrNull()
        val vName = pi?.versionName ?: "?"
        // longVersionCode 是 API 28+，低版本用 versionCode
        val vCode = if (Build.VERSION.SDK_INT >= 28) pi?.longVersionCode ?: 0L
        else (pi?.versionCode ?: 0).toLong()
        appendLine("版本: $vName (versionCode $vCode)")
        appendLine("主题: ${themeLabel(Prefs.themeMode(ctx))} / 毛玻璃: ${if (Prefs.isGlass(ctx)) "开" else "关"}")
        appendLine("分类学习: ${if (Prefs.isCategoryLearning(ctx)) "开" else "关"}")
        appendLine("总开关: ${if (Prefs.isAutoEnabled(ctx)) "开" else "关"} / 无障碍: ${if (ServiceHealth.isAccessibilityEnabled(ctx)) "已连" else "未连"}")
        appendLine("通知使用权: ${if (ServiceHealth.isNotificationListenerEnabled(ctx)) "有" else "无"}")
        appendLine("Hook 最近上报: ${if (Prefs.hookActiveAt(ctx) > 0) "${(System.currentTimeMillis() - Prefs.hookActiveAt(ctx)) / 1000}s 前" else "从未"}")
        appendLine("背景图: ${runCatching { com.jianji.app.ui.BannerBackgrounds.labelOf(ctx) }.getOrDefault("?")}")
        val dbv = runCatching { (ctx.applicationContext as App).dao.count() }.getOrDefault(-1)
        appendLine("账单条数: $dbv")
    }.trim()

    fun themeLabel(mode: Int) = when (mode) {
        1 -> "纯白"
        2 -> "纯黑"
        else -> "跟随系统"
    }

    /**
     * 分享日志**文件**。
     *
     * 关键教训（你反馈"发给好友显示文件不存在"）：
     * 以前用 `FileProvider` 的 URI —— 这个 URI 是**由简记进程提供**的；
     * 而分享面板一起，简记就被 MIUI 杀掉，对方去读文件时 provider 已经死了 → "文件不存在"。
     *
     * 现在改成：**先把日志写进系统媒体库的「下载/简记」**，
     * 再分享那个 **系统媒体 URI**（`content://media/...`）—— 由系统进程提供，
     * 简记被杀也不影响，而且任何应用都能读。取不到媒体库时再退回 FileProvider。
     */
    fun shareFileIntent(ctx: Context): android.content.Intent? =
        shareFileIntentOf(ctx, fullText(ctx), "jianji-log", "简记运行日志")

    /**
     * 分享**任意文本为文件**（Hook 诊断用）。
     *
     * 与运行日志完全一致的做法：先写进系统媒体库「下载/简记」，
     * 再分享**系统媒体 URI** —— 简记被杀也能读，任何应用都收得下。
     */
    fun shareFileIntentOf(
        ctx: Context,
        text: String,
        prefix: String,
        subject: String
    ): android.content.Intent? = runCatching {
        val saved = saveToDownloadsNamed(ctx, text, prefix)
        val uri = saved?.first
            ?: export(ctx)?.let {
                androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", it)
            }
            ?: return null
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
            putExtra(android.content.Intent.EXTRA_TITLE, subject)
            putExtra(android.content.Intent.EXTRA_TEXT, text.takeLast(1500))
            clipData = android.content.ClipData.newUri(ctx.contentResolver, subject, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        i("日志", "分享文件($subject)：uri=$uri（保存位置=${saved?.second ?: "FileProvider"}）")
        android.content.Intent.createChooser(send, "分享$subject").apply {
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.onFailure { e("日志", "生成分享意图失败：${it.message}") }.getOrNull()

    /** 只分享**文本**（不涉及文件，任何应用都收得下，最稳） */
    /** 分享任意文本（Hook 诊断用） */
    fun shareTextIntentOf(ctx: Context, subject: String, text: String): android.content.Intent =
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }

    fun shareTextIntent(ctx: Context): android.content.Intent = android.content.Intent(
        android.content.Intent.ACTION_SEND
    ).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "简记运行日志")
        putExtra(android.content.Intent.EXTRA_TEXT, fullText(ctx))
    }

    /**
     * 保存到系统「下载」目录（MediaStore，Android 10+ 无需任何权限），
     * 低版本写到应用外部目录。返回可读路径（失败返回 null）。
     */
    /**
     * 写入系统「下载/简记」目录。
     *
     * Android 10+ 用 MediaStore（**不需要任何存储权限**，且文件由系统进程提供，
     * 简记被杀也能被别的应用读到 —— 这是修"文件不存在"的关键）。
     * 低版本写到应用外部目录。
     *
     * @return (可分享的 URI, 人类可读位置)；失败返回 null
     */
    /** 保存自定义文件名的文本到「下载/简记」（Hook 诊断导出用） */
    fun saveToDownloadsNamed(ctx: Context, text: String, prefix: String): Pair<android.net.Uri?, String>? =
        saveToDownloadsInternal(ctx, text, prefix)

    fun saveToDownloads(ctx: Context, text: String): Pair<android.net.Uri?, String>? =
        saveToDownloadsInternal(ctx, text, "jianji-log")

    private fun saveToDownloadsInternal(ctx: Context, text: String, prefix: String): Pair<android.net.Uri?, String>? = runCatching {
        val name = "$prefix-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/简记"
                )
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            // 写完后取消 pending，其他应用才能读到
            val done = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            i("日志", "已保存到 下载/简记/$name")
            uri to "下载/简记/$name"
        } else {
            val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "简记").apply { mkdirs() }
            val out = File(dir, name)
            out.writeText(text)
            i("日志", "已保存到 ${out.absolutePath}")
            null to out.absolutePath
        }
    }.onFailure { e("日志", "保存到下载目录失败：${it.message}") }.getOrNull()

    /** 完整日志文本（导出/展示用） */
    /**
     * 「只保留当天」开启时，清掉日志文件里非今天的行。
     * 日志行格式固定以 `MM-dd HH:mm:ss` 开头，因此按前缀判断即可。
     */
    fun pruneOldDays(ctx: Context) {
        if (!Prefs.isLogKeepTodayOnly(ctx)) return
        val f = file ?: return
        if (!f.exists()) return
        val today = SimpleDateFormat("MM-dd", Locale.US).format(Date())
        val kept = f.readLines().filter { line ->
            val t = line.trim()
            // 保留非日志行（导出头、分隔线等）与今天的行
            !LINE_DAY.matches(t.substring(0, minOf(t.length, 8))) || t.startsWith(today)
        }
        f.writeText(kept.joinToString("\n"))
    }

    /** 供界面显示/导出：开启「只保留当天」时只返回今天的行（含头部） */
    fun visibleText(ctx: Context): String {
        val all = fullText(ctx)
        if (!Prefs.isLogKeepTodayOnly(ctx)) return all
        val today = SimpleDateFormat("MM-dd", Locale.US).format(Date())
        return all.lines().filter { line ->
            val t = line.trim()
            t.isEmpty() || t.startsWith("[") || !LINE_DAY.matches(t.substring(0, minOf(t.length, 8))) ||
                t.startsWith(today) || t.startsWith("简记运行日志导出") || t.startsWith("---")
        }.joinToString("\n")
    }

    fun fullText(ctx: Context): String {
        val head = buildString {
            appendLine("简记运行日志导出")
            appendLine(environment(ctx))
            appendLine("----------------------------------------")
        }
        val disk = runCatching { file?.readText().orEmpty() }.getOrDefault("")
        return head + disk + "\n\n[内存缓冲]\n" + snapshot(MAX_MEMORY)
    }

    fun copyToClipboard(ctx: Context, text: String): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("简记日志", text))
        true
    }.getOrDefault(false)
}