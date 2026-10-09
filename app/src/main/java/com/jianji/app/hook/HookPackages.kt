package com.jianji.app.hook

/**
 * **Hook 模块接管的包名清单**（唯一来源）。
 *
 * 为什么单独放一个文件：
 * - Hook 跑在 system_server / 微信 / 银行等**别人的进程**里，必须刻意避免引用应用类
 *   （某个类初始化失败会让整个 hook 被静默跳过）。这个文件只含一个纯字符串集合，零依赖。
 * - 以前 Hook 内部自己写了一份名单，应用侧的 `PaymentParser.APP_NAMES` 又是另一份，
 *   两边不同步就会出现「界面里登记了、Hook 却把通知丢掉」——购物/外卖/银行的账单就是这么漏的。
 *   现在两边都引用这里，并由 `PlatformCoverageTest` 在编译期测试里保证一致。
 *
 * 新增平台时：① 这里加包名；② `PaymentParser.APP_NAMES` 加包名+展示名；
 * ③ `META-INF/xposed/scope.list` 加同一行（让模块默认可挂载）。
 */
object HookPackages {

    val ALL: Set<String> = setOf(
        // ---------- 支付 / 钱包 ----------
        "com.tencent.mm", "com.eg.android.AlipayGphone", "com.unionpay", "cn.gov.pbc.dcep",

        // ---------- 银行 ----------
        "com.icbc", "com.icbc.im", "com.chinamworld.main", "com.chinamworld.bocmbci",
        "com.android.bankabc", "com.bankcomm.Bankcomm", "cmb.pb",
        "com.cmbchina.ccd.pluto.cmbActivity", "com.yitong.mbank.psbc",
        "com.ecitic.bank.mobile", "cn.com.spdb.mobilebank.per", "com.cebbank.mobile.cemb",
        "com.cgbchina.xpt", "cn.com.cmbc.newmbank", "com.pingan.paces.ccms",
        "com.pingan.pabank.activity", "com.cib.cibmb", "com.hxb.mobile.client",
        "com.bank.ningbo.mobilebank", "com.csii.zjrcu.mobilebank",
        "com.boc.bocsoft.mobile.bocmobile", "com.epay.bank",

        // ---------- 购物 ----------
        "com.taobao.taobao", "com.tmall.wireless", "com.jingdong.app.mall", "com.jd.jrapp",
        "com.xunmeng.pinduoduo", "com.achievo.vipshop", "com.suning.mobile.ebuy",
        "com.xingin.xhs", "com.shizhuang.duapp", "com.kaola", "com.netease.yanxuan",
        "com.taobao.idlefish", "com.alibaba.aliexpresshd", "com.wudaokou.hippo",
        "com.dingdong.purchase", "com.yaya.zone",

        // ---------- 外卖 / 餐饮 ----------
        "com.sankuai.meituan.takeoutnew", "com.sankuai.meituan", "me.ele",
        "com.luckycoffee.luckin", "com.starbucks.cn", "com.yum.kfc", "com.mcdonalds.app",
        "com.yum.pizzahut", "com.mxbc.mxsa", "com.hongyi.h5.mxbc", "com.cns.xiangpiaopiao",
        "com.dianping.v1", "com.taobao.trip",

        // ---------- 出行 ----------
        "com.sdu.didi.psnger", "com.jingyao.easybike", "com.sdu.didi.gsui",
        "com.MobileTicket", "com.ctrip.android.view", "com.Qunar", "com.taxis99",
        "com.autonavi.minimap", "com.baidu.BaiduMap", "cn.com.egova.egovamobile",

        // ---------- 娱乐 / 通讯（会员、话费也常见） ----------
        "com.tencent.qqlive", "com.qiyi.video", "com.hunantv.imgo.activity",
        "tv.danmaku.bili", "com.netease.cloudmusic", "com.tencent.qqmusic",
        "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite",
        "com.smile.gifmaker", "com.kuaishou.nebula", "com.ximalaya.ting.android",
        "com.chinamobile.contacts.im", "com.sinovatech.unicom.ui",
        "com.greenpoint.android.mc10086.activity", "com.ct.client"
    )
}