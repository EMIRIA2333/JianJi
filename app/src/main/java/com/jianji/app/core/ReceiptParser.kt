package com.jianji.app.core

import java.util.regex.Pattern

/**
 * 账单详情页解析器（纯 Kotlin，可单测）。
 *
 * 针对微信「账单详情 / 微信支付凭证」与支付宝「账单详情」页面：
 *  - 金额常常是**纯数字独占一行**（如 `-17.00`），没有 ¥ 也没有「元」
 *  - 页面还带有「支付时间」「商品」「商户全称 / 收款方全称」「账单分类」等字段
 * 因此单独用页面规则解析，可拿到**准确的金额、方向、商户、平台分类与支付时刻**。
 */
object ReceiptParser {

    data class Parsed(
        val type: Int,
        val amount: Double,
        val merchant: String,
        val categoryHint: String,
        val time: Long?,
        val payMethod: String = "",
        val evidence: String,
        /** 页面上的商品说明 / 备注 / 留言（「自动提取备注」开启时记入标签） */
        val remark: String = "",
        /** 平台自带的账单分类（如支付宝「账单分类」），非空表示分类是平台权威值 */
        val platformCategory: String? = null
    )

    /** 页面必须出现的强证据（付款/收款已完成） */
    private val STRONG = listOf(
        "支付成功", "付款成功", "交易成功", "扣款成功", "自动扣款成功", "免密支付",
        "收款成功", "已收款", "收款到账", "转账成功", "充值成功", "还款成功", "退款成功",
        "当前状态 支付成功", "当前状态 交易成功",
        // 微信转账详情页：真机 OCR 只有「转账单号/转账时间/对方己收钱」，没有"支付成功"
        "转账单号", "转账时间", "对方己收钱", "对方已收钱", "已存入零钱",
        // 退款（微信账单详情页写的是「已全额退款」「已退款」，不是「退款成功」）
        "已全额退款", "已退款", "部分退款", "退款记录", "退款到账", "已原路退回", "原路退回",
        // 红包：只有明确的收发状态才算账单依据（聊天里没领取的红包不会命中）
        "微信红包", "红包已被领取", "红包已被领完", "发送了一个红包", "你领取了", "红包到账",
        // 收款成功页 / 红包收款页：真机页面文案是「你已收款，资金已存入零钱」
        // 和「已存入零钱，可直接提现 / 可直接转账」，页面里**没有**“微信红包”四个字
        "已存入零钱", "资金已存入零钱", "可直接提现", "可直接转账"
    )

    /** 退款相关词（用于分类与方向兜底） */
    private val REFUND_HINT = listOf(
        "已全额退款", "已退款", "退款记录", "退款成功", "退款到账", "原路退回", "已退回", "退货退款"
    )

    /**
     * 未完成支付的状态词：出现即不记账（付款页 / 待支付 / 失败 / 已取消 / 已退回）。
     *
     * 注意：**不含「待对方确认收款」** —— 微信/支付宝转账是「发出即扣款」，
     * 收款方还没确认只是钱还在平台托管，对付款人来说这笔支出已经发生。
     */
    private val PENDING_STATE = listOf(
        "立即支付", "确认支付", "去支付", "请输入支付密码", "请输入密码", "待支付", "待付款",
        "支付失败", "付款失败", "交易关闭", "已取消", "已关闭", "订单已取消", "待收货人确认",
        "已退还", "退还成功", "超过24小时未领取"
    )

    /** 聊天页特征：出现任一即认为这是会话页面而不是账单（聊天里收付混排，方向不可靠） */
    private val CHAT_MARKERS = listOf(
        "对方正在输入", "按住 说话", "按住说话", "语音输入", "发送消息", "视频通话", "聊天信息",
        "置顶聊天", "消息免打扰", "清空聊天记录", "拍一拍", "添加到备注", "引用回复"
    )

    /**
     * 转账**详情页**专用字段：只有出现这些字段时才要求用「收款方/付款方」判断方向。
     *
     * 注意：**不含「转账时间」** —— 收款成功页（「你已收款，资金已存入零钱」+转账时间/收款时间）
     * 也带「转账时间」，但它的方向由状态词就能确定，不需要收款方/付款方。
     */
    /** 我收到的转账：「转账-来自X」「已存入零钱」 */
    private val TRANSFER_IN = Pattern.compile("转账[-—·]?来自")

    /**
     * 我转出去的转账：「转账-转给X」。
     * OCR 常把「转给」认成 转检 / 转纶 / 转纥 / 转烚，
     * 所以这里**不限定第二个字**，只要求「转账-转X」且不是「转账」本身。
     */
    private val TRANSFER_OUT = Pattern.compile("转账[-—·]?转(?!账)")

