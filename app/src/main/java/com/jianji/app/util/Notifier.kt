package com.jianji.app.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jianji.app.R
import com.jianji.app.core.Record
import com.jianji.app.core.RecordType
import com.jianji.app.service.RecordActionReceiver
import com.jianji.app.ui.ConfirmActivity
import com.jianji.app.ui.EditRecordActivity
import com.jianji.app.ui.MainActivity
import java.util.Locale

object Notifier {

    private const val CHANNEL_ID = "record"
    /** 需要用户操作的提醒用高优先级渠道（悬浮横幅 + 操作按钮） */
    private const val CHANNEL_CONFIRM = "record_confirm"

    /**
     * **记账完成弹窗**渠道：高优先级 + 响铃 + 震动 → 系统会弹横幅（heads-up）。
     *
     * 单独一个渠道的原因：用户可以在系统设置里单独调它（静音/关横幅），
     * 而不会连带把「保存 / 修改」确认提醒也调掉。
     */
    private const val CHANNEL_DONE = "record_done"
    /** 常驻运行状态（低优先级、无声、不打扰） */
    private const val CHANNEL_STATUS = "status"

    private val main = Handler(Looper.getMainLooper())
    private var seq = 1000

    /** 「账单页补充」汇总通知的固定 id */
    private const val BILL_IMPORT_ID = 9901

    /** 「运行状态」常驻通知的固定 id */
    private const val STATUS_ID = 9902

    /** 「无障碍已关闭」提醒的固定 id */
    private const val SERVICE_OFF_ID = 9903

    /** root 自动读通知的前台服务通知 id */
    const val ROOT_SCAN_ID = 9904

    /** Hook 直读守护的前台服务通知 id */
    const val HOOK_GUARD_ID = 9905

    /** 渠道只需创建一次，避免每次通知都查询/创建渠道 */
    @Volatile
    private var channelsReady = false

