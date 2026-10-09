package com.jianji.app.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jianji.app.core.PaymentParser
import com.jianji.app.util.Prefs
import com.jianji.app.util.RecognitionLog
import com.jianji.app.util.RootManager
import com.jianji.app.util.ServiceHealth

/**
 * 通知读取服务（需要用户授予「通知使用权」）。
 *
 * 为什么需要它：无障碍的通知事件在部分机型 / 场景下会漏（例如通知只在通知栏里、
 * 没有弹横幅时）。**通知读取权限读的是通知本身，弹不弹都能拿到**，
 * 因此这条通路专门用来兜住「免密支付不弹通知」这类漏记。
 *
 * 与无障碍通路共用 [NotifyPipe]，内容相同只会记一次。
 */
class NotifyListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        // 门控：总开关关掉 → 不处理任何通知
        if (!Prefs.isAutoEnabled(this)) return
        // 顺手做一次「无障碍保活自检」（限流 5 分钟一次；ADB / root 已授权时会自动恢复）
        ServiceHealth.ensureAccessibilityAliveThrottled(this)
        RootManager.autoRestoreWithRoot(this)
        val pkg = sbn.packageName ?: return
        if (!isWatched(pkg)) return
        // 跳过自己发的通知，避免自触发
        if (pkg == packageName) return

        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        ).joinToString("\n")
        if (title.isBlank() && text.isBlank()) return

        NotifyPipe.handle(this, pkg, title, text, "通知读取", sbn.postTime)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        RecognitionLog.add("通知读取权限已连接（不弹通知也能记账）")
    }

    private fun isWatched(pkg: String): Boolean =
        pkg == PaymentParser.PKG_WECHAT || pkg == PaymentParser.PKG_ALIPAY ||
            PaymentParser.APP_NAMES.containsKey(pkg)
}