    /** 只要页面上出现「转账-XX」这种标题，就当转账详情页处理（OCR 无序也能判方向） */
    private val TRANSFER_HINT = Pattern.compile("转账[-—·][^\\s]{1,4}")

    private val TRANSFER_DETAIL_FIELDS = listOf(
        "转账详情", "转账金额", "转账单号", "转账说明", "转账凭证"
    )

    /**
     * 转账详情页方向：微信/支付宝在「我付款」时列的是**收款方**，在「我收款」时列的是**付款方**。
     *
     * 但真实页面还有两种更强的写法（以前只认标签，导致这些页直接不记）：
     * - 「转账-**来自**李四」+「当前状态 **已存入零钱**」→ 我收到的（收入）；
     * - 「转账-**给**张三」/「已转账给」→ 我付出的（支出）。
     * 所以先看这两种明确写法，再退回收款方/付款方标签。
     */
    fun transferDirection(text: String): Int? {
        // ① 明确写法（最可靠）
        // OCR 常把「转给」认成 转检/转纶/转纥…，所以「转账-转X」后面不限定具体字，
        // 只排除「来自」；同时「对方已收(款|钱)」永远是支出（钱出去了）。
        if (TRANSFER_IN.matcher(text).find()) return RecordType.INCOME
        if (text.contains("对方已收款") || text.contains("对方已收钱") ||
            // OCR 常把「已」认成「己」（真实日志：「对方己收钱」）
            text.contains("对方己收款") || text.contains("对方己收钱") ||
            text.contains("已被接收") || text.contains("已转账给") ||
            TRANSFER_OUT.matcher(text).find()
        ) {
            return RecordType.EXPENSE
        }
        // ② 收款方/付款方标签
        val hasPayee = text.contains("收款方")
        val hasPayer = text.contains("付款方")
        return when {
            hasPayer && !hasPayee -> RecordType.INCOME
            hasPayee && !hasPayer -> RecordType.EXPENSE
            else -> null
        }
    }

    /**
     * 收入方向状态词。
     * 注意：
     * - 不含裸「退款」两字，避免把「可在支持的商户扫码退款」当成收入；
     * - 不含「已退款」——部分退款页面上「交易成功 + 退款记录 已退款」同时存在，
     *   这时主体是那笔**支出**，退款金额会在零钱明细里再单独记一笔收入；
     * - 「已全额退款」是整笔变成退款，方向确定为收入。
     */
    private val INCOME_STATUS = listOf(
        "收款成功", "已收款", "收款到账", "退款成功", "已全额退款", "退款到账", "原路退回", "已退回",
        "已到账", "转账成功", "收入", "转入成功", "红包到账", "收款金额",
        // 真机收款页文案
        "已存入零钱", "资金已存入零钱"
    )

    /** 支出方向状态词 */
    private val EXPENSE_STATUS = listOf(
        "支付成功", "付款成功", "交易成功", "扣款成功", "自动扣款成功", "免密支付", "已支付",
        "付款金额", "消费成功", "支出", "转账成功 支出"
    )

    /**
     * **月账单 / 收支汇总页**的特征词。
     *
     * 真实 bug：支付宝「11月」账单页顶部有「支出 ¥189.87 / 收入 ¥0.00 / 本月已省 …」，
     * 这些是**汇总数字**，不是一笔交易 —— 以前会被当成一笔「+189.87 收入」记下来。
     * 命中 ≥2 个特征就认定是汇总页，直接不解析（单笔详情页不会同时出现这些）。
     */
    private val SUMMARY_MARKERS = listOf(
        "本月已省", "收支分析", "搜索交易记录", "月账单", "本月支出", "本月收入",
        "账单统计", "月度账单", "全部账单", "较上月", "笔交易", "笔支出"
    )

    private val LABELED_AMOUNT = Pattern.compile(
        "(?:支付金额|付款金额|交易金额|订单金额|实付金额|实付款|退款金额|收款金额|金额)[:：]?\\s*[¥￥]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
    )
    private val CURRENCY_AMOUNT = Pattern.compile("[¥￥]\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val YUAN_AMOUNT = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元")

    /**
     * 空格/标点分隔的独立金额 token，如 OCR 出来的
     * 「… 转账-转检阮祝後 对方己收钱 -14.40 微信转账 …」里的 -14.40。
     * 要求带小数点（1~2 位）或带符号，避免把金额以外的整数（笔数、月份）当金额。
     */
    private val TOKEN_AMOUNT = Pattern.compile(
        "(?:^|[\\s|｜])([+＋\\-－])?\\s*[¥￥]?\\s*([0-9][0-9,]*\\.[0-9]{1,2})(?=[\\s|｜]|$)"
    )

