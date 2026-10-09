package com.jianji.app.core

/**
 * `dumpsys notification --noredact` 输出解析（纯 Kotlin，可单测）。
 *
 * 解析后的每条通知（包名 + 标题 + 正文）会交给与无障碍/通知读取**同一个记账管道**，
 * 因此同一条通知不会重复记账。这样即使无障碍和通知读取都没开，
 * root 也能把通知栏里的消费信息读出来记账。
 */
object NotificationDumpParser {

    data class Item(val pkg: String, val title: String, val text: String, val notifyTime: Long = 0L)

    private val PKG = Regex("pkg=([A-Za-z0-9_.]+)")
    private val COMPONENT_PKG = Regex("^\\s*[A-Za-z0-9_.]+\\{?[A-Za-z0-9_.]+\\s+([A-Za-z0-9_.]+)/")
    /** 通知自身的时间戳：更新通知时不变，新通知会变（用于"同一条通知只记一次"） */
    private val WHEN = Regex("(?:android\\.)?when=(\\d{10,})")

    fun parse(dump: String): List<Item> {
        val out = ArrayList<Item>()
        var pkg: String? = null
        var title = ""
        var text = ""
        var bigText = ""
        var notifyTime = 0L

        fun flush() {
            val p = pkg
            val body = if (bigText.isNotBlank()) bigText else text
            if (p != null && (title.isNotBlank() || body.isNotBlank())) {
                out.add(Item(p, title, body, notifyTime))
            }
            title = ""
            text = ""
            bigText = ""
            notifyTime = 0L
        }

        dump.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("NotificationRecord(") -> {
                    flush()
                    pkg = PKG.find(line)?.groupValues?.get(1)
                        ?: COMPONENT_PKG.find(line)?.groupValues?.get(1)
                    WHEN.find(line)?.let { notifyTime = it.groupValues[1].toLongOrNull() ?: 0L }
                }
                line.startsWith("android.title=") -> title = unquote(line.substringAfter('='))
                line.startsWith("android.bigText=") -> bigText = unquote(line.substringAfter('='))
                line.startsWith("android.text=") -> text = unquote(line.substringAfter('='))
                notifyTime == 0L -> WHEN.find(line)?.let { notifyTime = it.groupValues[1].toLongOrNull() ?: 0L }
            }
        }
        flush()
        return out
    }

    /** 形如 `String (交易提醒)` / `SpannableString (…文本…)`，取出括号里的内容 */
    private fun unquote(s: String): String {
        val i = s.indexOf('(')
        val j = s.lastIndexOf(')')
        return if (i >= 0 && j > i) s.substring(i + 1, j).trim() else s.trim()
    }
}
