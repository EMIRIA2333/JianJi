package com.jianji.app.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.jianji.app.App
import com.jianji.app.core.BillListParser
import com.jianji.app.core.Categories
import com.jianji.app.core.ChatPaymentParser
import com.jianji.app.core.PaymentParser
import com.jianji.app.core.ReceiptParser
import com.jianji.app.core.Record
import com.jianji.app.core.RecordPolicy
import com.jianji.app.core.RecordType
import com.jianji.app.core.ScanPolicy
import com.jianji.app.core.ServiceMessageParser
import com.jianji.app.db.RecordDao
import com.jianji.app.ui.EditRecordActivity
import com.jianji.app.ui.overlay.RecordOverlay
import com.jianji.app.util.CategoryLearner
import com.jianji.app.util.Money
import com.jianji.app.util.Notifier
import com.jianji.app.util.PendingRecord
import com.jianji.app.util.Prefs
import com.jianji.app.util.RecognitionLog
import com.jianji.app.util.RecordWriter
import com.jianji.app.util.ServiceHealth
import com.jianji.app.util.TimeUtil
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 无障碍自动记账服务。
 *
 * 识别来源：
 *  1. 通知（含免密/自动扣款、支付成功、收款到账等）；
 *  2. 支付成功页 / 账单详情页（[ReceiptParser]：金额可为无 ¥ 的纯数字，含支付时间、商户、分类、**支付方式**）；
 *  3. **账单列表页**（[BillListParser]）：补齐那些**不弹通知**的账单。
 *
 * 确认方式：
 *  - 开启「悬浮窗提醒」时，直接在支付页面上浮出卡片，可**当场改分类**并保存/修改/忽略；
 *  - 否则回落到通知栏的「保存 / 修改」「忽略 / 继续记录」按钮；
 *  - 悬浮窗 10 秒无操作会自动收起并转为通知，不会丢失。
 */
class AutoCaptureService : AccessibilityService() {

    private val lastWindowScan = AtomicLong(0L)
    private val lastContentScan = AtomicLong(0L)
    private val lastBillScan = AtomicLong(0L)

    /** 连续未识别到账单的次数（用于自适应降频） */
    private val missStreak = AtomicInteger(0)

    /** 上一次解析过的页面内容哈希（页面没变就不重复解析） */
    @Volatile
    private var lastPageHash = 0

    /** 息屏判断的缓存（避免高频 IPC） */
    @Volatile
    private var interactiveCached = true
    @Volatile
    private var interactiveCheckedAt = 0L

    /** 系统省电模式缓存 */
    @Volatile
    private var saverCached = false
    @Volatile
    private var saverCheckedAt = 0L

    /** 低电量判断缓存 */
    @Volatile
    private var lowBatCached = false
    @Volatile
    private var lowBatCheckedAt = 0L

    /** 状态通知里的「今日已记」数字，避免重复刷新 */
    @Volatile
    private var lastStatusCount = -1

    /** 上次记录「页面未识别」的时间（限流，避免刷屏） */
    @Volatile
    private var lastMissLogAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: RecordOverlay? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 门控：总开关关掉 → 一切扫描都不做（后台零开销）
        if (!Prefs.isAutoEnabled(this)) return
        val pkg = event.packageName?.toString() ?: return
        if (isSelf(pkg)) return
        if (!isWatchedPkg(pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> handleNotification(event, pkg)
            // 页面识别只针对支付类应用：购物/外卖等应用的商品页、订单页不是账单，
            // 曾出现「淘宝商品价格被批量当成账单」的问题
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                if (isPageScanPkg(pkg)) handleWindow(event, pkg, windowScan = true)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (isPageScanPkg(pkg)) handleWindow(event, pkg, windowScan = false)
        }
    }

    /** 自己的界面不需要扫描（也省一次遍历） */
    private fun isSelf(pkg: String): Boolean = pkg == packageName

    private fun isWatchedPkg(pkg: String): Boolean =
        pkg == PaymentParser.PKG_WECHAT || pkg == PaymentParser.PKG_ALIPAY ||
            PaymentParser.APP_NAMES.containsKey(pkg)

    /** 允许做页面识别（账单页/支付成功页）的应用 */
    private fun isPageScanPkg(pkg: String): Boolean = BillListParser.BILL_SOURCE_PKGS.contains(pkg)