    /** 独占一行的纯数字金额（可带 +/- 号），如 "-17.00"、"0.90"、"＋0.11" */
    private val LINE_AMOUNT = Pattern.compile(
        "^\\s*([+＋\\-－])?\\s*[¥￥]?\\s*([0-9][0-9,]*\\.[0-9]{1,2})\\s*$",
        Pattern.MULTILINE
    )

    private val MERCHANT_PATTERNS = listOf(
        // 转账成功页：「待🐱确认收款」里的名字就是收款人
        Pattern.compile("待(?:对方)?[「【『\\[（(]?(.{1,20}?)[」】』\\]）)]?确认收款"),
        // 红包收款页：「🐱 示例昵称的红包」里的名字就是发送者
        Pattern.compile("([^\\n]{1,14}?)的红包"),
        Pattern.compile("商户全称[:：]?\\s*([^\\n]{2,30})"),
        Pattern.compile("收款方全称[:：]?\\s*([^\\n]{2,30})"),
        Pattern.compile("收款方[:：]?\\s*([^\\n]{2,30})"),
        Pattern.compile("交易对方[:：]?\\s*([^\\n]{2,30})"),
        Pattern.compile("商户名称[:：]?\\s*([^\\n]{2,30})"),
        Pattern.compile("商品说明[:：]?\\s*([^\\n]{2,30})"),
        Pattern.compile("商品[:：]?\\s*([^\\n]{2,30})")
    )

    private val TIME_PATTERN = Pattern.compile(
        "(?:支付时间|付款时间|交易时间|创建时间|转账时间|收款时间)[:：]?\\s*" +
            "([0-9]{4}[-/年][0-9]{1,2}[-/月][0-9]{1,2}日?\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)"
    )

    /** 支付宝「账单分类」-> 简记分类 */
    private val PLATFORM_CATEGORY = mapOf(
        "教育培训" to "教育", "餐饮美食" to "餐饮", "交通出行" to "交通", "服饰装扮" to "服饰",
        "日用百货" to "日用", "数码电器" to "数码", "医疗健康" to "医疗", "住房物业" to "居住",
        "通讯物流" to "通讯", "文化休闲" to "娱乐", "生活服务" to "其他", "转账红包" to "人情",
        "投资理财" to "理财", "保险" to "保险", "母婴亲子" to "母婴", "宠物" to "宠物",
        "运动户外" to "运动", "美容美发" to "美容", "快递" to "快递", "汽车" to "汽车",
        "旅行住宿" to "旅行", "工资收入" to "工资", "退款" to "退款", "收款" to "收款", "红包" to "红包",
        "公用事业" to "居住", "充值缴费" to "通讯", "酒店旅游" to "旅行", "亲子" to "母婴",
        "鲜花宠物" to "宠物", "家居家装" to "日用", "运动健身" to "运动", "图书音像" to "教育"
    )

    private val PLATFORM_CATEGORY_PATTERN = Pattern.compile("(?:账单分类|分类)[:：]?\\s*([\\u4e00-\\u9fa5A-Za-z]{2,8})")

    /** 商户名排除词：这些行不是商户（含推广/活动文案与按钮） */
    private val NOT_MERCHANT = listOf(
        // 注意：这里只放**字段标签/结构词**；特判见 isLabelOnly()，
        // 因为真实商户名里也可能含"商户"（如「拼多多平台商户」）——不能用 contains 直接判死
        "账单详情", "全部账单", "交易成功", "支付成功", "当前状态", "时间", "方式",
        // 平台名与页面结构词不能当商户（真实 bug：「微信支付账单详情」的商户被记成"微信"，
        // 而正确的商户是金额上方那行「示例便利店」）
        "微信", "支付宝", "零钱明细", "零钱余额", "零钱", "账单服务", "转账说明", "转账单号",
        "转账时间", "收款时间", "转账-来自", "转账-给", "定位到聊天位置", "申请转账电子凭证", "查看往来交易",
        "单号", "分类", "标签", "更多", "推荐服务", "免密", "积分", "领取", "商品说明", "直接付款",
        "已记账", "记一笔", "服务", "状态", "金额", "查询", "投诉", "说明", "备注", "收起", "展开",
        // 支付宝支付成功页顶部的按钮/提示（曾被当成商户名或备注，真实 bug）
        "回首页", "完成", "去领取", "去看看", "获得花呗能量", "花呗能量", "奖励",
        // 支付宝「账单详情」的字段**标签**本身（值在别处）——不能当商户名（真实 bug：商户="全称"）
        "收款方全称", "商户全称", "收单机构", "商品说明", "账单分类", "推荐服务", "票单管理", "更多信息",
        "限时", "立减", "随机", "免息", "分期", "活动", "优惠", "红包", "抽奖", "开通", "领取",
        "下单", "专享", "特惠", "满减", "折扣", "返现", "兑换", "权益",
        // 转账成功页上的状态与按钮
        "确认收款", "待确认", "完成", "转账成功", "对方",
        // 收款成功页 / 红包收款页上的文案（不能当商户）
        "你已收款", "资金已存入零钱", "零钱余额", "封面由个人制作", "回复表情到聊天",
        "我也要制作", "红包", "转账时间", "收款时间"
    )

