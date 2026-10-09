package com.jianji.app.core

/**
 * 分类体系：颜色、归类规则。纯 Kotlin（无 Android 资源依赖），便于单元测试。
 * 图标资源映射在 ui.CategoryIcons 中。
 */
object Categories {

    val EXPENSE_LIST = listOf(
        "餐饮", "交通", "购物", "服饰", "日用", "娱乐", "医疗", "居住", "通讯",
        "教育", "旅行", "宠物", "运动", "数码", "母婴", "汽车", "人情", "快递",
        "美容", "保险", "税费", "办公", "其他"
    )

    val INCOME_LIST = listOf(
        "工资", "奖金", "兼职", "收款", "红包", "退款", "报销", "理财", "利息", "分红", "礼金", "其他"
    )

    /** 分类 -> 圆形底色（ARGB） */
    val BG_COLORS = mapOf(
        // 支出
        "餐饮" to 0xFFFF7043, "交通" to 0xFF42A5F5, "购物" to 0xFFAB47BC, "服饰" to 0xFFEC407A,
        "日用" to 0xFF26C6DA, "娱乐" to 0xFF7E57C2, "医疗" to 0xFF66BB6A, "居住" to 0xFF8D6E63,
        "通讯" to 0xFF5C6BC0, "教育" to 0xFF29B6F6, "旅行" to 0xFF26A69A, "宠物" to 0xFFFFA726,
        "运动" to 0xFFEF5350, "数码" to 0xFF78909C, "母婴" to 0xFFF06292, "汽车" to 0xFF546E7A,
        "人情" to 0xFFFF8A65, "快递" to 0xFFA1887F, "美容" to 0xFFF48FB1, "保险" to 0xFF4DB6AC,
        "税费" to 0xFF90A4AE, "办公" to 0xFF7986CB, "其他" to 0xFFB0BEC5,
        // 收入
        "工资" to 0xFF43A047, "奖金" to 0xFF2E7D32, "兼职" to 0xFF00897B, "收款" to 0xFF009688,
        "红包" to 0xFFE53935, "退款" to 0xFF9575CD, "报销" to 0xFF4DD0E1, "理财" to 0xFFFFB300,
        "利息" to 0xFFC0A16B, "分红" to 0xFFF4511E, "礼金" to 0xFFD81B60,
        // 其他来源
        "短信" to 0xFFF4511E
    )

    fun default(isIncome: Boolean): String = if (isIncome) "收款" else "其他"

    fun bgColor(category: String): Int = (BG_COLORS[category] ?: 0xFFB0BEC5).toInt()

    /** 只能是收入的分类（红包/退款/工资…），用于校验方向是否自洽 */
    fun isIncomeCategory(category: String): Boolean =
        category != "其他" && INCOME_LIST.contains(category)

    /** 只能是支出的分类 */
    fun isExpenseCategory(category: String): Boolean =
        category != "其他" && EXPENSE_LIST.contains(category)

    /**
     * 方向与分类的一致性校正：
     * 分类是「退款/收款/红包…」这类只可能是收入的分类时，方向必须是收入。
     * 修复「商品退款被记成支出」这类问题。
     */
    fun normalizeType(type: Int, category: String): Int =
        if (type == RecordType.EXPENSE && isIncomeCategory(category)) RecordType.INCOME else type

