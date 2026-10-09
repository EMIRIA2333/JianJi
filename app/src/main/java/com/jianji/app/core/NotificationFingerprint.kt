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
 * - **`when`（通知自身的时间戳）**：通知"更新"时它不变，而**新的一笔支付会变**，
 *   因此它既能挡住重复投递，又不会吞掉"同样金额的真实第二笔"。
 */
object NotificationFingerprint {

    private val GROUP_COUNT = Regex("\\[\\s*\\d+\\s*条\\s*]")
    private val SPACES = Regex("\\s+")

    /** 生成用于去重的规范化指纹 */
    fun of(pkg: String, title: String, text: String, notifyTime: Long): String {
        val t = SPACES.replace(GROUP_COUNT.replace(text, ""), "")
        val ti = SPACES.replace(GROUP_COUNT.replace(title, ""), "")
        val body = if (t.length > 220) t.substring(0, 220) else t
        return "$pkg|$ti|$body|$notifyTime"
    }

    /** 压缩成短键（长度 + 哈希），存进偏好设置不占空间 */
    fun key(fingerprint: String): String =
        "${fingerprint.length}|${Integer.toHexString(fingerprint.hashCode())}"
}
