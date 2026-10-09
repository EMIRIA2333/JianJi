package com.jianji.app.util

import android.content.Context
import com.jianji.app.core.NotificationFingerprint

/**
 * 通知指纹登记表：保证**同一条通知只记一次**。
 *
 * 为什么需要：通知栏里没清理的消息会被反复送达 —— 分组通知每次更新都会触发 hook，
 * root 轮询每 30 秒会把通知栏里的旧消息再读一遍。没有这个登记表就会出现
 * 「支付一次、记了几十笔」。
 *
 * 特性：
 * - 持久化（进程被杀后依然有效）；
 * - 保留最近 [MAX] 条、超过 [TTL_MS] 自动清理，不占空间；
 * - 指纹里带通知自身的时间戳，**同样金额的真实第二笔不会被误杀**。
 */
object SeenNotifications {

    private const val MAX = 300
    private const val TTL_MS = 24 * 60 * 60 * 1000L

    /** @return true = 这条通知还没处理过（并已登记）；false = 重复，应跳过 */
    @Synchronized
    fun isNewAndRemember(ctx: Context, pkg: String, title: String, text: String, notifyTime: Long): Boolean {
        val key = NotificationFingerprint.key(NotificationFingerprint.of(pkg, title, text, notifyTime))
        val now = System.currentTimeMillis()
        val entries = load(ctx).toMutableList()
        // 清理过期
        entries.removeAll { now - it.second > TTL_MS }
        if (entries.any { it.first == key }) {
            save(ctx, entries)
            return false
        }
        entries.add(key to now)
        while (entries.size > MAX) entries.removeAt(0)
        save(ctx, entries)
        return true
    }

    private fun load(ctx: Context): List<Pair<String, Long>> =
        Prefs.seenNotifications(ctx).lineSequence()
            .mapNotNull { line ->
                val i = line.lastIndexOf(':')
                if (i <= 0) null
                else line.substring(i + 1).toLongOrNull()?.let { line.substring(0, i) to it }
            }
            .toList()

    private fun save(ctx: Context, entries: List<Pair<String, Long>>) {
        Prefs.setSeenNotifications(ctx, entries.joinToString("\n") { "${it.first}:${it.second}" })
    }
}
