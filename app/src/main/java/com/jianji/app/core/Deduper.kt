package com.jianji.app.core

import java.util.LinkedHashMap

/**
 * 进程内去重器：防止同一笔支付被记两次（通知 + 页面双事件、账单页补齐重复等）。
 *
 * 两级判定：
 *  1. **严格**：同方向 + 同金额 + 同商户；
 *  2. **宽松**：同方向 + 同金额，且其中一方是「微信 / 支付宝」这类平台通用名，或两个商户名相近
 *     —— 用于拦住「通知记一次 + 页面又记一次」但商户名不同的情况（例如一个是「微信」、
 *     另一个是页面上的活动文案）。
 *
 * 纯内存实现（容量上限 256 条，自动按时间窗清理），无磁盘/网络开销。
 */
class Deduper(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private val records = LinkedHashMap<String, Long>()
    private val texts = LinkedHashMap<String, Long>()
    private val pending = LinkedHashMap<String, Long>()

    /** 同类型+同金额+同商户（或宽松匹配）在时间窗内视为重复 */
    fun duplicate(type: Int, amount: Double, merchant: String): Boolean {
        val strictKey = keyOf(type, amount, merchant)
        val now = clock()
        prune(records, now)
        if (records.containsKey(strictKey)) return true
        if (looseDuplicate(type, amount, merchant)) return true
        records[strictKey] = now
        records[looseKeyOf(type, amount, merchant)] = now
        trim(records)
        return false
    }

    /**
     * 标记「已弹出待处理提醒」；同一笔正在等待用户处理时返回 false。
     *
     * 注意：这个标记只活 [PENDING_TTL_MS]（30 秒）。曾经用 3 分钟，
     * 结果「同一商户连续两笔相同金额」或「用户反复测同样的小额」会被静默吞掉。
     */
    fun markPending(type: Int, amount: Double, merchant: String): Boolean {
        val key = keyOf(type, amount, merchant)
        val now = clock()
        prune(pending, now, PENDING_TTL_MS)
        if (pending.containsKey(key)) return false
        pending[key] = now
        trim(pending)
        return true
    }

    /** 用户已处理（保存 / 忽略）后清除待处理标记 */
    fun clearPending(type: Int, amount: Double, merchant: String) {
        pending.remove(keyOf(type, amount, merchant))
    }

    /**
     * 完全相同的文本在时间窗内视为重复。
     * @param windowMs 可指定更长的窗口（例如聊天页判断「这一页内容刚处理过」）
     */
    fun duplicateText(text: String, windowMs: Long = 0L): Boolean {
        val t = text.trim()
        val key = "${t.length}|${Integer.toHexString(t.hashCode())}"
        val now = clock()
        prune(texts, now, windowMs)
        if (texts.containsKey(key)) return true
        texts[key] = now
        trim(texts)
        return false
    }

    fun clear() {
        records.clear()
        texts.clear()
        pending.clear()
    }

    /** 移除一条去重记录（用户在「相似账单」确认页选择仍然录入时调用） */
    fun remove(type: Int, amount: Double, merchant: String) {
        records.remove(keyOf(type, amount, merchant))
        records.remove(looseKeyOf(type, amount, merchant))
    }

    // ---------------- 内部 ----------------

    private fun keyOf(type: Int, amount: Double, merchant: String): String =
        "$type|${Math.round(amount * 100.0)}|${merchant.trim().lowercase()}"

    private fun looseKeyOf(type: Int, amount: Double, merchant: String): String =
        "$LOOSE_PREFIX$type:${amtKey(amount)}:${merchant.trim().lowercase()}"

    private fun amtKey(amount: Double): Long = Math.round(amount * 100.0)

    /**
     * 宽松判定：同方向 + 同金额，且商户相近或其中一个是平台通用名。
     * 两笔真实的同金额消费（不同具体商户）不会被误判。
     */
    private fun looseDuplicate(type: Int, amount: Double, merchant: String): Boolean {
        val prefix = "$LOOSE_PREFIX$type:${amtKey(amount)}:"
        val m = merchant.trim().lowercase()
        return records.keys.any { key ->
            if (!key.startsWith(prefix)) return@any false
            val other = key.substring(prefix.length)
            isGenericMerchant(other) || isGenericMerchant(m) || similarMerchant(other, m)
        }
    }

    private fun isGenericMerchant(m: String): Boolean =
        m.isBlank() || m.length <= 2 || GENERIC_MERCHANTS.contains(m)

    private fun similarMerchant(a: String, b: String): Boolean =
        a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))

    private fun prune(map: LinkedHashMap<String, Long>, now: Long, ttlMs: Long = 0L) {
        val limit = if (ttlMs > 0) ttlMs else windowMs
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > limit) it.remove() else break
        }
    }

    private fun trim(map: LinkedHashMap<String, Long>) {
        val it = map.entries.iterator()
        while (map.size > MAX_ENTRIES) {
            it.next()
            it.remove()
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 3 * 60 * 1000L
        const val MAX_ENTRIES = 256

        /** 「等待用户处理」标记的存活期：只用于防止同一笔反复弹窗，不能太长 */
        const val PENDING_TTL_MS = 30 * 1000L

        private const val LOOSE_PREFIX = "L:"

        /** 平台通用商户名：这些名字不能作为「是否同一笔」的区分依据 */
        private val GENERIC_MERCHANTS = setOf(
            "微信", "微信支付", "支付宝", "支付宝支付", "支付", "云闪付", "财付通", "银联",
            "银行", "零钱", "余额", "微信支付凭证", "交易提醒"
        )
    }
}
