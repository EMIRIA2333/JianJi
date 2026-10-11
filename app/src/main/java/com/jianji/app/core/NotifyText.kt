package com.jianji.app.core

import android.app.Notification
import android.os.Bundle
import android.widget.RemoteViews

/**
 * 通知文本抽取。
 *
 * **为什么单独做这件事**：支付宝「交易提醒」、部分银行的交易通知用的是
 * **自定义 RemoteViews**，正文既不在 `EXTRA_TEXT` 也不在 `EXTRA_BIG_TEXT` 里 ——
 * 旧实现只读 `EXTRA_TITLE / BIG_TEXT / TEXT / SUB_TEXT`，取不到就 `return null`，
 * 表现为「通知明明在通知栏里，简记却完全没反应」（支付宝免密支付不记账就是这个原因）。
 *
 * 现在的顺序：
 *  1. 常规 extras（title / bigText / text / subText / summaryText / infoText）；
 *  2. InboxStyle 的 `EXTRA_TEXT_LINES`、MessagingStyle 的 `EXTRA_MESSAGES`；
 *  3. `tickerText`；
 *  4. **RemoteViews 反射**：遍历 `contentView / bigContentView / headsUpContentView` 的
 *     actions，把 `setText` 类的 CharSequence 取出来（纯反射，不 apply、不 inflate，system_server 里也安全）。
 *
 * 全程 `runCatching` 包裹：任何一步失败都只是少一条线索，绝不让异常影响通知发布。
 */
object NotifyText {

    /**
     * 纯逻辑部分（可单测）：挑出标题与正文。
     *
     * @return null 表示标题和正文都为空（这种通知没有可用信息）
     */
    fun compose(title: String?, bodies: List<String?>): Pair<String, String>? {
        val t = title?.trim().orEmpty()
        val body = bodies
            .mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
        if (t.isEmpty() && body.isEmpty()) return null
        return t to body
    }

    /** 从通知里尽量多地把文本挖出来 */
    fun of(n: Notification?): Pair<String, String>? {
        if (n == null) return null

        val titleCandidates = listOf(
            extraString(n, Notification.EXTRA_TITLE),
            extraString(n, "android.title.big"),
            runCatching { n.tickerText?.toString() }.getOrNull()
        )

        val bodyCandidates = ArrayList<String?>()
        bodyCandidates += extraString(n, Notification.EXTRA_BIG_TEXT)
        bodyCandidates += extraString(n, Notification.EXTRA_TEXT)
        bodyCandidates += extraString(n, Notification.EXTRA_SUB_TEXT)
        bodyCandidates += extraString(n, Notification.EXTRA_SUMMARY_TEXT)
        bodyCandidates += extraString(n, Notification.EXTRA_INFO_TEXT)
        bodyCandidates += extraString(n, "android.progressText")
        bodyCandidates += textLines(n)
        bodyCandidates += messages(n)
        // RemoteViews 放在最后：它最"脏"（可能包含按钮文字等），但也最可能救回正在丢的那条
        bodyCandidates += remoteTexts(n)

        val title = titleCandidates.firstOrNull { !it.isNullOrBlank() }
        return compose(title, bodyCandidates)
    }

    /** 仅 RemoteViews 里的文本（诊断用：判断一条通知是不是"只存在于自定义布局里"） */
    fun remoteOnlyText(n: Notification?): String {
        if (n == null) return ""
        return remoteTexts(n).joinToString(" | ")
    }

    // ---------------- 内部 ----------------

    private fun extraString(n: Notification, key: String): String? =
        runCatching { n.extras?.getCharSequence(key)?.toString() }.getOrNull()

    /** InboxStyle：android.textLines */
    private fun textLines(n: Notification): List<String> = runCatching {
        val arr = n.extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) ?: return emptyList()
        arr.mapNotNull { it?.toString() }
    }.getOrDefault(emptyList())

    /** MessagingStyle：android.messages[*].text */
    private fun messages(n: Notification): List<String> = runCatching {
        val arr = n.extras?.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        arr.mapNotNull { b ->
            (b as? Bundle)?.getCharSequence("text")?.toString()
        }
    }.getOrDefault(emptyList())

    /**
     * 从 RemoteViews 的 actions 里取文本。
     *
     * 不调用 `apply()`（那会 inflate 布局，system_server 里风险太大），
     * 只反射读取 action 上的字段：老版本是 `ReflectionAction(methodName, value)`，
     * 新版本结构有变化 —— 所以这里按"字段名 + 类型"宽容地扫，拿不到就返回空。
     */
    private fun remoteTexts(n: Notification): List<String> {
        val out = ArrayList<String>()
        listOfNotNull(
            runCatching { n.contentView }.getOrNull(),
            runCatching { n.bigContentView }.getOrNull(),
            runCatching { n.headsUpContentView }.getOrNull()
        ).forEach { rv -> out += remoteTexts(rv) }
        return out
    }

    private fun remoteTexts(rv: RemoteViews?): List<String> {
        if (rv == null) return emptyList()
        val out = ArrayList<String>()
        runCatching {
            val f = RemoteViews::class.java.getDeclaredField("mActions")
            f.isAccessible = true
            val actions = f.get(rv) as? List<*> ?: return out
            for (a in actions) {
                if (a == null) continue
                val cls = a.javaClass
                val method = fieldString(cls, a, "methodName")
                val value = fieldCharSequence(cls, a, "value")
                if (value.isNullOrBlank()) continue
                // setText / setCharSequence 之类才要；setImageResource 等忽略
                if (method == null || method.contains("text", ignoreCase = true)) out += value.toString()
            }
        }
        return out
    }

    private fun fieldString(cls: Class<*>, obj: Any, name: String): String? = runCatching {
        val f = cls.getDeclaredField(name)
        f.isAccessible = true
        f.get(obj) as? String
    }.getOrNull()

    private fun fieldCharSequence(cls: Class<*>, obj: Any, name: String): String? = runCatching {
        val f = cls.getDeclaredField(name)
        f.isAccessible = true
        (f.get(obj) as? CharSequence)?.toString()
    }.getOrNull()
}