    // ---------------- 通知 ----------------

    /**
     * 无障碍通知事件：只负责把标题/正文取出来，剩下的解析与记账统一交给
     * [NotifyPipe]（与「通知读取」通路共用，内容相同只记一次）。
     */
    private fun handleNotification(event: AccessibilityEvent, pkg: String) {
        var title = ""
        var text = ""
        var notifyAt = 0L
        try {
            val n = event.parcelableData as? Notification
            if (n != null) {
                title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                text = listOfNotNull(
                    n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                    n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ).joinToString("\n")
                notifyAt = runCatching { n.`when` }.getOrDefault(0L)
            }
        } catch (_: Exception) {
            // 部分 ROM 上 extras 读取异常，回退 ticker
        }
        if (text.isBlank()) {
            title = ""
            text = event.text?.joinToString("\n") { it?.toString().orEmpty() }.orEmpty()
        }
        if (title.isBlank() && text.isBlank()) return
        NotifyPipe.handle(this, pkg, title, text, "无障碍", if (notifyAt > 0) notifyAt else event.eventTime)
    }

    // ---------------- 页面 ----------------

    private fun handleWindow(event: AccessibilityEvent, pkg: String, windowScan: Boolean) {
        val now = System.currentTimeMillis()

        // ① 事件文本预筛（纯 CPU，最便宜）：内容变化事件占绝大多数（滚动/打字/动画都会触发），
        //    若事件自带的变更文本与钱无关，直接跳过整棵树遍历 —— 这是最省电的一步
        if (!windowScan && !ScanPolicy.looksInteresting(eventTextOf(event))) return

        // ② 间隔与自适应退避（一次原子读 + 比较）；系统省电 / 低电量未充电时进一步降频
        val batterySaver = isBatterySaverOn() || isLowBattery()
        val powerSave = Prefs.isPowerSave(this) || batterySaver
        var interval = ScanPolicy.intervalFor(windowScan, powerSave, missStreak.get(), batterySaver)
        // 空文本的内容变化事件（WebView 动画/光标闪烁等）信号价值低，再翻倍间隔
        if (!windowScan && eventTextOf(event).isNullOrEmpty()) interval *= 2
        val lastRef = if (windowScan) lastWindowScan else lastContentScan
        val last = lastRef.get()
        if (now - last < interval) return
        if (!lastRef.compareAndSet(last, now)) return

        // ③ 息屏时不做页面扫描（isInteractive 是 IPC，做了 2 秒缓存）
        if (!isInteractive()) return

        // ④ 后台线程已积压时主动放弃本次扫描（下次事件再来），避免任务雪崩
        val app = application as App
        if (app.isWorkerBusy()) return

        val root = rootInActiveWindow ?: return
        val rootPkg = root.packageName?.toString() ?: return
        if (rootPkg != pkg) return

        // ⑤ 一次遍历直接拼成文本（省掉中间 List/join 的分配），并按事件类型限制节点数
        val joined = collectText(root, ScanPolicy.nodeLimit(windowScan))
        if (joined.length < 4) return

        // ⑥ 页面内容没变化就不重复解析（滚动停止后的事件尤其多）
        val hash = joined.hashCode()
        if (hash == lastPageHash) {
            noteScanResult(false)
            return
        }
        lastPageHash = hash

        app.post {
            // 1) 账单详情页 / 支付成功页：精确解析（金额、时间、分类、支付方式）
            val receipt = ReceiptParser.parse(pkg, joined)
            if (receipt != null) {
                val record = Record(
                    amount = receipt.amount,
                    type = receipt.type,
                    category = rememberCategory(
                        app, receipt.merchant, receipt.type, receipt.categoryHint,
                        allowMemory = receipt.platformCategory == null
                    ),
                    note = receipt.merchant,
                    tag = if (Prefs.isExtractRemark(this)) receipt.remark else "",
                    payMethod = receipt.payMethod,
                    source = PaymentParser.sourceOf(pkg),
                    time = receipt.time ?: now
                )
                RecognitionLog.add(
                    "${PaymentParser.platformName(pkg)} 账单页 → " +
                        "${if (record.type == RecordType.INCOME) "收入" else "支出"} ${Money.plain(record.amount)} · ${record.displayName}"
                )
                dispatch(app, record, joined, pkg, withImage = true)
                noteScanResult(true)
                return@post
            }

            // 2) 聊天页：只认转账 / 红包气泡（逐笔，方向由气泡状态决定，不会记反）
            val chatEntries = ChatPaymentParser.parse(pkg, joined)
            if (chatEntries.isNotEmpty()) {
                importChatEntries(chatEntries, pkg, joined)
                noteScanResult(true)
                return@post
            }

            // 3) 应用内支付消息列表：不弹通知的免密支付 / 自动扣款（支付宝交易提醒、微信支付）
            val messages = ServiceMessageParser.parse(pkg, joined)
            if (messages.isNotEmpty()) {
                importMessages(messages, pkg, joined)
                noteScanResult(true)
                return@post
            }

            // 4) 账单列表页：补齐「不弹通知」的账单
            if (Prefs.isBillScanEnabled(this)) {
                val billLast = lastBillScan.get()
                if (now - billLast >= ScanPolicy.BILL_INTERVAL_MS && lastBillScan.compareAndSet(billLast, now)) {
                    val entries = BillListParser.parse(pkg, joined, now)
                    if (entries.isNotEmpty()) {
                        importBillEntries(entries, pkg, joined)
                        noteScanResult(true)
                        return@post
                    }
                }
            }

            // 5) 通用规则兜底：必须是「像账单页」的页面（含支付时间/交易单号/商户等字段），
            //    避免把普通列表页里的数字当成金额
            if (countReceiptLabels(joined) < 2) {
                logPageMiss(pkg, joined, "不像账单页")
                noteScanResult(false)
                return@post
            }
            val parsed = PaymentParser.parse(pkg, "", joined, strict = true)
            if (parsed == null) {
                logPageMiss(pkg, joined, "页面没有明确收付状态")
                noteScanResult(false)
                return@post
            }
            val record = Record(
                amount = parsed.amount,
                type = parsed.type,
                category = rememberCategory(
                    app, parsed.merchant, parsed.type,
                    CategoryLearner.suggest(this, parsed.merchant, parsed.type == RecordType.INCOME, joined, pkg),
                    allowMemory = true
                ),
                note = "",
                merchant = parsed.merchant,
                tag = if (Prefs.isExtractRemark(this)) ReceiptParser.extractRemark(joined) else "",
                payMethod = parsed.payMethod,
                source = PaymentParser.sourceOf(pkg),
                time = now
            )
            dispatch(app, record, joined, pkg, withImage = false)
            noteScanResult(true)
        }
    }

