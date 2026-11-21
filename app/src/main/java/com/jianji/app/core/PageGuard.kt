package com.jianji.app.core

/**
 * 页面级"这一页该不该记账"的硬拦截。
 *
 * 都是真机反馈出来的误记：
 *  - 微信**聊天列表页**（主界面）没有气泡，只有「昵称 + 时间 + 预览」。
 *    预览里一旦出现「已收款」「转账」这类字样，就会被当成聊天气泡逐笔解析；
 *    日志里微信主界面文本是
 *    「浮窗 退出浮窗 打开侧栏 搜索 更多功能 … 主页 主页 通讯录 通讯录 发现 发现 我 我」，
 *    命中底部导航 + 顶部工具栏，是稳定的列表页特征。 → bug「微信在主界面会莫名记账」
 *  - 支付宝**还没输金额的付款页**（付款给个人 / 输入金额）也会被兜底规则当成账单页。
 *    → bug「支付宝扫码还没打出支付金额就会自动记账」
 *  - 支付宝**首页信息流**里的「最近消息」卡片写着「闲鱼 自动扣款成功￥23.35 · 43分钟前」，
 *    它同时含有「自动扣款」和「扣款成功」，被当成免密支付成交页 → 一次打开首页记了三笔。
 *    → bug「扫码支付会识别支付宝主页的支付消息」
 */
object PageGuard {

    /** 聊天**列表页**（主界面/会话列表）才有的入口文字 */
    private val CHAT_LIST_MARKERS = listOf(
        "通讯录", "发现", "朋友圈", "微信(", "正在加载", "浮窗", "退出浮窗",
        "打开侧栏", "更多功能", "搜索", "主页"
    )

    /** 聊天**详情页**才有的输入栏 / 工具栏文字（出现即说明不是列表页） */
    private val CHAT_DETAIL_MARKERS = listOf(
        "按住 说话", "按住说话", "发送消息", "表情", "语音输入", "聊天信息", "视频通话"
    )

    /** 付款页"还没付"的操作按钮 */
    private val PENDING_PAY_MARKERS = listOf(
        "确认付款", "确认支付", "立即付款", "立即支付", "去支付", "输入金额",
        "付款金额", "请输入支付密码", "请输入密码", "指纹支付", "面容支付",
        "使用密码", "付款给", "扫一扫", "扫码支付"
    )

    /**
     * 真正的"已经成交"才算的强证据。
     * 注意**不能**放「交易时间」「付款时间」这类页脚字段：
     * 支付宝付款页会从下层活动透出「首页 理财 消息 我的」等文字，容易误判为成交页。
     */
    private val STRONG_PAID_MARKERS = listOf(
        "支付成功", "付款成功", "交易成功", "扣款成功", "已支付", "已付款",
        "收款成功", "转账成功", "退款成功", "当前状态"
    )

    /** 账单详情页才有的字段：出现任意一条就不该被任何"页面级"规则误拦 */
    private val BILL_FIELDS = listOf(
        "交易单号", "商户单号", "商户全称", "收款方", "当前状态",
        "转账时间", "收款时间", "支付时间", "付款时间", "交易时间",
        "转账单号", "账单分类", "已存入零钱", "对方已收"
    )

    /**
     * App **首页 / 信息流**的界面文字（支付宝、淘宝这类「工具宫格 + 最近消息 + 为你推荐」）。
     * 注意不要放「付款方式」这类付款页也会出现的词。
     */
    private val HOME_FEED_MARKERS = listOf(
        "松开刷新", "搜索框", "更多操作", "为你推荐", "消息盒子", "最近消息",
        "碰一下", "芝麻信用", "蚂蚁森林", "余额宝", "收付款", "卡包", "攒钱第一步"
    )

    /** 首页信息流里每条消息都带的相对时间（账单详情页没有这种写法） */
    private val RELATIVE_TIME = Regex("(\\d+\\s*分钟前|\\d+\\s*小时前|刚刚|昨天\\s*\\d{1,2}:\\d{2})")

    /**
     * 是不是 App 首页 / 信息流页（含「最近消息」这类卡片）。
     *
     * 支付宝首页那条卡片的原文是
     * 「闲鱼 自动扣款成功￥23.35  43分钟前」，同时命中「自动扣款」「扣款成功」，
     * 被当成免密成交页记了账。首页没有任何账单详情字段，只有信息流chrome + 相对时间。
     */
    fun isAppHomePage(text: String): Boolean {
        if (text.length < 20) return false
        // 真正的账单详情页一律放行（账单页也可能带"搜索/发现"这类入口）
        if (BILL_FIELDS.any { text.contains(it) }) return false
        val hits = HOME_FEED_MARKERS.count { text.contains(it) }
        if (hits < 2) return false
        // 必须还要有信息流特征：相对时间，或首页专属的推荐/消息区
        return RELATIVE_TIME.containsMatchIn(text) ||
            text.contains("为你推荐") ||
            text.contains("消息盒子") ||
            text.contains("最近消息")
    }

    /**
     * 是不是聊天的**会话列表**页（不是聊天详情页）。
     * 命中 ≥3 条列表页入口，且完全没有输入栏文字、也没有账单字段。
     */
    fun isChatListPage(text: String): Boolean {
        if (text.length < 20) return false
        if (CHAT_DETAIL_MARKERS.any { text.contains(it) }) return false
        // 账单字段一旦出现就说明这是账单页（有的 App 账单页也有"搜索/发现/主页"这类入口）
        if (BILL_FIELDS.any { text.contains(it) }) return false
        return CHAT_LIST_MARKERS.count { text.contains(it) } >= 3
    }

    /**
     * 是不是「金额还没确定」的付款页。
     * 有付款操作按钮 / 金额输入区，却没有任何成交后才会出现的字段。
     *
     * 例外：支付宝首页信息流里的消息卡片也写着「自动扣款成功」，那不是成交证据，
     * 所以判定有效成交前先排除首页。
     */
    fun isPendingPayPage(text: String): Boolean {
        if (text.length < 6) return false
        if (PENDING_PAY_MARKERS.none { text.contains(it) }) return false
        val paidEvidence = STRONG_PAID_MARKERS.any { text.contains(it) } && !isAppHomePage(text)
        return !paidEvidence
    }
}