    /** 支出分类关键词（顺序即优先级：越具体的越靠前） */
    private val EXPENSE_RULES: List<Pair<String, List<String>>> = listOf(
        "餐饮" to listOf(
            "餐", "饭", "食", "咖", "奶茶", "茶", "烧烤", "小吃", "外卖", "美团", "饿了么", "肯德基",
            "麦当劳", "汉堡", "披萨", "必胜客", "米线", "火锅", "早餐", "午餐", "晚餐", "夜宵", "烘焙",
            "面包", "蛋糕", "瑞幸", "星巴克", "喜茶", "奈雪", "蜜雪", "茶百道", "古茗", "书亦", "沪上阿姨",
            "塔斯汀", "华莱士", "真功夫", "老乡鸡", "沙县", "兰州", "麻辣烫", "冒菜", "快餐", "食堂",
            "餐厅", "酒楼", "寿司", "料理", "烤肉", "串串", "卤味", "鸭脖", "水果", "零食", "菜市", "早点"
        ),
        "娱乐" to listOf(
            "电影", "影院", "影城", "剧场", "游戏", "会员", "视频", "音乐", "网易云", "腾讯视频", "爱奇艺", "优酷",
            "哔哩哔哩", "KTV", "景区", "门票", "公园", "健身", "淘票票", "猫眼", "演出", "话剧", "密室",
            "剧本", "网吧", "电竞", "桌游", "演唱会", "展览", "展馆", "酒吧", "livehouse", "手办", "盲盒"
        ),
        "交通" to listOf(
            "加油", "中国石化", "中国石油", "壳牌", "充电桩", "地铁", "公交", "出租", "打车", "滴滴",
            "花小猪", "曹操", "高铁", "火车", "机票", "航空", "航班", "停车", "高速", "单车", "骑行",
            "哈啰", "青桔", "出行", "客运", "轮渡", "代驾", "网约车", "交通", "车费"
        ),
        "汽车" to listOf("汽车", "保养", "4S", "车险", "洗车", "轮胎", "年检", "违章", "车载", "贴膜", "改装"),
        "快递" to listOf("快递", "顺丰", "圆通", "申通", "中通", "韵达", "邮政", "EMS", "菜鸟", "物流", "寄件", "运费"),
        "购物" to listOf(
            "超市", "便利", "商城", "百货", "淘宝", "天猫", "京东", "拼多多", "市场", "商店", "专卖",
            "盒马", "山姆", "麦德龙", "沃尔玛", "永辉", "大润发", "物美", "华润万家", "罗森", "全家",
            "711", "生鲜", "买菜", "朴朴", "叮咚", "唯品会", "苏宁", "严选", "小红书", "得物", "闲鱼",
            "购物", "商场", "折扣", "免税", "抖音商城"
        ),
        "服饰" to listOf(
            "服饰", "服装", "衣", "鞋", "帽", "优衣库", "耐克", "阿迪", "李宁", "安踏", "连衣裙",
            "内衣", "袜", "箱包", "无印良品", "牛仔", "羽绒"
        ),
        "数码" to listOf(
            "数码", "电脑", "手机", "平板", "耳机", "相机", "键盘", "硬盘", "显示器", "小米", "华为",
            "苹果", "显卡", "路由器", "充电宝", "配件", "智能"
        ),
        "母婴" to listOf("母婴", "奶粉", "尿不湿", "婴儿", "幼儿", "童装", "玩具", "早教", "辅食"),
        "宠物" to listOf("宠物", "猫", "狗", "犬", "猫粮", "狗粮", "兽医", "铲屎", "水族", "鸟粮"),
        "运动" to listOf("运动", "球", "瑜伽", "游泳", "跑步", "户外", "登山", "羽毛球", "篮球", "足球", "滑雪", "装备"),
        "美容" to listOf("美容", "美发", "理发", "美甲", "化妆", "护肤", "面膜", "香水", "美睫", "美瞳", "spa"),
        "日用" to listOf("日用", "纸巾", "洗护", "洗衣", "牙膏", "五金", "家居", "清洁", "垃圾袋", "雨伞", "收纳"),
        "通讯" to listOf("话费", "流量", "宽带", "移动", "联通", "电信", "手机费", "副卡", "网费", "充值"),
        "居住" to listOf("酒店", "宾馆", "民宿", "公寓", "物业", "房租", "水费", "电费", "燃气", "取暖", "房贷", "租金", "宿舍", "家政"),
        "教育" to listOf("学费", "培训", "教育", "课程", "网课", "书", "图书", "书店", "考试", "报名", "文具", "幼儿园", "学校", "驾校", "学习", "讲座", "考证"),
        "旅行" to listOf("旅行", "旅游", "携程", "去哪儿", "飞猪", "同程", "跟团", "景点", "导游", "签证", "度假", "民宿"),
        "医疗" to listOf("药", "医院", "诊所", "健康", "牙科", "体检", "门诊", "挂号", "医疗", "疫苗", "眼科", "口腔", "中医", "推拿"),
        "保险" to listOf("保险", "保费", "重疾", "人寿", "平安", "车船税"),
        "税费" to listOf("税", "个税", "社保", "公积金", "罚款", "缴费", "行政", "手续费"),
        "办公" to listOf("办公", "打印", "耗材", "云服务", "服务器", "域名", "软件", "订阅", "复印", "名片", "快递费"),
        "人情" to listOf("红包", "礼物", "礼品", "请客", "份子", "婚礼", "随礼", "人情", "慰问", "转账", "代付", "礼金", "压岁钱")
    )

    /** 收入分类关键词（顺序即优先级） */
    private val INCOME_RULES: List<Pair<String, List<String>>> = listOf(
        "红包" to listOf("红包", "压岁钱"),
        "工资" to listOf("工资", "薪资", "薪酬", "月薪", "代发", "发薪"),
        "奖金" to listOf("奖金", "年终", "绩效", "提成", "奖励", "补贴", "津贴"),
        "兼职" to listOf("兼职", "外快", "稿费", "劳务", "副业", "私活", "佣金"),
        "报销" to listOf("报销", "差旅", "垫付"),
        "退款" to listOf("退款", "退货", "退回", "原路", "返现", "补偿"),
        "分红" to listOf("分红", "派息", "股息"),
        "利息" to listOf("利息", "国债", "存款"),
        "理财" to listOf("理财", "基金", "股票", "收益", "余额宝", "定期"),
        "礼金" to listOf("礼金", "随礼", "份子", "彩礼", "见面礼")
    )

    /**
     * 根据商户名与原文猜测分类（best-effort，猜不出回退默认值）。
     * @param textHint 自动记账时的原文，用于补充判断（如红包、退款）
     * @param pkg 来源应用包名，用于平台级兜底归类（如美团 -> 餐饮）
     */
    fun guess(merchant: String, isIncome: Boolean, textHint: String = "", pkg: String = ""): String {
        val hint = merchant + " " + textHint
        if (isIncome) {
            INCOME_RULES.forEach { (cat, keys) ->
                if (keys.any { hint.contains(it) }) return cat
            }
            return "收款"
        }
        EXPENSE_RULES.forEach { (cat, keys) ->
            if (keys.any { merchant.contains(it) }) return cat
        }
        // 商户识别不出时，用原文兜底
        EXPENSE_RULES.forEach { (cat, keys) ->
            if (keys.any { textHint.contains(it) }) return cat
        }
        // 平台兜底：美团->餐饮，滴滴->交通，淘宝京东->购物 ...
        val p = pkg.lowercase()
        PLATFORM_CATEGORY.forEach { (k, v) -> if (p.contains(k)) return v }
        return "其他"
    }

    /** 购物平台包名 -> 归类提示 */
    private val PLATFORM_CATEGORY = mapOf(
        "sankuai" to "餐饮", "ele" to "餐饮",
        "didi" to "交通", "jingyao" to "交通", "mobileticket" to "交通", "ctrip" to "旅行", "qunar" to "旅行",
        "taobao" to "购物", "tmall" to "购物", "jingdong" to "购物", "xunmeng" to "购物",
        "vipshop" to "购物", "suning" to "购物", "gifmaker" to "购物", "unionpay" to "其他"
    )
}
