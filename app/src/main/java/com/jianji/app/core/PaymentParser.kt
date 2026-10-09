package com.jianji.app.core

import java.util.regex.Pattern

/**
 * 支付内容解析器（通知 / 消息文本，纯 Kotlin，无 Android 依赖，JVM 单元测试覆盖）。
 *
 * 输入：来源应用包名 + 通知标题 + 通知/页面文本
 * 输出：[Parsed]（类型、金额、商户、置信度）；无法确信时返回 null，宁漏勿错。
 */
object PaymentParser {

    const val PKG_WECHAT = "com.tencent.mm"
    const val PKG_ALIPAY = "com.eg.android.AlipayGphone"

    /** 单笔金额上限，超过视为误报 */
    const val MAX_AMOUNT = 1_000_000.0

    /**
     * 平台包名 -> 展示名（**银行 / 购物 / 外卖 / 出行 / 生活缴费**）。
     *
     * 用途：
     * - 来源标注（识别记录、账单来源列）；
     * - Hook 模块的接管范围与 `HookProtocol.isWatched` 判定；
     * - 分类兜底（平台名参与分类猜测，例如「美团外卖」→ 餐饮）。
     *
     * 注：模块侧另有一份**硬编码**的包名集合（hook 不能引用应用类），
     * 两边新增平台时要一起加（见 `HookProtocol.FAST_WATCHED`）。
     */
    val APP_NAMES = mapOf(
        // ---------- 支付 / 钱包 ----------
        "com.unionpay" to "云闪付",
        "cn.gov.pbc.dcep" to "数字人民币",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.tencent.mm" to "微信",

        // ---------- 银行（主流 + 常见股份制/城商） ----------
        "com.icbc" to "工商银行",
        "com.icbc.im" to "工银e生活",
        "com.chinamworld.main" to "建设银行",
        "com.chinamworld.bocmbci" to "中国银行",
        "com.android.bankabc" to "农业银行",
        "com.bankcomm.Bankcomm" to "交通银行",
        "cmb.pb" to "招商银行",
        "com.cmbchina.ccd.pluto.cmbActivity" to "招商银行掌上生活",
        "com.yitong.mbank.psbc" to "邮储银行",
        "com.ecitic.bank.mobile" to "中信银行",
        "cn.com.spdb.mobilebank.per" to "浦发银行",
        "com.cebbank.mobile.cemb" to "光大银行",
        "com.cgbchina.xpt" to "广发银行",
        "cn.com.cmbc.newmbank" to "民生银行",
        "com.pingan.paces.ccms" to "平安银行",
        "com.pingan.pabank.activity" to "平安口袋银行",
        "com.cib.cibmb" to "兴业银行",
        "com.hxb.mobile.client" to "华夏银行",
        "com.bank.ningbo.mobilebank" to "宁波银行",
        "com.csii.zjrcu.mobilebank" to "农村商业银行",
        "com.boc.bocsoft.mobile.bocmobile" to "中国银行手机银行",
        "com.epay.bank" to "银行",

        // ---------- 购物 ----------
        "com.taobao.taobao" to "淘宝",
        "com.tmall.wireless" to "天猫",
        "com.jingdong.app.mall" to "京东",
        "com.jd.jrapp" to "京东金融",
        "com.xunmeng.pinduoduo" to "拼多多",
        "com.achievo.vipshop" to "唯品会",
        "com.suning.mobile.ebuy" to "苏宁易购",
        "com.xingin.xhs" to "小红书",
        "com.shizhuang.duapp" to "得物",
        "com.kaola" to "网易考拉",
        "com.netease.yanxuan" to "网易严选",
        "com.taobao.idlefish" to "闲鱼",
        "com.alibaba.aliexpresshd" to "速卖通",
        "com.wudaokou.hippo" to "盒马",
        "com.dingdong.purchase" to "叮咚买菜",
        "com.yaya.zone" to "每日优鲜",

        // ---------- 外卖 / 餐饮 ----------
        "com.sankuai.meituan.takeoutnew" to "美团外卖",
        "com.sankuai.meituan" to "美团",
        "me.ele" to "饿了么",
        "com.luckycoffee.luckin" to "瑞幸咖啡",
        "com.starbucks.cn" to "星巴克",
        "com.yum.kfc" to "肯德基",
        "com.mcdonalds.app" to "麦当劳",
        "com.yum.pizzahut" to "必胜客",
        "com.mxbc.mxsa" to "蜜雪冰城",
        "com.hongyi.h5.mxbc" to "茶百道",
        "com.cns.xiangpiaopiao" to "香飘飘",
        "com.dianping.v1" to "大众点评",
        "com.taobao.trip" to "飞猪",

        // ---------- 出行 / 生活 ----------
        "com.sdu.didi.psnger" to "滴滴出行",
        "com.jingyao.easybike" to "哈啰",
        "com.sdu.didi.gsui" to "滴滴司机",
        "com.MobileTicket" to "铁路12306",
        "com.ctrip.android.view" to "携程",
        "com.Qunar" to "去哪儿",
        "com.taxis99" to "曹操出行",
        "com.autonavi.minimap" to "高德打车",
        "com.baidu.BaiduMap" to "百度地图",
        "cn.com.egova.egovamobile" to "城市服务",
        "com.tencent.qqlive" to "腾讯视频",
        "com.qiyi.video" to "爱奇艺",
        "com.hunantv.imgo.activity" to "芒果TV",
        "tv.danmaku.bili" to "哔哩哔哩",
        "com.netease.cloudmusic" to "网易云音乐",
        "com.tencent.qqmusic" to "QQ音乐",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.ss.android.ugc.aweme.lite" to "抖音极速版",
        "com.smile.gifmaker" to "快手",
        "com.kuaishou.nebula" to "快手极速版",
        "com.ximalaya.ting.android" to "喜马拉雅",
        "com.chinamobile.contacts.im" to "中国移动",
        "com.sinovatech.unicom.ui" to "中国联通",
        "com.greenpoint.android.mc10086.activity" to "中国移动营业厅",
        "com.ct.client" to "中国电信"
    )