    /**
     * 商户记忆（本地「智能匹配」）：这个商户以前被你改过分类，就沿用上次的分类。
     * 平台自带的账单分类是权威值（allowMemory = false），不会被历史覆盖。
     */
    private fun rememberCategory(
        app: App,
        note: String,
        type: Int,
        guessed: String,
        allowMemory: Boolean
    ): String {
        if (!allowMemory || note.isBlank()) return guessed
        val remembered = runCatching { app.dao.lastCategoryFor(note, type) }.getOrNull()
        return if (!remembered.isNullOrBlank()) remembered else guessed
    }

    /** 应用内支付消息列表：按条数同步入库（方向由状态词唯一确定），并给「撤销」入口 */
    private fun importMessages(entries: List<ServiceMessageParser.Entry>, pkg: String, rawText: String) {
        val app = application as App
        syncByCount(
            app = app,
            pkg = pkg,
            rawText = rawText,
            tag = "msg",
            label = "简记 · 支付消息",
            rows = entries.map {
                SyncRow(
                    type = it.type,
                    amount = it.amount,
                    note = "",
                    merchant = it.merchant,
                    payMethod = it.payMethod,
                    category = CategoryLearner.suggest(
                        this, it.merchant, it.type == RecordType.INCOME, rawText, pkg,
                        note = "", amount = it.amount
                    )
                )
            }
        )
    }

    /** 聊天页的转账 / 红包：按条数同步入库（金额、方向、分类都由气泡状态确定） */
    private fun importChatEntries(entries: List<ChatPaymentParser.Entry>, pkg: String, rawText: String) {
        val app = application as App
        syncByCount(
            app = app,
            pkg = pkg,
            rawText = rawText,
            tag = "chat",
            label = "简记 · 转账 / 红包",
            rows = entries.map {
                SyncRow(
                    type = it.type,
                    amount = it.amount,
                    note = it.note,
                    payMethod = it.payMethod,
                    category = it.category
                )
            }
        )
    }

