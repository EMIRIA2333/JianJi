package com.jianji.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.regex.Pattern

/**
 * 账单列表页解析器（纯 Kotlin，可单测）。
 *
 * 解决「支付软件不弹通知时无法记账」的问题：用户打开微信/支付宝的**账单列表页**时，
 * 逐条解析可见账单（商户 + 金额 + 日期分组），用于补齐那些没有通知的支付
 * （小程序内支付、免密扣款、被折叠的通知等）。
 *
 * 设计要点：
 *  - 只在**账单列表页**生效（含「账单 / 交易记录」等标记，且**不是**账单详情页）；
 *  - 至少 2 条金额行才认定是列表，避免把普通页面误判；
 *  - 商户取金额行上方最近的非噪声行；类型按正负号判断，无符号时结合状态词；
 *  - 支持「今天 / 昨天 / 10月1日 / 2026年10月1日 / 2026-10-01」日期分组头。
 */
object BillListParser {

    /**
     * 只有支付类应用才有「账单列表」语义。
     * **购物/外卖等应用的商品列表、订单列表不是账单**，绝不能拿来记账
     * （真机事故：淘宝商品页的价格被当成账单批量导入）。
     */
    val BILL_SOURCE_PKGS = setOf(
        PaymentParser.PKG_WECHAT,
        PaymentParser.PKG_ALIPAY,
        "com.unionpay"
    )

    /** 订单 / 商品页特征：出现任一即认为不是账单列表 */
    private val ORDER_PAGE_MARKERS = listOf(
        "加入购物车", "立即购买", "立即抢购", "确认收货", "待发货", "待收货", "待评价", "我的订单",
        "订单详情", "申请售后", "评价晒单", "规格", "库存", "件宝贝", "店铺", "联系客服", "收藏",
        "关注店铺", "购物车", "凑单", "领券购买", "预售", "定金", "尾款", "宝贝详情", "商品详情",
        "推荐搭配", "猜你喜欢", "已售", "月销", "包邮", "发货", "物流信息"
    )

    /** 聊天页特征：会话里收付混排，不能当账单列表（微信支付会话里也常出现「账单」字样） */
    private val CHAT_MARKERS = listOf(
        "对方正在输入", "按住 说话", "按住说话", "语音输入", "发送消息", "视频通话", "聊天信息",
        "置顶聊天", "消息免打扰", "清空聊天记录", "拍一拍", "添加到备注", "引用回复"
    )

    data class Entry(
        val type: Int,
        val amount: Double,
        val merchant: String,
        val time: Long,
        val payMethod: String
    )

    private val BILL_MARKERS = listOf(
        "账单", "交易记录", "收支明细", "交易明细", "收支记录",
        // 微信「零钱明细」也是账单页（发红包、转账都在这里）
        "零钱明细", "账单明细", "资金明细", "收支账单"
    )
    private val DETAIL_LABELS = listOf(
        "当前状态", "交易单号", "商户单号", "商户全称", "收款方全称", "商品说明", "收单机构", "支付时间"
    )
    private val STATUS_WORDS = listOf(
        "已支付", "支付成功", "交易成功", "已完成", "已收款", "收款成功", "已转账", "转账成功",
        "待付款", "交易关闭", "已关闭", "退款成功", "已退款", "还款成功", "充值成功", "自动扣款",
        "免密支付", "支付失败", "已取消", "进行中"
    )
    private val INCOME_WORDS = listOf("收款", "收入", "退款", "已到账", "转入", "红包", "返还", "退回")

    /**
     * 退款/收入专用关键词：只有这些词出现在**本行**时才把负号金额判为收入。
     * 注意不能用「收款」——很多商户名本身带「收款」（如「二楼小吃9001A收款」）。
     */
    private val REFUND_WORDS = listOf(
        "退款", "退货", "返还", "退回", "原路退回", "已退款", "退款成功", "退还", "赔付", "补偿"
    )

    /** 明确的收入状态词（说明这笔钱是进来的） */
    private val INCOME_STATUS_WORDS = listOf(
        "收款成功", "已收款", "收款到账", "到账", "转入成功", "收入", "红包到账"
    )