    /** 上一次被拒的原因（仅用于日志/识别记录，解析只在后台线程调用） */
    @Volatile
    var lastRejectReason: String = ""
        private set

    private fun reject(reason: String): Parsed? {
        lastRejectReason = reason
        return null
    }

    fun parse(pkg: String, screenText: String): Parsed? {
        lastRejectReason = ""
        val text = screenText.replace('\u00A0', ' ')
        if (text.length < 8) return reject("文本太短")
        // 聊天页不是账单：一页里可能同时存在「我发出去的」和「别人发给我的」，
        // 曾出现「转账给对方却记成收入」的问题 —— 直接不解析，
        // 转账/红包请从「账单详情页」或「零钱明细」识别
        CHAT_MARKERS.firstOrNull { text.contains(it) }?.let { return reject("聊天页特征「$it」") }
        // 尚未完成支付的页面（付款键盘 / 待支付 / 失败）不能记账
        PENDING_STATE.firstOrNull { text.contains(it) }?.let { return reject("未完成支付「$it」") }
        // **一页多笔**：微信支付服务号的「我的账单」页会连续展示多张支付卡片
        // （每张都有"使用XX支付 / ¥金额 / 账单详情"）。以前会把第一张当成单笔账单记一条，
        // 还会随着刷新反复记。这里按"页面里出现 ≥3 个 ¥金额"判定为多笔页面 → 不当作单笔。
        // 用循环计数而不是 Matcher.results()（后者要 Java 9 / 高 API，minSdk 26 上会崩）
        var amountHits = 0
        val amountMatcher = CURRENCY_AMOUNT.matcher(text)
        while (amountMatcher.find()) {
            amountHits++
            if (amountHits >= 3) break
        }
        // 更可靠的"多张卡片"判据：页面里出现 ≥2 次「使用…支付」或 ≥2 次「账单详情」，
        // 说明是服务号账单页/列表页（真·单笔详情页只会出现 1 次）。
        var multiCardCount = 0
        val cardMatcher = Regex("使用[^\\n]{0,24}支付|账单详情|查看交易详情").findAll(text)
        for (m in cardMatcher) {
            multiCardCount++
            if (multiCardCount >= 2) break
        }
        if (amountHits >= 3 || multiCardCount >= 2) {
            return reject("一页多笔（账单列表），交给列表补齐")
        }
        // 月账单/收支汇总页：顶部是"支出/收入/本月已省"这类**汇总数字**，不是一笔交易 → 不记
        SUMMARY_MARKERS.filter { text.contains(it) }.takeIf { it.size >= 2 }?.let { return reject("月账单汇总页（${it.joinToString("/")}）") }
        // 账单「列表页」（多行金额 + 日期分组）交给 BillListParser 处理，
        // 否则会把列表里的某一行当成单笔详情记一条错账
        if (isListPage(text)) return reject("这是账单列表页（交给列表补齐）")
        val evidence = STRONG.firstOrNull { text.contains(it) } ?: return reject("缺少支付成功等强证据")

        val negated = extractAmount(text) ?: return reject("没提取到金额")
        val amount = Math.abs(negated)

        val platformCategory = extractPlatformCategory(text)
        val incomeStatus = INCOME_STATUS.any { text.contains(it) }
        val expenseStatus = EXPENSE_STATUS.any { text.contains(it) }

        // 红包特判：只有明确「我发出的 / 我收到的」状态才记账
        val redPacket = if (text.contains("红包")) redPacketDirection(text) else null
        if (text.contains("红包") && redPacket == null) return reject("红包方向不明")

        // 转账详情页：用「收款方 / 付款方」标签判断方向（比状态词可靠），
        // 两个标签都出现或都不出现时无法判断，宁可不记
        val isTransferDetail = TRANSFER_DETAIL_FIELDS.any { text.contains(it) } ||
            TRANSFER_HINT.matcher(text).find()
        val transferType = if (isTransferDetail) transferDirection(text) else null
        if (isTransferDetail && transferType == null) return reject("转账方向不明（缺收款方/付款方）")

        // 方向判定优先级：
        // 转账详情页 > 红包特判 > 平台分类（如「账单分类 退款/收款」）> 状态词 > 金额符号
        val type = when {
            // 「支付成功，对方已收款」= 我付出去的钱（微信支付页面/服务号通知都有这句）；
            // 必须放在最前，否则会被 INCOME_STATUS 里的「已收款」误判成收入（真实 bug）。
            text.contains("对方已收款") || text.contains("对方已收钱") ||
                text.contains("对方己收款") || text.contains("对方己收钱") -> RecordType.EXPENSE
            transferType != null -> transferType
            redPacket != null -> redPacket
            platformCategory != null && Categories.isIncomeCategory(platformCategory) -> RecordType.INCOME
            incomeStatus && !expenseStatus -> RecordType.INCOME
            expenseStatus && !incomeStatus -> RecordType.EXPENSE
            incomeStatus && expenseStatus -> if (negated < 0) RecordType.EXPENSE else RecordType.INCOME
            negated < 0 -> RecordType.EXPENSE
            Categories.isIncomeCategory(platformCategory.orEmpty()) -> RecordType.INCOME
            else -> RecordType.EXPENSE
        }

        val merchant = extractMerchant(text) ?: when {
            text.contains("微信红包") -> "微信红包"
            text.contains("红包") -> "红包"
            else -> PaymentParser.platformName(pkg)
        }
        // 方向已定后再取分类：红包特判 > 退款 > 平台自带分类 > 关键词猜测
        val categoryHint = when {
            redPacket != null -> if (redPacket == RecordType.INCOME) "红包" else "人情"
            type == RecordType.INCOME && REFUND_HINT.any { text.contains(it) } -> "退款"
            platformCategory == null -> Categories.guess(merchant, type == RecordType.INCOME, text, pkg)
            type == RecordType.EXPENSE && Categories.isIncomeCategory(platformCategory) ->
                EXPENSE_CATEGORY_FOR_INCOME[platformCategory] ?: "其他"
            else -> platformCategory
        }
        // 一致性校正：分类若为「退款/收款/红包…」这类只可能是收入的分类，方向纠正为收入
        val finalType = Categories.normalizeType(type, categoryHint)
        val time = extractTime(text)
        val payMethod = extractPayMethod(text)
        val remark = extractRemark(text)

        return Parsed(
            finalType, amount, merchant, categoryHint, time, payMethod, evidence, remark,
            if (redPacket == null) platformCategory else null
        )
    }

