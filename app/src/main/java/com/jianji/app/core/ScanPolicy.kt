package com.jianji.app.core

/**
 * 页面扫描策略（纯 Kotlin，可单测）。
 *
 * 无障碍服务最耗电的动作是「遍历无障碍节点树」，而 `TYPE_WINDOW_CONTENT_CHANGED`
 * 在滚动、打字、动画时都会高频触发。这里把「什么时候值得扫、多久扫一次、扫多少节点」
 * 集中成可测试的策略，避免为无关页面白白唤醒 CPU：
 *
 *  1. **事件文本预筛**：事件自带变更文本时，先看它与钱是否有关，无关直接跳过整棵树遍历；
 *  2. **自适应退避**：连续多次没扫出结果时拉长间隔（最多 2 倍），一旦命中立刻恢复；
 *  3. **省电模式**：把基准间隔从 1.8s 放宽到 4s；
 *  4. **节点上限**：内容变化事件只扫前 320 个节点（窗口切换事件扫 420 个）。
 *
 * 耗电优先的取舍（v3.2.0）：
 * - 通知类账单（免密支付、微信/支付宝支付通知）现在由 **Hook / 通知读取** 秒级拿到，
 *   不依赖页面扫描，所以页面扫描可以放宽 —— 它只用来兜「转账 / 红包结果页」这类
 *   不发通知的页面；
 * - 因此基准间隔与节点上限都明显收紧，长时间刷微信/支付宝时的 CPU 占用大幅下降。
 */
object ScanPolicy {

    const val WINDOW_INTERVAL_MS = 900L
    const val CONTENT_INTERVAL_MS = 1800L
    const val CONTENT_INTERVAL_SAVE_MS = 4000L
    const val BILL_INTERVAL_MS = 5000L

    const val CONTENT_NODE_LIMIT = 320
    const val WINDOW_NODE_LIMIT = 420

    /** 系统「省电模式」下的额外倍数（全局省电时进一步降频；窗口切换不降） */
    const val BATTERY_SAVER_FACTOR = 2

    /** 与钱有关的线索词：出现任一才值得遍历节点树 */
    private val INTERESTING = listOf(
        "支付", "付款", "收款", "转账", "红包", "退款", "退货", "金额", "价", "费",
        "¥", "￥", "元", "扣款", "扣费", "消费", "账单", "明细", "钱包", "零钱", "余额",
        "到账", "成功", "订单", "交易", "免密", "自动", "确认", "银行", "卡",
        // 转账 / 红包气泡的状态词（「已被接收」「已被领取」等本身不含"支付/收款"字样，
        // 少这几个词会导致这些事件被预筛掉、整页都不再解析）
        "接收", "领取", "领完", "存入", "退还", "已收", "待收"
    )

    /** 独立出现的金额（如账单页大字「17.00」）也算线索 */
    private val BARE_AMOUNT = Regex("^[¥￥+＋\\-－]?\\s*[0-9][0-9,]*(\\.\\d{1,2})?\\s*元?$")

    /**
     * 事件文本是否值得进一步扫描。
     * - 文本为空 / null：无从判断，**允许**扫描（例如 WebView 更新时不带文本）；
     * - 有文本但与钱无关：跳过，省下一次整树遍历；
     * - 纯金额（账单页大字）也算有关。
     */
    fun looksInteresting(text: CharSequence?): Boolean {
        val s = text?.toString()?.trim().orEmpty()
        if (s.isEmpty()) return true
        if (BARE_AMOUNT.matches(s)) return true
        return INTERESTING.any { s.contains(it) }
    }

    /**
     * 距离上次同类扫描至少需要间隔多久。
     *
     * @param missStreak 连续未识别到账单的次数（0 = 上次有收获）
     * @param batterySaver 系统是否处于省电模式（全局省电时进一步降频）
     */
    fun intervalFor(
        windowScan: Boolean,
        powerSave: Boolean,
        missStreak: Int,
        batterySaver: Boolean = false
    ): Long {
        if (windowScan) return WINDOW_INTERVAL_MS
        val base = if (powerSave) CONTENT_INTERVAL_SAVE_MS else CONTENT_INTERVAL_MS
        val systemFactor = if (batterySaver) BATTERY_SAVER_FACTOR else 1
        return base * factorFor(missStreak) * systemFactor
    }

    /** 退避倍数：连续 8 次无收获开始 ×2，20 次以上 ×2（识别优先，最多只降一半频率） */
    fun factorFor(missStreak: Int): Int = when {
        missStreak < 8 -> 1
        else -> 2
    }

    fun nodeLimit(windowScan: Boolean): Int =
        if (windowScan) WINDOW_NODE_LIMIT else CONTENT_NODE_LIMIT
}