    fun ensureChannel(c: Context) {
        if (channelsReady) return
        if (Build.VERSION.SDK_INT < 26) {
            channelsReady = true
            return
        }
        val m = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (m.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "自动记账提醒", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "自动记账结果与预算提醒"
            m.createNotificationChannel(ch)
        }
        if (m.getNotificationChannel(CHANNEL_CONFIRM) == null) {
            val ch = NotificationChannel(CHANNEL_CONFIRM, "记账确认提醒", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "识别到支付后弹出的「保存 / 修改」确认提醒"
            ch.enableVibration(true)
            m.createNotificationChannel(ch)
        }
        if (m.getNotificationChannel(CHANNEL_DONE) == null) {
            val ch = NotificationChannel(CHANNEL_DONE, "记账完成提醒", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "自动记账成功后弹出横幅提醒，可一键撤销"
            ch.enableVibration(true)
            ch.enableLights(true)
            // 高优先级 + 响铃/震动才会触发系统横幅（heads-up）
            ch.setShowBadge(true)
            m.createNotificationChannel(ch)
        }
        if (m.getNotificationChannel(CHANNEL_STATUS) == null) {
            val ch = NotificationChannel(CHANNEL_STATUS, "运行状态", NotificationManager.IMPORTANCE_MIN)
            ch.description = "常驻显示自动记账是否在运行（无声、不打扰）"
            ch.setShowBadge(false)
            m.createNotificationChannel(ch)
        }
        channelsReady = true
    }

    /**
     * root 自动读通知的前台服务通知（低优先级、无声、常驻）。
     * 前台服务必须有通知，这条同时也让用户知道「它在读通知栏」。
     */
    fun rootScanNotification(c: Context, text: String): Notification {
        ensureChannel(c)
        return NotificationCompat.Builder(c, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("简记 · root 读取通知栏")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(
                PendingIntent.getActivity(
                    c, 3, Intent(c, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    /** 更新 root 读取状态文案（失败就算了，不影响记账） */
    @SuppressLint("MissingPermission") // 已用 canNotify() 显式检查通知权限
    fun updateRootScanNotification(c: Context, text: String) {
        if (!canNotify(c)) return
        runCatching {
            NotificationManagerCompat.from(c).notify(ROOT_SCAN_ID, rootScanNotification(c, text))
        }
    }

    /** 更新 Hook 守护通知文案 */
    @SuppressLint("MissingPermission")
    fun updateHookGuardNotification(c: Context, text: String) {
        if (!canNotify(c)) return
        runCatching {
            NotificationManagerCompat.from(c).notify(HOOK_GUARD_ID, hookGuardNotification(c, text))
        }
    }

    /**
     * Hook 直读守护的前台服务通知（最低优先级、无声）。
     * 它的作用是让简记进程常驻，保证 Hook 模块发来的广播随时能送达。
     */
    fun hookGuardNotification(c: Context, text: String): Notification {
        ensureChannel(c)
        return NotificationCompat.Builder(c, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("简记 · Hook 直读守护")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(
                PendingIntent.getActivity(
                    c, 4, Intent(c, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    /**
     * 运行状态常驻通知（对应「通知栏状态」开关）：
     * 让用户一眼看到自动记账是否还活着 —— 无障碍被系统关掉时这条通知会消失。
     */
    fun updateStatusNotification(c: Context, enabled: Boolean, todayCount: Int) {
        main.post {
            ensureChannel(c)
            if (!enabled) {
                cancel(c, STATUS_ID)
                return@post
            }
            if (!canNotify(c)) return@post
            val text = if (todayCount > 0) "运行中 · 今日已记 $todayCount 笔" else "运行中 · 等待识别支付"
            val n = NotificationCompat.Builder(c, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle("简记 · 自动记账")
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setContentIntent(
                    PendingIntent.getActivity(
                        c, 2, Intent(c, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
            post(c, STATUS_ID, n)
        }
    }

    /** 无障碍服务被系统关闭时的强提醒（点击直达无障碍设置） */
    fun notifyServiceOff(c: Context) {
        main.post {
            ensureChannel(c)
            if (!canNotify(c)) return@post
            val n = NotificationCompat.Builder(c, CHANNEL_CONFIRM)
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle("简记 · 自动记账已停止")
                .setContentText("无障碍服务被系统关闭了，点这里重新开启即可恢复自动记账")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("无障碍服务被系统关闭了（更新应用或系统清理后台后常见）。点这里一键去开启，开启后自动记账立即恢复。")
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(
                    PendingIntent.getActivity(
                        c, 3,
                        Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)
                .build()
            post(c, SERVICE_OFF_ID, n)
        }
    }

    /** 主线程安全：内部已切换到主线程 */
    fun notifyRecord(c: Context, r: Record, merchant: String) {
        main.post {
            ensureChannel(c)
            val sign = if (r.type == RecordType.INCOME) "+" else "-"
            val detail = String.format(Locale.US, "%s¥%.2f", sign, r.amount) +
                if (merchant.isBlank()) "" else " · $merchant"
            val hide = Prefs.isNotifyHideAmount(c)
            val msg = if (hide) "已自动记账，点击查看" else detail
            Toast.makeText(c, if (hide) "已自动记账" else "已自动记账：$detail", Toast.LENGTH_SHORT).show()

            val pi = PendingIntent.getActivity(
                c, 1, Intent(c, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(c, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle("简记 · 自动记账")
                .setContentText(msg)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            post(c, seq++, n)
        }
    }

    /** 待确认账单：悬浮通知 +「保存 / 修改」按钮 */
    fun notifyPendingSave(c: Context, pending: PendingRecord): Int {
        val id = nextId()
        val p = pending.copy(notifyId = id)
        main.post {
            ensureChannel(c)
            val hide = Prefs.isNotifyHideAmount(c)
            val title = if (hide) "识别到一笔账单" else "识别到" + p.summary(c)
            val text = if (hide) p.summaryNoAmount(c) else "点击「保存」直接记账，或「修改」后再存"
            if (!canNotify(c)) {
                Toast.makeText(c, "$title，请到应用内确认", Toast.LENGTH_LONG).show()
                return@post
            }
            val editIntent = EditRecordActivity.pendingIntent(c, p)
            val n = NotificationCompat.Builder(c, CHANNEL_CONFIRM)
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(
                    PendingIntent.getActivity(
                        c, id, editIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .addAction(
                    R.drawable.ic_import, "保存",
                    actionIntent(c, RecordActionReceiver.ACTION_SAVE, p)
                )
                .addAction(
                    R.drawable.ic_gear, "修改",
                    PendingIntent.getActivity(
                        c, id + 5000, editIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)
                .build()
            post(c, id, n)
        }
        return id
    }

    /** 相似账单：金额 / 分类 / 时间 +「忽略 / 继续记录」 */
    fun notifyDuplicate(c: Context, pending: PendingRecord): Int {
        val id = nextId()
        val p = pending.copy(similar = true, notifyId = id)
        main.post {
            ensureChannel(c)
            val hide = Prefs.isNotifyHideAmount(c)
            val detail = if (hide) p.summaryNoAmount(c) else p.summary(c)
            val text = "$detail · 已存在相似账单"
            if (!canNotify(c)) {
                val record = p.toRecord()
                notifyConfirm(c, record.type, record.amount, record.note, record.time, record.source, true)
                return@post
            }
            val n = NotificationCompat.Builder(c, CHANNEL_CONFIRM)
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle("已存在相似账单")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(
                    PendingIntent.getActivity(
                        c, id, EditRecordActivity.pendingIntent(c, p),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .addAction(
                    R.drawable.ic_close, "忽略",
                    actionIntent(c, RecordActionReceiver.ACTION_IGNORE, p)
                )
                .addAction(
                    R.drawable.ic_import, "继续记录",
                    actionIntent(c, RecordActionReceiver.ACTION_FORCE_SAVE, p)
                )
                .setAutoCancel(true)
                .build()
            post(c, id, n)
        }
        return id
    }

    /** 预算预警：接近或超出预算额度时提醒 */
    fun notifyBudget(c: Context, status: Budget.Status) {
        main.post {
            ensureChannel(c)
            val hide = Prefs.isNotifyHideAmount(c)
            val title = if (status.isOver) "简记 · 已超预算" else "简记 · 预算提醒"
            val text = if (hide) "预算已触及上限，点击查看" else status.message()
            Toast.makeText(c, text, Toast.LENGTH_LONG).show()
            val pi = PendingIntent.getActivity(
                c, 7, Intent(c, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            post(c, seq++, n)
        }
    }

    /**
     * 批量入库汇总提醒（账单页补齐 / 聊天转账红包），带「撤销」按钮。
     *
     * 关键点：
     * - 用 [CHANNEL_DONE]（高优先级 + 响铃震动）→ **系统会弹横幅**；
     * - **每次用新的通知 id**：如果一直复用固定 id，系统只会"更新"上一条，
     *   不会再弹横幅（这正是"记账后没有弹窗"的原因）；
     * - 撤销时按这条通知自己的 id 取消。
     */
    fun notifyImported(c: Context, title: String, text: String, ids: LongArray = LongArray(0)) {
        main.post {
            ensureChannel(c)
            val notifyId = ++seq
            val pi = PendingIntent.getActivity(
                c, 9, Intent(c, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(c, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
            if (ids.isNotEmpty()) {
                val undo = Intent(c, RecordActionReceiver::class.java).apply {
                    action = RecordActionReceiver.ACTION_UNDO_IMPORT
                    putExtra(RecordActionReceiver.EXTRA_UNDO_IDS, ids)
                    putExtra(RecordActionReceiver.EXTRA_NOTIFY_ID, notifyId)
                }
                val undoPi = PendingIntent.getBroadcast(
                    c, 77 + notifyId, undo,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(R.drawable.ic_trash, "撤销", undoPi)
            }
            post(c, notifyId, builder.build())
        }
    }

    /** 账单页补齐结果：一次性汇总提醒，并带「撤销」按钮防止误识别 */
    fun notifyBillImport(c: Context, count: Int, days: Int, ids: LongArray = LongArray(0)) {
        notifyImported(
            c,
            "简记 · 账单页补充",
            "已从账单页补充 $count 笔账单（近 $days 天），可在回收站删除",
            ids
        )
    }

    /** 取消「账单页补充」通知（撤销时调用） */
    fun cancelBillImport(c: Context) = cancel(c, BILL_IMPORT_ID)

    /** 兼容保留：确认页提醒（通知权限受限时的兜底路径） */
    fun notifyConfirm(
        c: Context,
        type: Int,
        amount: Double,
        merchant: String,
        time: Long,
        source: Int,
        similar: Boolean
    ) {
        main.post {
            ensureChannel(c)
            if (!canNotify(c)) return@post
            val pi = PendingIntent.getActivity(
                c, seq, ConfirmActivity.intent(c, type, amount, merchant, time, source, similar),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(c, CHANNEL_CONFIRM)
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle(if (similar) "简记 · 疑似重复账单" else "简记 · 待确认账单")
                .setContentText("点击查看详情")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            post(c, seq++, n)
        }
    }

    /**
     * 统一的发送出口：先检查通知权限（未授权直接跳过），
     * 并用 runCatching 兜住个别 ROM 的限制。
     */
    @SuppressLint("MissingPermission") // 已通过 canNotify() 显式检查 POST_NOTIFICATIONS
    private fun post(c: Context, id: Int, n: Notification) {
        if (!canNotify(c)) return
        runCatching { NotificationManagerCompat.from(c).notify(id, n) }
    }

    private fun actionIntent(c: Context, action: String, p: PendingRecord): PendingIntent {
        val intent = p.putExtras(Intent(c, RecordActionReceiver::class.java)).setAction(action)
        return PendingIntent.getBroadcast(
            c, p.notifyId + action.hashCode() % 1000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextId(): Int = ++seq

    fun cancel(c: Context, id: Int) {
        main.post { runCatching { NotificationManagerCompat.from(c).cancel(id) } }
    }

    fun canNotify(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(c).areNotificationsEnabled()
    }
}