    /** 一条待同步的记录（note = 备注 / 商户名，merchant 单独一份更好区分） */
    private data class SyncRow(
        val type: Int,
        val amount: Double,
        val note: String,
        val payMethod: String,
        val category: String,
        /** 商户 / 对方名称（聊天转账就是对方名字） */
        val merchant: String = note
    )

    /**
     * 按「页面上有几条」同步入库：库里少于页面条数就补差，多了不动。
     *
     * 这样两种情况都对：
     * - 同一商户每天扣同样金额、或反复测同样的小额转账 → 页面上是两个气泡，库里就该有两条；
     * - 反复扫描同一页 → 页面条数没变，不会重复记。
     */
    private fun syncByCount(
        app: App,
        pkg: String,
        rawText: String,
        tag: String,
        label: String,
        rows: List<SyncRow>
    ) {
        if (rows.isEmpty()) return
        // 同一页面在短时间内被多次事件扫到：只处理一次。
        // 用**完整文本**做键（之前只取前 1200 字，聊天页尾部的新气泡可能落在截断之外，
        // 导致新页面与旧页面算出同一个键而被静默跳过）
        if (app.deduper.duplicateText("$tag|$rawText", SYNC_WINDOW_MS)) return

        val since = System.currentTimeMillis() - SYNC_WINDOW_MS
        var added = 0
        val ids = ArrayList<Long>()
        rows.groupBy { "${it.type}|${Math.round(it.amount * 100)}|${it.note}" }.forEach { (_, group) ->
            val head = group.first()
            val existing = runCatching {
                app.dao.countSimilar(head.amount, head.type, head.note, since)
            }.getOrDefault(0)
            val need = (group.size - existing).coerceIn(0, MAX_PER_GROUP)
            repeat(need) {
                val rec = Record(
                    amount = head.amount,
                    type = head.type,
                    category = rememberCategory(app, head.merchant, head.type, head.category, allowMemory = true),
                    note = "",
                    merchant = head.merchant,
                    payMethod = head.payMethod,
                    source = PaymentParser.sourceOf(pkg),
                    time = System.currentTimeMillis()
                )
                val id = runCatching { app.dao.insert(rec) }.getOrDefault(-1L)
                if (id > 0) {
                    added++
                    ids.add(id)
                }
            }
        }
        // 一条都没新增时也写进识别记录，方便排查「为什么没记」
        if (added == 0) {
            val detail = rows.groupBy { "${it.type}|${Math.round(it.amount * 100)}|${it.note}" }
                .values.joinToString("；") { g ->
                    val h = g.first()
                    "${if (h.type == RecordType.INCOME) "收入" else "支出"} ${Money.plain(h.amount)}·${h.note}×${g.size}"
                }
            RecognitionLog.add("$label 未新增（近 2 分钟内已记过）：$detail")
        }
        if (added > 0) {
            runCatching { RecordWriter.checkBudget(this) }
            Notifier.notifyImported(this, label, "记好了 $added 笔，点「撤销」可反悔", ids.toLongArray())
            refreshStatusNotification()
        }
    }

    /** 更新常驻状态通知的「今日已记 N 笔」（数字没变就不重复发通知） */
    private fun refreshStatusNotification() {
        if (!Prefs.isStatusNotification(this)) return
        val app = application as App
        val start = TimeUtil.dayStart(System.currentTimeMillis())
        app.post {
            val n = runCatching { app.dao.countInRange(start, start + DAY_MS) }.getOrDefault(0)
            if (n != lastStatusCount) {
                lastStatusCount = n
                Notifier.updateStatusNotification(this, true, n)
            }
        }
    }

    // ---------------- 账单页面截图（「账单图片」开关） ----------------