    // ---------------- 备注提取（商品说明 / 备注 / 留言） ----------------

    private val REMARK_PATTERNS = listOf(
        Pattern.compile("(?:商品说明|订单备注|转账说明|付款备注|收款备注|备注|附言|留言)[:：]?\\s*([^\\n]{1,30})"),
        Pattern.compile("商品[:：]?\\s*([^\\n]{2,30})")
    )

    /** 这些不是备注内容（单号、按钮、说明文字） */
    private val REMARK_NOISE = listOf(
        "单号", "编号", "订单号", "商户单号", "交易单号", "无", "查看", "详情", "申请", "联系",
        "对订单", "电子凭证", "添加", "去评价", "再来一单", "删除订单"
    )

    /**
     * 提取页面上的备注 / 商品说明，用于「自动提取备注」开关。
     * 只拿到长串订单号时会被过滤掉；返回空串表示没有可用备注。
     */
    fun extractRemark(text: String): String {
        REMARK_PATTERNS.forEach { p ->
            val m = p.matcher(text)
            if (m.find()) {
                var g = m.group(1).orEmpty().trim().trimEnd('>', '›', '：', ':').trim()
                g = g.substringBefore("  ").trim()
                if (g.length < 2 || g.length > 20) return@forEach
                if (REMARK_NOISE.any { g.contains(it) }) return@forEach
                if (g.matches(Regex("[0-9A-Za-z]{12,}"))) return@forEach
                return g
            }
        }
        return ""
    }

