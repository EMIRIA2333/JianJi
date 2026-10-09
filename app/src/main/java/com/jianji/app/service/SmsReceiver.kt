package com.jianji.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.jianji.app.App
import com.jianji.app.core.Categories
import com.jianji.app.core.Record
import com.jianji.app.core.RecordSource
import com.jianji.app.core.RecordType
import com.jianji.app.core.RuntimePolicy
import com.jianji.app.core.SmsParser
import com.jianji.app.util.CategoryLearner
import com.jianji.app.util.Notifier
import com.jianji.app.util.Prefs
import com.jianji.app.util.RecordWriter

/**
 * 短信记账：实时监听系统短信广播（RECEIVE_SMS 运行时权限），解析银行支付/收款短信。
 * 只处理强证据（金额+收支方向+银行语境），弱短信一律忽略，防止误记。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        // 门控：总开关 + 短信开关都开着才处理（总开关关掉时短信不该被解析）
        if (!RuntimePolicy.smsEnabled(Prefs.isAutoEnabled(context), Prefs.isSmsEnabled(context))) return
        if (!Prefs.isSmsPermissionGranted(context)) return

        val app = context.applicationContext as App
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // 多段短信按 originatingAddress 拼接
        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        if (sender.isBlank() || body.isBlank()) return

        app.post {
            val parsed = SmsParser.parse(sender, body) ?: return@post
            val note = buildString {
                if (parsed.bank.isNotBlank()) append(parsed.bank)
                if (parsed.merchant.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(parsed.merchant)
                }
            }.ifBlank { "银行短信" }

            if (app.deduper.duplicateText("sms|$sender|$body")) return@post
            if (app.deduper.duplicate(parsed.type, parsed.amount, note)) return@post

            val rec = Record(
                amount = parsed.amount,
                type = parsed.type,
                category = CategoryLearner.suggest(context, parsed.merchant, parsed.type == RecordType.INCOME, body, "sms"),
                note = note,
                source = RecordSource.AUTO_SMS,
                time = System.currentTimeMillis()
            )
            val id = RecordWriter.insert(context, rec)
            if (id > 0) {
                Notifier.notifyRecord(context, rec, note)
            }
        }
    }
}
