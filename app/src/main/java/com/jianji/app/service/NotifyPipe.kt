package com.jianji.app.service

import android.content.Context
import com.jianji.app.App
import com.jianji.app.core.Categories
import com.jianji.app.core.PaymentParser
import com.jianji.app.core.Record
import com.jianji.app.core.RecordPolicy
import com.jianji.app.core.RecordType
import com.jianji.app.core.ScanPolicy
import com.jianji.app.util.AppLog
import com.jianji.app.util.CategoryLearner
import com.jianji.app.util.Money
import com.jianji.app.util.Notifier
import com.jianji.app.util.PendingRecord
import com.jianji.app.util.Prefs
import com.jianji.app.util.RecognitionLog
import com.jianji.app.util.RecordWriter
import com.jianji.app.util.SeenNotifications

/**
 * 通知 → 记账的公共管道。
 *
 * **两条通路都走这里**，内容相同只会处理一次（文本去重）：
 * 1. 无障碍服务的通知事件（[AutoCaptureService]，需要无障碍开启）；
 * 2. 通知读取权限（[NotifyListenerService]，需要"通知使用权"）。
 *
 * 之所以要有第 2 条：无障碍的通知事件在部分机型/场景下会漏（例如通知只在
 * 通知栏里、没弹横幅时），而**通知读取权限读的是通知本身**，弹不弹都能拿到。
 */
object NotifyPipe {

    /**
     * @param via        来源标记，仅用于「识别记录」排查（"无障碍" / "通知读取" / "Hook" / "root"）
     * @param notifyTime 通知自身的时间戳（Notification.when / postTime）；
     *                   通知"更新"时不变、新的一笔支付会变 —— 是判断"同一条通知"的关键
     */
    fun handle(ctx: Context, pkg: String, title: String, text: String, via: String, notifyTime: Long = 0L) {
        val app = ctx.applicationContext as App
        app.post {
            val parsed = PaymentParser.parse(pkg, title, text, strict = false)
            if (parsed == null) {
                if (ScanPolicy.looksInteresting(text) && text.length > 4) {
                    RecognitionLog.add("${PaymentParser.platformName(pkg)} 通知($via)未识别：${text.take(40)}")
                }
                return@post
            }
            val record = Record(
                amount = parsed.amount,
                type = parsed.type,
                category = CategoryLearner.suggest(ctx, parsed.merchant, parsed.type == RecordType.INCOME, text, pkg),
                note = "",
                merchant = parsed.merchant,
                payMethod = parsed.payMethod,
                source = PaymentParser.sourceOf(pkg),
                time = System.currentTimeMillis()
            )
            // **同一条通知只记一次**：通知栏里没清理的消息会被反复投递
            // （分组通知更新、root 每 30 秒轮询、多条通路重复送达），
            // 没有这道闸就会出现"付一次记几十笔"。指纹带通知时间戳，
            // 因此同样金额的真实第二笔不会被误杀。
            if (!SeenNotifications.isNewAndRemember(ctx, pkg, title, text, notifyTime)) {
                RecognitionLog.add("${PaymentParser.platformName(pkg)} 同一条通知已记过($via)，跳过")
                AppLog.i("识别", "重复通知跳过：$pkg via=$via")
                return@post
            }
            RecognitionLog.add(
                "${PaymentParser.platformName(pkg)} 通知($via) → " +
                    "${if (record.type == RecordType.INCOME) "收入" else "支出"} ${Money.plain(record.amount)} · ${record.displayName}"
            )

            // 与近期记录雷同只是**提示信息**，绝不作为拦截条件
            val similar = app.deduper.duplicate(record.type, record.amount, record.displayName)

            // 统一决策：默认直接记账（带撤销）；免密支付即使开了确认也直接记
            val decision = RecordPolicy.decide(
                confirmBeforeSave = Prefs.isConfirmBeforeSave(ctx),
                autoSaveMianmi = Prefs.isAutoSaveMianmi(ctx),
                isMianmi = PaymentParser.isMianmi(text)
            )
            if (decision == RecordPolicy.SAVE_DIRECT) {
                saveDirect(app, ctx, record, similar)
                return@post
            }

            val pending = PendingRecord(
                amount = record.amount,
                type = record.type,
                merchant = record.displayName,
                category = record.category,
                time = record.time,
                source = record.source,
                similar = similar,
                payMethod = record.payMethod
            )
            if (!app.deduper.markPending(record.type, record.amount, record.displayName)) return@post
            if (similar) {
                Notifier.notifyDuplicate(ctx, pending)
            } else {
                Notifier.notifyPendingSave(ctx, pending)
            }
        }
    }

    /** 直接入库 + 可撤销通知（默认路径，保证通知类账单一定落库） */
    private fun saveDirect(app: App, ctx: Context, record: Record, similar: Boolean = false) {
        val id = runCatching { RecordWriter.insert(ctx, record) }.getOrDefault(-1L)
        AppLog.i("记账", "入库 id=$id ${if (record.type == 1) "收入" else "支出"} ${Money.plain(record.amount)} 分类=${record.category} 商户=${record.displayName} 相似=$similar")
        if (id <= 0) {
            RecognitionLog.add("入库失败：${record.displayName}")
            Notifier.notifyPendingSave(
                ctx,
                PendingRecord(
                    amount = record.amount, type = record.type, merchant = record.displayName,
                    category = record.category, time = record.time, source = record.source,
                    payMethod = record.payMethod
                )
            )
            return
        }
        app.deduper.clearPending(record.type, record.amount, record.displayName)
        val sign = if (record.type == RecordType.INCOME) "+" else "-"
        val tail = if (similar) "（与近期账单雷同，记错了点撤销）" else "，点「撤销」可反悔"
        Notifier.notifyImported(
            ctx,
            "简记 · 已记账",
            "记好了 $sign${Money.plain(record.amount)} · ${record.category}$tail",
            longArrayOf(id)
        )
    }
}