    private val AMOUNT_LINE = Pattern.compile(
        "^\\s*([+＋\\-－])?\\s*[¥￥]?\\s*([0-9][0-9,]*\\.[0-9]{1,2})\\s*元?\\s*$"
    )
    // 日期分组头：今天 / 昨天 / 10月1日 / 10月1日 21:00（零钱明细） / 2026年10月1日 / 2026-10-01
    private val DATE_MD = Pattern.compile("^\\s*([0-9]{1,2})月([0-9]{1,2})日(?:\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)?\\s*$")
    private val DATE_YMD = Pattern.compile("^\\s*([0-9]{4})年([0-9]{1,2})月([0-9]{1,2})日(?:\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)?\\s*$")
    private val DATE_DASH = Pattern.compile("^\\s*([0-9]{4})[-/]([0-9]{1,2})[-/]([0-9]{1,2})(?:\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)?\\s*$")

    /** 行内日期（零钱明细把日期时间写在商户同一行） */
    private val INLINE_YMD = Pattern.compile("([0-9]{4})年([0-9]{1,2})月([0-9]{1,2})日")
    private val INLINE_MD = Pattern.compile("([0-9]{1,2})月([0-9]{1,2})日")
    private val INLINE_DASH = Pattern.compile("([0-9]{4})[-/]([0-9]{1,2})[-/]([0-9]{1,2})")

    /** 页面里是否出现任何日期信息（真实账单一定带日期；商品列表通常没有） */
    private val DATE_ANYWHERE = Pattern.compile(
        "今天|昨天|[0-9]{1,2}月[0-9]{1,2}日|[0-9]{4}年[0-9]{1,2}月|[0-9]{4}[-/][0-9]{1,2}[-/][0-9]{1,2}"
    )
    private val TIME_ONLY = Pattern.compile("^\\s*[0-9]{1,2}:[0-9]{2}(:[0-9]{2})?\\s*$")

    private val MERCHANT_NOISE = listOf(
        "账单", "全部账单", "交易记录", "明细", "今天", "昨天", "全部", "筛选", "搜索", "统计",
        "本月", "上月", "更多", "收起", "展开", "收入", "支出", "合计", "总计", "查看", "设置",
        "时间", "方式", "分类", "标签", "备注", "状态", "元", "笔"
    )

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /**
     * @return 解析出的账单条目（已按「类型+金额+商户」去重），列表页条件不满足时返回空
     */
    fun parse(
        pkg: String,
        screenText: String,
        now: Long = System.currentTimeMillis(),
        maxEntries: Int = 30
    ): List<Entry> {
        // 只认支付类应用的账单列表
        if (!BILL_SOURCE_PKGS.contains(pkg)) return emptyList()

        val lines = screenText.replace('\u00A0', ' ')
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 4) return emptyList()

        val joined = lines.joinToString(" ")
        if (BILL_MARKERS.none { joined.contains(it) }) return emptyList()
        // 账单详情页交给 ReceiptParser，避免只记一条却丢了精确时间/分类
        if (DETAIL_LABELS.count { joined.contains(it) } >= 2) return emptyList()
        // 订单/商品页（淘宝、京东、小程序商城等）不是账单
        if (ORDER_PAGE_MARKERS.any { joined.contains(it) }) return emptyList()
        // 聊天页不是账单（会话里收付混排，方向与来源都不可靠）
        if (CHAT_MARKERS.any { joined.contains(it) }) return emptyList()

        val amountIndexes = lines.indices.filter { AMOUNT_LINE.matcher(lines[it]).matches() }
        if (amountIndexes.size < 2) return emptyList()

        val todayStart = dayStartOf(now)
        // 真实账单一定带日期信息（分组头或行内日期）；商品列表通常没有
        if (!DATE_ANYWHERE.matcher(joined).find()) return emptyList()
        if (lines.none { parseDayHeader(it, todayStart) != null } &&
            lines.none { parseInlineDay(it, todayStart) != null }
        ) return emptyList()

        var dayCursor: Long? = null
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        var prevAmountIdx = -1

