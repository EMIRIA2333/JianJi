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

    private val memory = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null
    @Volatile private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        ready = true
        runCatching { file = File(ctx.filesDir, FILE_NAME) }
        i("日志", "===== 应用启动 =====")
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
        append("主题=${themeLabel(Prefs.themeMode(ctx))} 毛玻璃=${Prefs.glassMode(ctx)}")
    }

    /**
     * **安全模式**：上次启动没走到主界面就死了 → 本次跳过毛玻璃等可选功能。
     *
     * 目的：万一某个可选特性导致启动崩溃，用户至少还能打开应用（而不是永远打不开）。
     * 主界面第一次 onResume 会清掉这个标记。
     */
    fun enterSafeModeIfLastCrash(ctx: Context): Boolean {
        val crashed = runCatching { Prefs.sp(ctx).getBoolean(KEY_LAST_RUN_OK, true).not() }.getOrDefault(false)
        runCatching { Prefs.sp(ctx).edit().putBoolean(KEY_LAST_RUN_OK, false).apply() }
        if (crashed) {
            i("安全模式", "检测到上次启动未进入主界面 → 本次关闭毛玻璃等可选功能")
        }
        return crashed
    }

    /** 主界面起来了：标记本次启动正常 */
    fun markRunOk(ctx: Context) {
        runCatching { Prefs.sp(ctx).edit().putBoolean(KEY_LAST_RUN_OK, true).apply() }
    }

    private const val KEY_LAST_RUN_OK = "last_run_ok"
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
    fun shareFileIntent(ctx: Context): android.content.Intent? = runCatching {
        val text = fullText(ctx)
        val saved = saveToDownloads(ctx, text)
        val uri = saved?.first
            ?: export(ctx)?.let {
                androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", it)
            }
            ?: return null
        // 只放一小段正文做兜底（完整的在附件里），避免超大文本把意图撑坏
        val preview = text.takeLast(1500)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "简记运行日志")
            putExtra(android.content.Intent.EXTRA_TITLE, "简记运行日志")
            putExtra(android.content.Intent.EXTRA_TEXT, preview)
            clipData = android.content.ClipData.newUri(ctx.contentResolver, "简记日志", uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        i("日志", "分享文件：uri=$uri（保存位置=${saved?.second ?: "FileProvider"}）")
        android.content.Intent.createChooser(send, "分享日志").apply {
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.onFailure { e("日志", "生成分享意图失败：${it.message}") }.getOrNull()

    /** 只分享**文本**（不涉及文件，任何应用都收得下，最稳） */
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
    fun saveToDownloads(ctx: Context, text: String): Pair<android.net.Uri?, String>? = runCatching {
        val name = "jianji-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
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