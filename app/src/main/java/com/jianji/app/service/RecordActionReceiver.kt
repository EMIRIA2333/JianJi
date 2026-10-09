package com.jianji.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.jianji.app.App
import com.jianji.app.util.Notifier
import com.jianji.app.util.PendingRecord
import com.jianji.app.util.RecordWriter

/**
 * 通知按钮回调：无需打开应用即可确认账单。
 *  - ACTION_SAVE        保存（正常记账）
 *  - ACTION_FORCE_SAVE  继续记录（相似账单场景，用户确认不是重复）
 *  - ACTION_IGNORE      忽略
 *  - ACTION_UNDO_IMPORT 撤销上一次「账单页补充」（移入回收站，可再还原）
 */
class RecordActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as App

        if (intent.action == ACTION_UNDO_IMPORT) {
            val ids = intent.getLongArrayExtra(EXTRA_UNDO_IDS)?.toList().orEmpty()
            if (ids.isEmpty()) return
            // 按这条通知自己的 id 取消（现在每次记账用独立 id，才能弹出横幅）
            val notifyId = intent.getIntExtra(EXTRA_NOTIFY_ID, -1)
            app.post {
                val n = app.dao.softDeleteMany(ids)
                if (notifyId > 0) Notifier.cancel(context, notifyId) else Notifier.cancelBillImport(context)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "已撤销，$n 条已移入回收站", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        val pending = PendingRecord.from(intent) ?: return

        when (intent.action) {
            ACTION_IGNORE -> {
                app.post { app.deduper.clearPending(pending.type, pending.amount, pending.merchant) }
                Notifier.cancel(context, pending.notifyId)
            }

            ACTION_SAVE, ACTION_FORCE_SAVE -> {
                val record = pending.toRecord()
                app.post {
                    val id = RecordWriter.insert(context, record)
                    app.deduper.clearPending(pending.type, pending.amount, pending.merchant)
                    Notifier.cancel(context, pending.notifyId)
                    if (id > 0) Notifier.notifyRecord(context, record, record.note)
                }
            }
        }
    }

    companion object {
        const val ACTION_SAVE = "com.jianji.app.action.SAVE_RECORD"
        const val ACTION_FORCE_SAVE = "com.jianji.app.action.FORCE_SAVE_RECORD"
        const val ACTION_IGNORE = "com.jianji.app.action.IGNORE_RECORD"
        const val ACTION_UNDO_IMPORT = "com.jianji.app.action.UNDO_BILL_IMPORT"
        const val EXTRA_UNDO_IDS = "undo_ids"
        const val EXTRA_NOTIFY_ID = "notify_id"
    }
}