        for (idx in lines.indices) {
            val line = lines[idx]
            val dayHeader = parseDayHeader(line, todayStart)
            if (dayHeader != null) {
                dayCursor = dayHeader
                continue
            }
            val m = AMOUNT_LINE.matcher(line)
            if (!m.matches()) continue
            val sign = m.group(1).orEmpty()
            val value = m.group(2).replace(",", "").toDoubleOrNull() ?: continue
            if (value <= 0.0 || value > PaymentParser.MAX_AMOUNT) continue

            // 本行范围：上一条金额之后 ~ 本条金额（若首行是上一笔的尾部状态词则跳过）
            val rawStart = prevAmountIdx + 1
            prevAmountIdx = idx
            val start = if (rawStart < idx && STATUS_WORDS.any { lines[rawStart].trim().let { l -> l == it || l.startsWith(it) } }) {
                rawStart + 1
            } else {
                rawStart
            }
            val rowContext = (start..idx).joinToString(" ") { lines[it] }

            if (rowContext.contains("支付失败") || rowContext.contains("交易关闭") ||
                rowContext.contains("已取消") || rowContext.contains("进行中")
            ) continue

            // 支付证据：带正负号的金额 / 带 ¥ 的金额 / 交易状态词 / 支付方式
            // 商品价格（纯数字、无状态、无支付方式）不算账单
            val hasSign = sign == "+" || sign == "＋" || sign == "-" || sign == "－"
            val hasCurrencyPrefix = line.trim().startsWith("¥") || line.trim().startsWith("￥")
            val hasStatus = STATUS_WORDS.any { rowContext.contains(it) }
            val payMethod = payMethodNear(lines, idx)
            if (!hasSign && !hasCurrencyPrefix && !hasStatus && payMethod.isEmpty()) continue

            // 方向判定：明确收入状态 > 退款词（即便金额带负号，如「退款 -3.31」）> 金额符号 > 默认支出
            val incomeStatus = INCOME_STATUS_WORDS.any { rowContext.contains(it) }
            val refundWord = REFUND_WORDS.any { rowContext.contains(it) }
            val type = when {
                incomeStatus -> RecordType.INCOME
                refundWord -> RecordType.INCOME
                sign == "-" || sign == "－" -> RecordType.EXPENSE
                sign == "+" || sign == "＋" -> RecordType.INCOME
                else -> RecordType.EXPENSE
            }

            val merchant = findMerchant(lines, idx) ?: PaymentParser.platformName(pkg)
            // 行内日期优先（零钱明细把「10月1日 21:00」写在商户行里）
            val day = parseInlineDay(rowContext, todayStart) ?: dayCursor
            val time = when {
                day == null -> now
                day == todayStart -> now
                else -> day + NOON_OFFSET
            }
            val key = "$type|${Math.round(value * 100)}|${merchant.lowercase()}"
            if (!seen.add(key)) continue
            out.add(Entry(type, Math.round(value * 100.0) / 100.0, merchant, time, payMethod))
            if (out.size >= maxEntries) break
        }
        return out
    }

    /** 金额行上方最近的非噪声行即为商户 */
    fun findMerchant(lines: List<String>, amountIdx: Int): String? {
        var steps = 0
        var i = amountIdx - 1
        while (i >= 0 && steps < 4) {
            var cand = lines[i].trim().trimEnd('>', '›').trim()
            i--
            if (cand.isEmpty()) continue
            steps++
            // 去掉行内的日期与时间（零钱明细把「10月1日 21:00」写在商户后面）
            cand = cand.replace(TRAILING_DATE, "").trim()
            if (cand.isEmpty()) continue
            if (AMOUNT_LINE.matcher(cand).matches()) continue
            if (TIME_ONLY.matcher(cand).matches()) continue
            if (parseDayHeader(cand, dayStartOf(System.currentTimeMillis())) != null) continue
            if (STATUS_WORDS.any { cand == it || cand.startsWith(it) }) continue
            if (MERCHANT_NOISE.any { cand.contains(it) }) continue
            // 支付方式（零钱/花呗…）不是商户
            if (ReceiptParser.KNOWN_PAY_METHODS.any { cand == it || cand.startsWith(it) }) continue
            if (cand.matches(Regex("[0-9.,¥￥+\\-\\s]+"))) continue
            if (cand.length in 2..30) return cand
        }
        return null
    }

    /** 行尾的日期 / 时间（含「10月1日 21:00」「2026-09-30」「今天 12:30」） */
    private val TRAILING_DATE = Regex(
        "\\s*(?:[0-9]{4}年)?[0-9]{1,2}月[0-9]{1,2}日(?:\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)?" +
            "|\\s*[0-9]{4}[-/][0-9]{1,2}[-/][0-9]{1,2}(?:\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)?" +
            "|\\s*(?:今天|昨天)(?:\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)?" +
            "|\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?\\s*$"
    )

    /** 金额行附近识别支付方式：优先紧邻的已知支付工具，其次标签行/关键词 */
    fun payMethodNear(lines: List<String>, amountIdx: Int): String {
        for (i in amountIdx - 1 downTo maxOf(0, amountIdx - 2)) {
            val cand = lines[i].trim()
            ReceiptParser.KNOWN_PAY_METHODS.firstOrNull { cand == it || cand.startsWith(it) }
                ?.let { return it }
        }
        val context = (maxOf(0, amountIdx - 3)..minOf(lines.size - 1, amountIdx + 3))
            .joinToString(" ") { lines[it] }
        return ReceiptParser.extractPayMethod(context)
    }

    /** 解析行内日期（商户行里带「10月1日 21:00」「2026-09-30」这类文本） */
    fun parseInlineDay(text: String, todayStart: Long): Long? {
        INLINE_YMD.matcher(text).takeIf { it.find() }?.let {
            return safeDate(it.group(1)!!.toInt(), it.group(2)!!.toInt(), it.group(3)!!.toInt())
        }
        INLINE_DASH.matcher(text).takeIf { it.find() }?.let {
            return safeDate(it.group(1)!!.toInt(), it.group(2)!!.toInt(), it.group(3)!!.toInt())
        }
        if (text.contains("今天")) return todayStart
        if (text.contains("昨天")) return todayStart - DAY_MS
        INLINE_MD.matcher(text).takeIf { it.find() }?.let {
            val today = Instant.ofEpochMilli(todayStart).atZone(zone).toLocalDate()
            val month = it.group(1)!!.toInt()
            val day = it.group(2)!!.toInt()
            var d = safeDate(today.year, month, day) ?: return null
            if (d > todayStart) d = safeDate(today.year - 1, month, day) ?: d
            return d
        }
        return null
    }

    /** 解析日期分组头：今天 / 昨天 / 10月1日 / 2026年10月1日 / 2026-10-01 */
    fun parseDayHeader(line: String, todayStart: Long): Long? {
        val s = line.trim()
        if (s.isEmpty()) return null
        if (s == "今天" || s.startsWith("今天")) return todayStart
        if (s == "昨天" || s.startsWith("昨天")) return todayStart - DAY_MS
        val today = Instant.ofEpochMilli(todayStart).atZone(zone).toLocalDate()

        DATE_YMD.matcher(s).takeIf { it.matches() }?.let {
            return safeDate(it.group(1).toInt(), it.group(2).toInt(), it.group(3).toInt())
        }
        DATE_DASH.matcher(s).takeIf { it.matches() }?.let {
            return safeDate(it.group(1).toInt(), it.group(2).toInt(), it.group(3).toInt())
        }
        DATE_MD.matcher(s).takeIf { it.matches() }?.let {
            val month = it.group(1).toInt()
            val day = it.group(2).toInt()
            // 只写月日：默认今年，若晚于今天则视为去年
            var d = safeDate(today.year, month, day) ?: return null
            if (d > todayStart) d = safeDate(today.year - 1, month, day) ?: d
            return d
        }
        return null
    }

    private fun safeDate(year: Int, month: Int, day: Int): Long? = runCatching {
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()
    }.getOrNull()

    private fun dayStartOf(time: Long): Long =
        Instant.ofEpochMilli(time).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val NOON_OFFSET = 12L * 60 * 60 * 1000
}