    /**
     * 是否是「账单列表页」：3 行以上独立金额 + 至少一个日期分组头 + 至少 2 个交易状态。
     * 详情页通常只有 1~2 个独立金额（大字金额 + 退款记录金额），不会被误判。
     */
    private fun isListPage(text: String): Boolean {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val amountLines = lines.count { LINE_AMOUNT.matcher(it).matches() }
        if (amountLines < 3) return false
        val hasDateGroup = lines.any { DATE_GROUP.matches(it) }
        val statusCount = STATUS_FOR_LIST.count { text.contains(it) }
        return hasDateGroup && statusCount >= 2
    }

    private val DATE_GROUP = Regex(
        "^(今天|昨天|[0-9]{1,2}月[0-9]{1,2}日|[0-9]{4}年[0-9]{1,2}月[0-9]{1,2}日)$"
    )

    /** 列表页里每行都会出现的交易状态词 */
    private val STATUS_FOR_LIST = listOf(
        "交易成功", "已支付", "支付成功", "已收款", "退款成功", "已全额退款", "已退款", "交易关闭"
    )

    // ---------------- 红包方向 ----------------

    /** 收红包一方看到的文案（钱进来了）—— 最明确的信号，优先判定 */
    private val RED_PACKET_RECEIVED = listOf(
        "你领取了", "领取了", "抢到", "红包到账", "已存入零钱", "已收钱", "已收款", "已转入零钱"
    )

    /** 发红包一方看到的文案（钱已经出去了） */
    private val RED_PACKET_SENT = listOf(
        "你发送", "发送了一个红包", "发出红包", "已发送", "红包已被领取", "已被领取",
        "已被领完", "已领完", "等待对方领取", "你已发出", "已领取"
    )

    /**
     * 红包方向判定：返回 null 表示「状态不明确」——只有金额没有收发状态的页面不记账。
     * 「已存入零钱 / 你领取了」这类第一人称文案优先于「已领取」这类双方都可能出现的文案。
     */
    fun redPacketDirection(text: String): Int? {
        if (RED_PACKET_RECEIVED.any { text.contains(it) }) return RecordType.INCOME
        if (RED_PACKET_SENT.any { text.contains(it) }) return RecordType.EXPENSE
        return null
    }

    /** 收入类分类出现在支出方向时的替代分类（发红包 → 人情） */
    private val EXPENSE_CATEGORY_FOR_INCOME = mapOf(
        "红包" to "人情",
        "礼金" to "人情",
        "收款" to "其他",
        "退款" to "其他",
        "工资" to "其他",
        "报销" to "其他",
        "利息" to "其他",
        "分红" to "其他"
    )

    // ---------------- 支付方式（支出工具） ----------------

    private val PAY_METHOD_PATTERN = Pattern.compile(
        "(?:支付方式|付款方式|扣款方式|支付渠道|付款渠道|支付工具)[:：]?\\s*([^\\n]{1,16})"
    )

    /** 常见支付方式 / 支出工具关键词（用于兜底识别与规范化） */
    val KNOWN_PAY_METHODS = listOf(
        "零钱通", "零钱", "花呗", "借呗", "余额宝", "云闪付", "储蓄卡", "信用卡", "银行卡",
        "微信支付", "支付宝", "Apple Pay", "数字人民币", "京东白条", "美团月付", "抖音支付",
        "花呗分期", "亲情卡", "亲属卡", "立减金", "积分抵现", "Pay"
    )

    /** 这些词不是支付方式（红包、转账本身是业务类型，不能当支出工具） */
    private val PAY_METHOD_BLOCKLIST = listOf("红包", "转账", "收款", "付款", "支付", "明细", "记录")

    private val PAY_METHOD_NOISE = listOf("积分", "奖励", "领取", "推荐", "更多", "管理", "设置", "选择", "优惠")

    /**
     * 提取支付方式：优先读「支付方式 / 付款方式」标签行，其次在文本里找已知支付工具关键词。
     * 红包 / 转账这类业务词会被过滤掉，避免出现「支付方式：红包」这种无意义结果。
     */
    fun extractPayMethod(text: String): String {
        PAY_METHOD_PATTERN.matcher(text).let { m ->
            if (m.find()) {
                val cleaned = cleanPayMethod(m.group(1).orEmpty())
                if (cleaned.isNotEmpty()) return cleaned
            }
        }
        KNOWN_PAY_METHODS.forEach { known ->
            if (text.contains(known)) return known
        }
        return ""
    }

    private fun cleanPayMethod(raw: String): String {
        var s = raw.trim()
        // 去掉右侧箭头、冒号、以及紧接着的其它字段名
        s = s.split('>', '›', '＞').first().trim()
        s = s.trimEnd('：', ':', '的')
        s = s.replace(Regex("\\s{2,}.*$"), "").trim()
        if (s.isEmpty() || s.length > 12) return ""
        if (PAY_METHOD_NOISE.any { s.contains(it) }) return ""
        if (PAY_METHOD_BLOCKLIST.any { s == it }) return ""
        if (s.contains("时间") || s.contains("方式") && s.length > 8) return ""
        return s
    }

