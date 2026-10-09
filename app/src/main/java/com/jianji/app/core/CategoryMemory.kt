package com.jianji.app.core

/**
 * 分类记忆的匹配逻辑（纯 Kotlin，可单测）。
 *
 * 目标：**你手动改过一次分类，以后相似的账单就自动用那个分类**，从而减少手动编辑。
 *
 * 匹配分三档（越像分越高）：
 * 1. **完全相同**（归一化后）→ 100 分；
 * 2. **互相包含**（如记忆里有「滴滴出行」，新账单是「滴滴出行-快车」）→ 80 分；
 * 3. **二元组重合度**（中文里对错别字/前后缀很有效）→ 最高 70 分。
 *
 * 低于 [MIN_SCORE] 视为不相关，避免"看起来像但其实不是"的误套用。
 */
object CategoryMemory {

    /** 采纳阈值：低于它宁可用默认猜测，也不乱套用 */
    const val MIN_SCORE = 62

    /** 记忆键前缀：商户 / 备注(含标签) / 商户+金额档 */
    const val PREFIX_MERCHANT = "m:"
    const val PREFIX_NOTE = "n:"
    const val PREFIX_AMOUNT = "a:"

    /** 金额档位（把金额归到「相近区间」再学习，避免几十块的差异就学不到） */
    fun amountBucket(amount: Double): String = when {
        amount <= 0.0 -> ""
        amount < 20 -> "lt20"
        amount < 50 -> "20_50"
        amount < 100 -> "50_100"
        amount < 300 -> "100_300"
        amount < 1000 -> "300_1000"
        else -> "gt1000"
    }

    /** 商户键 */
    fun merchantKey(merchant: String): String = PREFIX_MERCHANT + normalizeKey(merchant)

    /** 备注/标签键 */
    fun noteKey(note: String): String = PREFIX_NOTE + normalizeKey(note)

    /** 商户 + 金额档键（"这家店这个价位"通常就是同一类消费） */
    fun amountKey(merchant: String, amount: Double): String {
        val m = normalizeKey(merchant)
        val b = amountBucket(amount)
        return if (m.isEmpty() || b.isEmpty()) "" else "$PREFIX_AMOUNT$m|$b"
    }

    data class Entry(
        val key: String,
        val category: String,
        val hits: Int,
        val updatedAt: Long
    )

    private val PUNCT = Regex("[\\s\\p{Punct}，。、；：？！（）【】《》“”‘’·—…～]+")

    /** 归一化：去空白与标点，英文转小写（中文原样保留） */
    fun normalizeKey(merchant: String): String =
        PUNCT.replace(merchant.trim(), "").lowercase()

    /** 相似度评分（0~100） */
    fun score(queryRaw: String, keyRaw: String): Int {
        val q = normalizeKey(queryRaw)
        val k = normalizeKey(keyRaw)
        if (q.isEmpty() || k.isEmpty()) return 0
        if (q == k) return 100
        val short = if (q.length <= k.length) q else k
        val long = if (q.length <= k.length) k else q
        if (short.length >= 2 && long.contains(short)) return 80
        // 二元组重合度
        val a = bigrams(q)
        val b = bigrams(k)
        if (a.isEmpty() || b.isEmpty()) return 0
        val inter = a.intersect(b).size
        val ratio = (2.0 * inter) / (a.size + b.size)
        return if (ratio >= 0.6) (ratio * 70).toInt() else 0
    }

    private fun bigrams(s: String): Set<String> {
        if (s.length < 2) return emptySet()
        val out = HashSet<String>(s.length)
        for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        return out
    }

    /**
     * 从记忆里挑最合适的一条：
     * 先比相似度，再比命中次数，最后比最近使用时间。
     *
     * 打分规则（越像越可信）：
     * - 商户匹配：100（完全相同）/ 80（包含）/ 二元组相似；
     * - 备注（或标签）匹配：按相似度 × 0.75 计 —— 说明"这类消费"你之前分过类；
     * - 商户 + 金额档同时命中：在商户分基础上 **+10**（同一家店同一价位，最可靠）。
     */
    fun pickBest(queryRaw: String, entries: List<Entry>): Entry? =
        pickBest(queryRaw, "", 0.0, entries)

    fun pickBest(
        merchant: String,
        note: String,
        amount: Double,
        entries: List<Entry>
    ): Entry? {
        if (normalizeKey(merchant).isEmpty() && normalizeKey(note).isEmpty()) return null
        var best: Entry? = null
        var bestScore = 0
        entries.forEach { e ->
            val s = scoreFor(merchant, note, amount, e.key)
            if (s < MIN_SCORE) return@forEach
            val better = best == null ||
                s > bestScore ||
                (s == bestScore && e.hits > best!!.hits) ||
                (s == bestScore && e.hits == best!!.hits && e.updatedAt > best!!.updatedAt)
            if (better) {
                best = e
                bestScore = s
            }
        }
        return best
    }

    /** 单个记忆键对本次查询的得分 */
    fun scoreFor(merchant: String, note: String, amount: Double, key: String): Int {
        return when {
            key.startsWith(PREFIX_MERCHANT) -> score(merchant, key.removePrefix(PREFIX_MERCHANT))
            key.startsWith(PREFIX_NOTE) -> {
                val raw = score(note, key.removePrefix(PREFIX_NOTE))
                // 备注的权重低一档（除非商户也命中，见下面的金额档）
                (raw * 0.75).toInt()
            }
            key.startsWith(PREFIX_AMOUNT) -> {
                val body = key.removePrefix(PREFIX_AMOUNT)
                val bar = body.indexOf('|')
                if (bar <= 0) 0
                else {
                    val m = body.substring(0, bar)
                    val b = body.substring(bar + 1)
                    val ms = score(merchant, m)
                    if (ms >= MIN_SCORE && amountBucket(amount) == b) minOf(100, ms + 10) else 0
                }
            }
            else -> score(merchant, key)
        }
    }
}
