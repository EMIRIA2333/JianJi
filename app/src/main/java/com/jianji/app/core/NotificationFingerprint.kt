package com.jianji.app.core

/**
 * 通知指纹（纯 Kotlin，可单测）。
 *
 * 解决的问题：**同一条通知被反复投递 → 反复记账**。
 * 常见来源：
 * - 微信/支付宝的**分组通知**会被不断更新，每次都触发一次 hook；
 * - root 轮询 `dumpsys notification` 时，通知栏里没清理的消息每轮都会被再读一次；
 * - 无障碍 / 通知读取 / Hook / root 四条通路各投递一次。
 *
 * 判定依据（关键）：
 * - `pkg + 标题 + 正文`：正文里的分组条数「[12条]」会变化，必须先归一化掉；
 * - **通知时间戳的时间桶**（30 秒为一个桶）：通知"更新"时它不变，而**新的一笔支付会变**。
 *
 * 为什么不再用时间戳的精确值（3.24.0 修复）：
 *   四条通路拿到的时间戳来源不同 —— Hook 用 `notification.when`、
 *   通知读取用 `sbn.postTime`、无障碍用 `event.eventTime`（或 `when`）、
 *   root 用 `dumpsys` 里的 `when`（解析失败时为 0）。
 *   同一个事件在两条通路上能被算出**不同的精确时间戳**，于是同一笔付一次记两笔
 *   （真机日志实证：12:01:18.858 Hook 入库 id=1879，12:01:19.064 通知读取又入库 id=1880）。
 *   改成时间桶后，跨通路只要落在同一个 30 秒内就算同一条通知；
 *   而真实的两笔支付在时间上几乎不可能落在同一个桶里、正文还完全一致。
 */
object NotificationFingerprint {

    private val GROUP_COUNT = Regex("\\[\\s*\\d+\\s*条\\s*]")
    private val SPACES = Regex("\\s+")

    /** 时间桶宽度：30 秒（四条通路的投递延迟都在秒级以内） */
    const val TIME_BUCKET_MS = 30_000L

    /** 正文指纹：pkg + 标题 + 正文（**不含时间**），用于「换了通路但其实是同一条」 */
    fun contentOf(pkg: String, title: String, text: String): String {
        val t = SPACES.replace(GROUP_COUNT.replace(text, ""), "")
        val ti = SPACES.replace(GROUP_COUNT.replace(title, ""), "")
        val body = if (t.length > 220) t.substring(0, 220) else t
        return "$pkg|$ti|$body"
    }

    /** 时间桶：0 保持 0（未知时间不参与区分） */
    fun bucketOf(notifyTime: Long): Long =
        if (notifyTime <= 0L) 0L else notifyTime / TIME_BUCKET_MS

    /** 生成用于去重的规范化指纹 */
    fun of(pkg: String, title: String, text: String, notifyTime: Long): String =
        "${contentOf(pkg, title, text)}|${bucketOf(notifyTime)}"

    /**
     * 判定「是不是同一条通知」时要一起比对的键：当前桶 ±1。
     *
     * 为什么要容差：不同通路拿到的时间戳能差几十秒（`when` vs `postTime` vs 事件时间），
     * 只比同一个桶仍会漏掉跨桶的那一对。相邻桶一起看，等于给了 60 秒的容差；
     * 而同一通路对同一条通知反复投递时时间戳是稳定的，永远落在同一个桶里，不会重复记。
     */
    fun keys(pkg: String, title: String, text: String, notifyTime: Long): List<String> {
        val content = contentOf(pkg, title, text)
        val bucket = bucketOf(notifyTime)
        if (bucket <= 0L) return listOf("$content|0")
        return listOf("$content|${bucket - 1}", "$content|$bucket", "$content|${bucket + 1}")
    }

    /** 压缩成短键（长度 + 哈希），存进偏好设置不占空间 */
    fun key(fingerprint: String): String =
        "${fingerprint.length}|${Integer.toHexString(fingerprint.hashCode())}"
}