    /** 返回带符号金额（负号表示支出）；找不到返回 null */
    /**
     * OCR 金额纠错。
     *
     * 真实 bug：支付宝「¥11.00」被 OCR 认成「¥1100」/「¥1100.00」→ 记成 1100 元。
     * 规则：金额 ≥ 1000 且以 00 结尾（或就是整数）时，若除以 100 仍 ≥ 1，则按分为单位还原。
     * 只对**页面 OCR 通道**生效，避免影响真实大额账单。
     */
    fun fixOcrAmount(raw: Double, text: String): Double {
        if (raw < 1000) return raw
        val cents = raw / 100.0
        if (cents < 1) return raw
        // 页面里如果出现「¥11.00」这种带小数点的原始写法，说明 OCR 另有正确值，交给正常流程
        if (Regex("[¥￥]\\s*[0-9][0-9,]*\\.[0-9]{2}").containsMatchIn(text)) return raw
        return Math.round(cents * 100.0) / 100.0
    }

    /**
     * 剔除**单号本身**（不是整行）。
     *
     * 真实 bug：支付宝「商户单号 XP1026…」里的长数字串会被当成金额；
     * 但微信 OCR 常把整页压成**一行**，里面还混着「转账单号」和真实金额，
     * 若按整行删除会把金额一起删掉（微信转账一直识别不出来的原因）。
     * 所以只删"单号后面的长数字串"（≥10 位连续数字）。
     */
    private val SERIAL_DIGITS = Regex("(?:单号|交易号|订单号|凭证号)[:：]?\\s*[A-Za-z]*[0-9]{8,}")

    private fun stripSerialLines(text: String): String = text.replace(SERIAL_DIGITS, "")

    fun extractAmount(text: String): Double? {
        val text = stripSerialLines(text)
        LABELED_AMOUNT.matcher(text).let { m ->
            if (m.find()) m.group(1).replace(",", "").toDoubleOrNull()?.let { if (it > 0) return it }
        }
        // 独立成行的金额（账单页大字）——取第一个，通常就是交易金额
        LINE_AMOUNT.matcher(text).let { m ->
            if (m.find()) {
                val sign = m.group(1).orEmpty()
                val v = m.group(2).replace(",", "").toDoubleOrNull()
                if (v != null && v > 0 && v <= PaymentParser.MAX_AMOUNT) {
                    return if (sign == "-" || sign == "－") -v else v
                }
            }
        }
        // 空格分隔的独立金额 token（OCR 常见的排版）
        TOKEN_AMOUNT.matcher(text).let { m ->
            while (m.find()) {
                val sign = m.group(1).orEmpty()
                val v = m.group(2).replace(",", "").toDoubleOrNull()
                if (v != null && v > 0 && v <= PaymentParser.MAX_AMOUNT) {
                    // 有符号时优先信符号；无符号时取第一个合理值
                    return if (sign == "-" || sign == "－") -v else v
                }
            }
        }
        CURRENCY_AMOUNT.matcher(text).let { m ->
            if (m.find()) m.group(1).replace(",", "").toDoubleOrNull()?.let { if (it > 0) return it }
        }
        YUAN_AMOUNT.matcher(text).let { m ->
            if (m.find()) m.group(1).replace(",", "").toDoubleOrNull()?.let { if (it > 0) return it }
        }
        return null
    }

    /** 候选串**整体**等于某个字段标签时才算标签（避免「拼多多平台商户」被误杀） */
    private val LABEL_ONLY = setOf(
        "商户", "商户全称", "收款方", "收款方全称", "收单机构", "商品说明", "账单分类",
        "标签", "备注", "更多信息", "推荐服务", "票单管理", "全部账单", "账单详情",
        "支付时间", "付款方式", "交易单号", "商户单号", "当前状态", "支付奖励",
        "更多", "收起", "展开", "请选择", "添加", "联系商家", "查看往来记录"
    )

    private fun isLabelOnly(s: String): Boolean = LABEL_ONLY.contains(s.trim())

    /**
     * 含「商户」二字但**不是纯标签**的一行，基本就是商户名。
     * OCR 常把「拼」认成「評/评/拼」，所以这里不依赖具体品牌字。
     * 真实 bug：支付宝拼多多账单的「拼多多平台商户」一直取不到，兜底成了平台名"支付宝"。
     */
    private val PLATFORM_MERCHANT = Regex("(?m)^[^\\n]{0,12}(?:平台商户|商户)$")