    /**
     * 截图当前账单页面并压缩保存（Android 11+ 无障碍服务的 takeScreenshot，无需额外权限）。
     * 失败或系统版本过低就回调空路径，绝不影响记账本身。
     */
    private fun capturePageImage(app: App, onDone: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onDone("")
            return
        }
        runCatching { takeScreenshotApi30(app, onDone) }.onFailure { onDone("") }
    }

    /** Android 11+ 才有无障碍截图 API（调用前已做版本判断） */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshotApi30(app: App, onDone: (String) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val path = runCatching {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer, screenshot.colorSpace
                        )?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        bitmap?.let { saveShot(app, it) }.orEmpty()
                    }.getOrDefault("")
                    onDone(path)
                }

                override fun onFailure(errorCode: Int) {
                    onDone("")
                }
            }
        )
    }

/** 压缩保存（最长边 1080、JPEG 75），返回相对 filesDir 的路径 */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun saveShot(app: App, src: Bitmap): String = runCatching {
        val maxSide = 1080
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src, (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1), true
            )
        } else {
            src
        }
        val dir = File(app.filesDir, "shots").apply { mkdirs() }
        val name = "shot_${System.currentTimeMillis()}.jpg"
        FileOutputStream(File(dir, name)).use { scaled.compress(Bitmap.CompressFormat.JPEG, 75, it) }
        if (scaled !== src) scaled.recycle()
        src.recycle()
        "shots/$name"
    }.getOrDefault("")

    /**
     * 系统是否处于全局省电模式（10 秒 TTL 缓存，避免高频 IPC）。
     */
    private fun isBatterySaverOn(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - saverCheckedAt < 10_000) return saverCached
        saverCheckedAt = now
        saverCached = runCatching {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            pm.isPowerSaveMode
        }.getOrDefault(false)
        return saverCached
    }

    /** 记录一次扫描是否有收获：没收获则逐步退避；有收获则短暂冷却（省电且无副作用） */
    private fun noteScanResult(hit: Boolean) {
        if (hit) {
            missStreak.set(0)
            // 刚记完一笔（悬浮窗/通知正在显示），接下来 3 秒没必要再扫
            lastContentScan.addAndGet(HIT_COOLDOWN_MS)
            lastWindowScan.addAndGet(HIT_COOLDOWN_MS / 3)
        } else {
            missStreak.incrementAndGet()
        }
    }

    /**
     * 低电量且未充电（≤15%）：自动按省电模式降频。
     * 用 BatteryManager 直接读属性并做 60 秒缓存，避免频繁 IPC。
     */
    private fun isLowBattery(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lowBatCheckedAt < 60_000) return lowBatCached
        lowBatCheckedAt = now
        lowBatCached = runCatching {
            val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            !bm.isCharging && level in 0..15
        }.getOrDefault(false)
        return lowBatCached
    }

    /**
     * 记一条「页面没识别到」的排查信息。
     * 只在与钱相关、且两次记录间隔超过 20 秒时记录，避免刷屏。
     */
    private fun logPageMiss(pkg: String, text: String, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastMissLogAt < 20_000) return
        if (!ScanPolicy.looksInteresting(text)) return
        lastMissLogAt = now
        val hint = when {
            text.contains("红包") -> "页面有「红包」字样"
            text.contains("转账") -> "页面有「转账」字样"
            text.contains("免密") || text.contains("扣款") -> "页面有「免密/扣款」字样"
            else -> text.take(30)
        }
        RecognitionLog.add("${PaymentParser.platformName(pkg)} 页面未记账：$reason（$hint）")
    }

    /** 事件携带的变更文本（用于预筛，避免为无关页面遍历节点树） */
    private fun eventTextOf(event: AccessibilityEvent): CharSequence? {
        val t = event.text
        if (t != null && t.isNotEmpty()) {
            return if (t.size == 1) t[0] else t.joinToString(" ")
        }
        return event.contentDescription
    }

    /**
     * 屏幕是否点亮且可交互。`isInteractive()` 是一次 IPC，这里做 2 秒 TTL 缓存，
     * 避免高频内容事件把它问成耗电热点。
     */
    private fun isInteractive(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - interactiveCheckedAt < INTERACTIVE_TTL_MS) return interactiveCached
        interactiveCheckedAt = now
        interactiveCached = runCatching {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            pm.isInteractive
        }.getOrDefault(true)
        return interactiveCached
    }

    /** 账单页常见字段，用于确认「这确实是一个账单页面」 */
    private fun countReceiptLabels(text: String): Int = RECEIPT_LABELS.count { text.contains(it) }

    // ---------------- 账单页补齐 ----------------

    private fun importBillEntries(entries: List<BillListParser.Entry>, pkg: String, rawText: String) {
        val app = application as App
        // 同一页只补一次（用完整文本，避免截断后新旧页面撞成同一个键）
        if (app.deduper.duplicateText("bill|$rawText", SYNC_WINDOW_MS)) return

        val days = Prefs.billScanDays(this)
        val since = System.currentTimeMillis() - days * DAY_MS
        var added = 0
        val addedIds = ArrayList<Long>()
        entries.forEach { e ->
            if (e.time < since) return@forEach
            // 近 5 分钟内已有同金额+同方向+同商户的才跳过（原来用 7 天，会把
            // 「同一商户每天扣同样金额」和反复测试的记录全挡住）
            if (app.dao.existsSimilar(e.amount, e.type, e.merchant, System.currentTimeMillis() - SYNC_WINDOW_MS)) return@forEach
            val rec = Record(
                amount = e.amount,
                type = e.type,
                category = CategoryLearner.suggest(this, e.merchant, e.type == RecordType.INCOME, rawText, pkg),
                note = "",
                merchant = e.merchant,
                payMethod = e.payMethod,
                source = PaymentParser.sourceOf(pkg),
                time = e.time
            )
            val id = app.dao.insert(rec)
            if (id > 0) {
                added++
                addedIds.add(id)
            }
        }
        if (added > 0) {
            RecordWriter.checkBudget(this)
            // 带「撤销」按钮：误识别可一键还原（移入回收站）
            Notifier.notifyBillImport(this, added, days, addedIds.toLongArray())
        }
    }

    // ---------------- 分流：悬浮窗 / 通知 / 直接入库 ----------------

    /**
     * @param withImage 「账单图片」开启时先截一张账单页面，随待确认账单一起保存
     */
    private fun dispatch(app: App, record: Record, rawText: String, pkg: String, withImage: Boolean = false) {
        if (withImage && Prefs.isBillImage(this)) {
            capturePageImage(app) { path ->
                app.post { dispatchInternal(app, record.copy(imagePath = path), rawText, pkg) }
            }
            return
        }
        dispatchInternal(app, record, rawText, pkg)
    }

    private fun dispatchInternal(app: App, record: Record, rawText: String, pkg: String) {
        val merchant = record.displayName
        // 同一页文本只处理一次（防止同一个页面被多次事件重复记账）
        if (app.deduper.duplicateText(rawText)) return

        // 与近期记录相同只是「提示」信息，**不再作为拦截条件**：
        // 同一人同金额的多笔（收款 + 付款 + 红包）本来就是三笔真实交易
        val similar = app.deduper.duplicate(record.type, record.amount, merchant)

        // 统一决策：默认直接记账（带撤销）；免密支付即使开了确认也直接记。
        // 「与近期雷同」只影响通知文案，绝不停下记账（曾经因此静默丢单）
        val decision = RecordPolicy.decide(
            confirmBeforeSave = Prefs.isConfirmBeforeSave(this),
            autoSaveMianmi = Prefs.isAutoSaveMianmi(this),
            isMianmi = PaymentParser.isMianmi(rawText)
        )
        if (decision == RecordPolicy.SAVE_DIRECT) {
            saveWithUndo(app, record, similar)
            return
        }

        if (!app.deduper.markPending(record.type, record.amount, merchant)) return

        val pending = PendingRecord(
            amount = record.amount,
            type = record.type,
            merchant = merchant,
            category = record.category,
            time = record.time,
            source = record.source,
            similar = similar,
            payMethod = record.payMethod,
            imagePath = record.imagePath,
            remark = record.tag
        )

        if (Prefs.isOverlayEnabled(this) && isSourceAppForeground(pkg)) {
            showOverlay(app, pending, record, pkg)
            return
        }

        fallbackNotify(app, pending, record)
    }

    /** 免密 / 自动扣款 / 代扣 / 续费：钱已经扣了，直接入库 */
    private fun isMianmiPayment(text: String): Boolean = PaymentParser.isMianmi(text)

    /**
     * 直接入库 + 「撤销」通知（默认路径：识别到就记上，误记一键反悔）。
     * @param similar 是否与近期记录雷同（只在文案里提示，不拦截）
     */
    private fun saveWithUndo(app: App, record: Record, similar: Boolean = false) {
        app.post {
            val id = runCatching { RecordWriter.insert(this, record) }.getOrDefault(-1L)
            app.deduper.clearPending(record.type, record.amount, record.displayName)
            if (id > 0) {
                val sign = if (record.type == RecordType.INCOME) "+" else "-"
                val tail = if (similar) "（与近期账单雷同，记错了就点撤销）" else "，点「撤销」可反悔"
                Notifier.notifyImported(
                    this,
                    "简记 · 已记账",
                    "记好了 $sign${Money.plain(record.amount)} · ${record.category}$tail",
                    longArrayOf(id)
                )
                refreshStatusNotification()
            } else {
                // 入库失败（极少见）：至少退回通知，别静默丢账
                Notifier.notifyPendingSave(this, PendingRecord(
                    amount = record.amount, type = record.type, merchant = record.displayName,
                    category = record.category, time = record.time, source = record.source,
                    payMethod = record.payMethod, imagePath = record.imagePath, remark = record.tag
                ))
            }
        }
    }

    /** 通知 / 直接入库的兜底路径（悬浮窗不可用时使用） */
    private fun fallbackNotify(app: App, pending: PendingRecord, record: Record) {
        when {
            pending.similar -> Notifier.notifyDuplicate(this, pending)
            Prefs.isConfirmBeforeSave(this) -> Notifier.notifyPendingSave(this, pending)
            else -> saveNow(app, record)
        }
    }

    /** 悬浮窗：显示当前分类 + 该商户的历史分类 + 常用分类，可当场改 */
    private fun showOverlay(app: App, pending: PendingRecord, record: Record, pkg: String) {
        // 分类候选需要在工作线程查库，视图创建与 addView 必须回到主线程（ViewRootImpl 需要 Looper）
        val suggestions = categorySuggestions(app, record, pkg)
        mainHandler.post {
            val ov = overlay ?: RecordOverlay(this).also { overlay = it }
            val ok = ov.show(pending, suggestions, object : RecordOverlay.Listener {
                override fun onSave(category: String, payMethod: String) {
                    val finalRecord = record.copy(
                        category = category.ifBlank { record.category },
                        payMethod = payMethod
                    )
                    if (pending.similar) {
                        app.deduper.remove(record.type, record.amount, record.displayName)
                    }
                    saveNow(app, finalRecord)
                }

                override fun onEdit(category: String) {
                    app.deduper.clearPending(record.type, record.amount, record.displayName)
                    val p = pending.copy(category = category.ifBlank { pending.category })
                    runCatching {
                        startActivity(EditRecordActivity.pendingIntent(this@AutoCaptureService, p))
                    }.onFailure {
                        // 部分 ROM 限制后台弹出界面：退化为通知，用户点通知进入编辑
                        Notifier.notifyPendingSave(this@AutoCaptureService, pending)
                    }
                }

                override fun onIgnore() {
                    app.post {
                        app.deduper.clearPending(record.type, record.amount, record.displayName)
                        app.deduper.remove(record.type, record.amount, record.displayName)
                    }
                }

                override fun onTimeout() {
                    // 收起后转通知；同时清掉「待处理」标记，
                    // 否则同一商户紧接着的第二笔相同金额会被静默吞掉
                    app.post { app.deduper.clearPending(record.type, record.amount, record.displayName) }
                    Notifier.notifyPendingSave(this@AutoCaptureService, pending)
                }
            })
            // 悬浮窗没能加成功（例如 MIUI 未授予「后台弹出界面」）：必须退化为通知，绝不静默丢账
            if (!ok) fallbackNotify(app, pending, record)
        }
    }

    /** 分类候选：当前分类 → 该商户历史常用分类 → 常用分类补足 */
    private fun categorySuggestions(app: App, record: Record, pkg: String): List<String> {
        val out = LinkedHashSet<String>()
        if (record.category.isNotBlank()) out.add(record.category)

        if (record.displayName.isNotBlank()) {
            runCatching {
                val history = app.dao.search(RecordDao.Query(text = record.displayName), 40)
                history.asSequence()
                    .filter { it.type == record.type }
                    .groupingBy { it.category }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(2)
                    .forEach { out.add(it.key) }
            }
        }

        val defaults = if (record.type == RecordType.INCOME) {
            listOf("收款", "红包", "退款", "工资", "其他")
        } else {
            listOf("餐饮", "交通", "购物", "日用", "娱乐", "其他")
        }
        defaults.forEach { if (out.size < 5) out.add(it) }
        return out.toList()
    }

    private fun saveNow(app: App, record: Record) {
        app.post {
            val id = runCatching { RecordWriter.insert(this, record) }.getOrDefault(-1L)
            app.deduper.clearPending(record.type, record.amount, record.displayName)
            if (id > 0) {
                RecognitionLog.add("已记账：${if (record.type == RecordType.INCOME) "收入" else "支出"} ${Money.plain(record.amount)} · ${record.displayName}")
                Notifier.notifyRecord(this, record, record.displayName)
                refreshStatusNotification()
            } else {
                RecognitionLog.add("入库失败（数据库异常）：${record.displayName}")
            }
        }
    }

    /** 当前前台窗口是否就是来源应用（决定能否直接把悬浮卡片盖在支付页面上） */
    private fun isSourceAppForeground(pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.packageName?.toString() == pkg
    }

    /** 事件时间(uptime) 换算成墙钟时间 */
    private fun eventTimeToWallClock(event: AccessibilityEvent): Long {
        val eventUptime = event.eventTime
        if (eventUptime <= 0) return System.currentTimeMillis()
        return System.currentTimeMillis() - (SystemClock.uptimeMillis() - eventUptime)
    }

    /**
     * BFS 遍历无障碍节点树直接拼成文本。
     *
     * 省电要点：
     * - 一次遍历完成拼接，省掉中间 List/joinToString 的对象分配与二次拷贝；
     * - 达到节点上限或文本上限立刻停止（大列表页不会完整遍历）；
     * - 不持有节点引用（交给系统回收），不做节点回收调用（API 33+ 已废弃）。
     */
    private fun collectText(root: AccessibilityNodeInfo, limit: Int): String {
        val sb = StringBuilder(1024)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < limit && sb.length < MAX_TEXT) {
            val node = queue.removeFirst()
            visited++
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append('\n') }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append('\n') }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return if (sb.length > MAX_TEXT) sb.substring(0, MAX_TEXT) else sb.toString()
    }

    override fun onInterrupt() {
        // 无需处理
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Notifier.ensureChannel(this)
        val listener = ServiceHealth.isNotificationListenerEnabled(this)
        RecognitionLog.add(
            "无障碍服务已连接" + if (listener) "；通知读取也已开启（双通道）" else "（建议再开「通知使用权」，不弹通知也能记）"
        )
        // 常驻状态通知：让用户随时确认自动记账是否在运行
        refreshStatusNotification()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        mainHandler.post {
            overlay?.dismiss()
            overlay = null
        }
        // 无障碍断开 = 扫描必须停止：连带把依赖它的 root 轮询一并停掉
        com.jianji.app.util.BackgroundWork.sync(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        mainHandler.post {
            overlay?.dismiss()
            overlay = null
        }
        com.jianji.app.util.BackgroundWork.sync(this)
        super.onDestroy()
    }

    companion object {
        /** 页面文本上限：聊天页最新的气泡在**底部**，截太短会把新内容切掉 */
        private const val MAX_TEXT = 6000
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** 息屏状态缓存时长 */
        private const val INTERACTIVE_TTL_MS = 2000L

        /** 刚记完一笔后的扫描冷却（此时没有新信息可扫） */
        private const val HIT_COOLDOWN_MS = 3000L

        /** 账单页常见字段（至少命中 2 个才认为页面确实是账单/支付结果页） */
        private val RECEIPT_LABELS = listOf(
            "支付时间", "付款时间", "交易时间", "创建时间", "交易单号", "商户单号", "商户全称",
            "收款方", "付款方式", "支付方式", "订单号", "付款金额", "支付金额", "交易金额", "当前状态"
        )

        /** 聊天/消息同步窗口（2 分钟）：窗内重扫不重复，超过就认为可能是新的一笔 */
        private const val SYNC_WINDOW_MS = 2 * 60 * 1000L

        /** 单组最多一次补多少条（防止误识别刷屏） */
        private const val MAX_PER_GROUP = 5
    }
}