    private val AMOUNT_PATTERNS = listOf(
        Pattern.compile("[¥￥]\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"),
        Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元"),
        Pattern.compile("人民币\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    )

    /** 独立成行的纯数字金额（账单页大字，无 ¥ / 元） */
    private val LINE_AMOUNT = Pattern.compile(
        "^\\s*([+＋\\-－])?\\s*[¥￥]?\\s*([0-9][0-9,]*\\.[0-9]{1,2})\\s*$",
        Pattern.MULTILINE
    )

    private val INCOME_PATTERNS = listOf(
        "收款成功", "到账", "已收款", "收到付款", "向你付款", "转入成功", "入账",
        "退款成功", "退款到账", "已退款", "退货退款", "全额退款", "退款给", "原路退回",
        "退款通知", "退款金额", "退款已", "已退回",
        "收款金额", "收到.{0,10}红包", "收到.{0,8}转账", "收款通知",
        "来自.{0,12}(转账|红包|付款)",
        // 收红包 / 收转账（与「发红包」严格区分）
        "领取了.{0,12}红包", "你领取了", "抢到.{0,6}红包", "红包.{0,6}已到账", "红包到账",
        "转账给你", "向你转账", "给你转账", "已收钱"
    ).map { Pattern.compile(it) }

    /** 退款关键词：出现时豁免「零钱通/余额宝/提现/充值」的资金内部转移拦截 */
    private val REFUND_WORDS = listOf(
        "退款", "退货", "退还", "已退回", "原路退回", "退款成功", "退款到账", "全额退款", "部分退款"
    )

    private val EXPENSE_PATTERNS = listOf(
        "支付成功", "付款成功", "成功支付", "成功付款", "已成功向", "已向.{0,20}(支付|付款|转账)",
        "支出", "扣款成功", "消费成功", "消费", "已支付", "转账成功", "付款金额", "购买成功",
        // 免密 / 自动扣款 / 代扣 / 订阅续费（图三那类通知）
        "免密支付", "免密.{0,6}扣款", "自动扣款", "自动续费", "代扣", "已扣款", "扣款", "续费",
        "连续包月", "会员续费", "有一笔.{0,20}(支付|扣款|消费)",
        // 微信发红包 / 转出（注意不要用裸「转账给」，否则会把「张三转账给你」判成支出）
        "你已(?:成功)?(?:发送|发出|发了|包了)", "你发送了一个红包", "(?:发送|发出|已发|包)了?(?:一个)?红包",
        "发送红包", "发出红包", "红包已被领取", "红包已被领完", "已发送.{0,6}红包",
        "红包[^\\n]{0,12}(?:已发送|已发出|已转出)",
        "你已转账", "已成功转账", "向[^你]{1,16}转账", "转给了"
    ).map { Pattern.compile(it) }

    /** 强证据：屏幕内容解析（strict）与待确认判断 */
    private val STRONG_PATTERNS = listOf(
        "支付成功", "付款成功", "成功支付", "成功付款", "收款成功", "到账", "已成功向", "退款成功",
        "转账成功", "交易成功", "已向.{0,20}(支付|付款|转账)",
        "免密支付", "自动扣款", "免密.{0,6}扣款", "扣款成功", "有一笔.{0,20}(支付|扣款|消费)",
        // 红包 / 转账（对方已领取＝你已支出；你已领取＝收入）
        "微信红包", "红包已被领取", "红包已被领完", "发送了一个红包", "领取了.{0,12}红包",
        "收到.{0,10}红包", "已成功转账"
    ).map { Pattern.compile(it) }

    /** 硬忽略：与记账无关或明确未完成，任何情况都不记录 */
    private val HARD_DENY = listOf(
        "支付失败", "付款失败", "扣款失败", "余额不足", "待支付", "待付款", "确认支付",
        "请输入支付密码", "立即支付", "去支付", "分期", "借款",
        // 「额度」不能单独作为拦截词：银行信用卡通知几乎都带「可用额度20000元」，
        // 一拦就会把所有信用卡消费都漏掉。只拦明确的营销/查询话术。
        "提额", "额度调整", "额度提升", "额度已提升", "临时额度", "额度查询", "申请额度",
        "签约", "解约", "自动续费管理", "免密支付设置",
        // 转账/红包确实未完成或已退回：钱没出去，不能记
        // （不含「待对方确认收款」—— 转账发出即扣款，收款方未确认不影响付款人）
        "等待对方确认", "已过期未领取", "超过24小时未领取", "已退还转账"
    ).map { Pattern.compile(it) }

    /**
     * 硬忽略（**退款场景下豁免**）：「零钱通 / 余额宝 / 提现 / 充值」本身是资金内部转移，
     * 不应记账；但「退款到账至零钱通」这类通知必须记成收入。
     */
    private val HARD_DENY_UNLESS_REFUND = listOf(
        "零钱通", "余额宝", "提现", "充值", "转入零钱通", "转入余额宝"
    ).map { Pattern.compile(it) }

    /** 软忽略：营销类噪声 / 开通引导，**只在没有强证据时**才拦截（账单页常带"积分/免息券"等字样） */
    private val SOFT_DENY = listOf(
        "账单", "攻略", "优惠券", "消费券", "免息", "积分", "领红包", "秒杀", "砍价", "签到",
        "金币", "抽奖", "限时", "闪购", "上新", "直播间", "专属优惠", "拼单", "邀请", "注册", "开通"
    ).map { Pattern.compile(it) }

    private val MERCHANT_PATTERNS = listOf(
        Pattern.compile("向[「【『\\[（(]?(.{1,24}?)[」】』\\]）)]?(?:支付|付款|转账)"),
        Pattern.compile("在([^,，。\\s()（）]{2,20}?)有(?:一笔|\\s*[0-9])"),
        Pattern.compile("在[「【『\\[（(]?(.{1,24}?)[」】』\\]）)]?(?:支付|消费|购买)"),
        Pattern.compile("付款给[:：]?\\s*([^,，。\\n\\s]{1,24})"),
        Pattern.compile("退款给[:：]?\\s*([^,，。\\n\\s]{1,24})"),
        Pattern.compile("商户(?:名称|全称)?[:：]\\s*([^,，。\\n\\s]{1,24})"),
        Pattern.compile("收款方[:：]?\\s*([^,，。\\n\\s]{1,24})"),
        // 红包 / 转账的对手方：给张三发红包 / 向李四转账 / 已转账给王五
        Pattern.compile("给[「【『\\[（(]?(.{1,16}?)[」】』\\]）)]?发.{0,2}红包"),
        Pattern.compile("(?:向|转账给)[「【『\\[（(]?(.{1,16}?)[」】』\\]）)]?(?:转账|发起转账)"),
        Pattern.compile("来自[「【『\\[（(]?(.{1,24}?)[」】』\\]）)]?(?:的)?(?:转账|红包|付款)"),
        Pattern.compile("(?:你领取了|收到)[「【『\\[（(]?(.{1,16}?)[」】』\\]）)]?的?红包"),
        Pattern.compile("给([^,，。\\n\\s]{1,16})$")
    )

    /** 商户字段里出现这些词说明提取错了（把正文当成了商户） */
    private val MERCHANT_NOISE = listOf(
        "支付", "付款", "转账", "成功", "红包", "退款", "余额", "零钱", "通知", "提醒",
        "订单", "商品", "金额", "一笔", "免密", "扣款", "自动"
    )

    private val PLATFORM_NAMES = setOf("微信支付", "支付宝", "微信", "支付宝支付", "零钱", "余额", "商家", "商户")

    data class Parsed(
        val type: Int,
        val amount: Double,
        val merchant: String,
        val confidence: Double,
        val payMethod: String = ""
    )

    fun sourceOf(pkg: String): Int = when (pkg) {
        PKG_WECHAT -> RecordSource.AUTO_WECHAT
        PKG_ALIPAY -> RecordSource.AUTO_ALIPAY
        else -> RecordSource.AUTO_SCREEN
    }

    fun platformName(pkg: String): String = when {
        pkg == PKG_WECHAT -> "微信"
        pkg == PKG_ALIPAY -> "支付宝"
        APP_NAMES.containsKey(pkg) -> APP_NAMES.getValue(pkg)
        else -> "支付"
    }

    /**
     * @param strict true 用于屏幕页面内容解析（必须出现强关键词才可信）
     */
    fun parse(pkg: String, title: String, text: String, strict: Boolean = false): Parsed? {
        val full = (title.trim() + "\n" + text.trim()).replace('\u00A0', ' ')
        if (full.length < 3 || full.length > 3000) return null
        if (HARD_DENY.any { it.matcher(full).find() }) return null
        // 退款关键词出现时，豁免「零钱通/余额宝/提现/充值」这类资金内部转移的拦截
        val refundish = REFUND_WORDS.any { full.contains(it) }
        if (!refundish && HARD_DENY_UNLESS_REFUND.any { it.matcher(full).find() }) return null

        val income = INCOME_PATTERNS.any { it.matcher(full).find() }
        val expense = EXPENSE_PATTERNS.any { it.matcher(full).find() }
        val strong = STRONG_PATTERNS.any { it.matcher(full).find() }

        // 软忽略只在不构成强支付证据时生效
        if (!strong && SOFT_DENY.any { it.matcher(full).find() }) return null
        // 双命中或双未命中都不处理，避免误判方向
        if (income == expense) return null
        val isIncome = income

        val amount = extractAmount(full, allowBareLine = strict || strong) ?: return null
        if (strict && !strong) return null

        val merchant = extractMerchant(full) ?: platformName(pkg)
        return Parsed(
            type = if (isIncome) RecordType.INCOME else RecordType.EXPENSE,
            amount = amount,
            merchant = merchant,
            confidence = if (strong) 1.0 else 0.7,
            payMethod = extractPayMethod(full)
        )
    }

    /** 支付方式 / 支出工具（零钱、花呗、储蓄卡…）：通知里通常没有，页面里才有 */
    fun extractPayMethod(text: String): String = ReceiptParser.extractPayMethod(text)

    /**
     * 是否是「免密支付 / 自动扣款 / 代扣 / 续费」这类**钱已经扣完**的消息。
     * 这类通知默认直接记账（并给撤销入口），不再等用户点确认 —— 否则通知被忽略就漏账。
     */
    fun isMianmi(text: String): Boolean =
        listOf("免密", "自动扣款", "代扣", "自动续费", "连续包月", "已扣款", "扣款成功")
            .any { text.contains(it) }

    /**
     * @param allowBareLine 是否允许「独立成行的纯数字金额」（账单页大字，无 ¥/元）
     */
    /**
     * 金额提取。
     *
     * **银行通知特别处理**：一条短信常同时出现多个金额，例如
     * 「您尾号8888的信用卡10月7日消费88.80元，可用额度20000元，余额5000元」
     * 必须取**交易金额**，不能取额度/余额（这是适配银行时最容易记错的地方）。
     * 做法：跳过紧跟在「余额 / 可用额度 / 剩余 / 限额 / 总额 / 积分」后面的数字。
     */
    fun extractAmount(s: String, allowBareLine: Boolean = false): Double? {
        for (p in AMOUNT_PATTERNS) {
            val m = p.matcher(s)
            var start = 0
            while (m.find(start)) {
                start = m.end()
                val raw = m.group(1).replace(",", "")
                val v = raw.toDoubleOrNull() ?: continue
                if (v <= 0.0 || v > MAX_AMOUNT) continue
                if (isBalanceLike(s, m.start())) continue
                return Math.round(v * 100.0) / 100.0
            }
        }
        if (allowBareLine) {
            val m = LINE_AMOUNT.matcher(s)
            if (m.find()) {
                val v = m.group(2).replace(",", "").toDoubleOrNull()
                if (v != null && v > 0.0 && v <= MAX_AMOUNT) return Math.round(v * 100.0) / 100.0
            }
        }
        return null
    }

    /** 金额前面这几个词说明它是"余额/额度/积分"，不是这次交易的钱 */
    private val BALANCE_WORDS = listOf(
        "余额", "可用额度", "剩余", "限额", "总额", "积分", "可用余额", "额度为", "结余", "存款"
    )

    private fun isBalanceLike(s: String, amountStart: Int): Boolean {
        val from = (amountStart - 8).coerceAtLeast(0)
        val before = s.substring(from, amountStart)
        return BALANCE_WORDS.any { before.contains(it) }
    }

    fun extractMerchant(s: String): String? {
        for (p in MERCHANT_PATTERNS) {
            val m = p.matcher(s)
            if (!m.find()) continue
            var g = (m.group(1) ?: "").trim()
            if (g.isEmpty()) continue
            g = g.trimEnd('的')
            // 去掉尾部残留的数字/货币符号（如 "给xx支付成功¥100" 误匹配）
            g = g.replace(Regex("[¥￥0-9.,:：]+$"), "").trim()
            if (g.isEmpty()) continue
            // 修正未闭合的括号（如 "瑞幸咖啡（国贸店" ）
            if (g.count { it == '（' } > g.count { it == '）' }) g += "）"
            if (g.count { it == '(' } > g.count { it == ')' }) g += ")"
            if (PLATFORM_NAMES.contains(g)) continue
            if (MERCHANT_NOISE.any { g.contains(it) }) continue
            return g
        }
        return null
    }
}