    fun extractMerchant(text: String): String? {
        // ① 最优先：形如「拼多多平台商户」的独立行
        text.split("\n").map { it.trim() }.forEach { line ->
            val clean = line.trimEnd('>', '›').trim()
            if (clean.length in 4..24 && PLATFORM_MERCHANT.matches(clean) &&
                !isLabelOnly(clean) && !clean.contains("单号")
            ) return clean
        }
        // 转账详情页 / 零钱明细：「转账-来自李四」「转账-给张三」里的名字就是对方
        // （以前会落到兜底文案「微信转账」，商户名就丢了）
        // 「转账-转检阮祝後」：OCR 把"给"认成别的字，所以第 3 字不限定；
        // 但必须**后面紧跟一个中文名字**，且不能是「转账时间/转账说明/转账单号」这类字段名。
        Regex("转账[-—·]?(?:来自|转[^\\s时说单])?\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{2,16})").find(text)?.let { m ->
            val name = m.groupValues[1].trim().trimEnd('的', ' ', '（', '(')
            val bad = listOf("时间", "说明", "单号", "凭证", "详情", "金额", "服务", "记录")
            if (name.length in 1..20 && bad.none { name.contains(it) }) return name
        }
        MERCHANT_PATTERNS.forEach { p ->
            val m = p.matcher(text)
            if (m.find()) {
                val raw = m.group(1).orEmpty().trim().trimEnd('的', '>', '›')
                // 去掉名字前的头像表情等非文字字符（如「🐱 示例昵称」→「示例昵称」）；
                // 如果整个名字就是表情（如「🐱」），保留原样，别把对方名字丢掉
                val cleaned = raw.replace(Regex("^[^\\p{IsHan}\\p{L}\\p{N}]+"), "").trim()
                val g = if (cleaned.length >= 2) cleaned else raw
                if (g.length >= 2 && !isLabelOnly(g) && NOT_MERCHANT.none { g.contains(it) }) return g
            }
        }
        // 支付宝/微信账单详情：字段标签后面的**值**才是商户
        // 形如「商户全称 昆明理工大学津桥学院」或「商户全称\n昆明理工大学津桥学院」
        val LABEL_VALUE = Regex(
            "(?:商户全称|收款方全称|收款方|商户名称)[:：]?\\s*[|｜]?\\s*([^\\n|｜]{2,30})"
        )
        LABEL_VALUE.find(text)?.let { m ->
            val v = m.groupValues[1].trim().trimEnd('>', '›').trim()
            if (v.length in 2..30 && NOT_MERCHANT.none { v.contains(it) } &&
                !v.matches(Regex("[0-9.,¥￥+\\-\\s]+"))
            ) return v
        }
        // 兜底：金额行上方最近的一行通常是商户名
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val amountIdx = lines.indexOfFirst { LINE_AMOUNT.matcher(it).matches() }
        if (amountIdx > 0) {
            for (i in amountIdx - 1 downTo maxOf(0, amountIdx - 3)) {
                val cand = lines[i].trimEnd('>', '›').trim()
                if (cand.length in 2..30 && !isLabelOnly(cand) && NOT_MERCHANT.none { cand.contains(it) } &&
                    !cand.matches(Regex("[0-9.,¥￥+\\-\\s]+"))
                ) return cand
            }
        }
        // 转账收款页/详情页上没有对方名字时，用「微信转账」兜底（别把状态文案当商户）
        if (text.contains("转账时间") || text.contains("收款时间") || text.contains("转账详情")) {
            return "微信转账"
        }
        return null
    }

    fun extractPlatformCategory(text: String): String? {
        val m = PLATFORM_CATEGORY_PATTERN.matcher(text)
        while (m.find()) {
            val raw = m.group(1).orEmpty()
            PLATFORM_CATEGORY[raw]?.let { return it }
            // 允许平台分类名不完全一致（如「教育培训」vs「教育」）
            PLATFORM_CATEGORY.forEach { (k, v) -> if (raw.contains(k) || k.contains(raw)) return v }
        }
        return null
    }

    fun extractTime(text: String): Long? {
        val m = TIME_PATTERN.matcher(text)
        if (m.find()) return TimeUtil_parse(m.group(1))
        return null
    }

    /** 供解析器使用的宽松时间解析（避免 core 依赖 util 的具体实现细节） */
    private fun TimeUtil_parse(raw: String): Long? = com.jianji.app.util.TimeUtil.parseAny(raw)
}
