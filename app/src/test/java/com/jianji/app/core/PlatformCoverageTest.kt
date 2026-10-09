package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平台覆盖测试：银行 / 购物 / 外卖 / 出行 的包名与短信解析。
 *
 * 意义：新增或改名平台时，如果忘了同步（应用侧 `APP_NAMES` 与模块侧 `FAST_WATCHED`
 * 必须成对出现），这里会立刻失败 —— 避免"某个 App 怎么都不记账"的排查地狱。
 */
class PlatformCoverageTest {

    /** 银行类：至少覆盖这些常见银行 */
    private val banks = listOf(
        "com.icbc", "com.chinamworld.main", "com.chinamworld.bocmbci", "com.android.bankabc",
        "com.bankcomm.Bankcomm", "cmb.pb", "com.yitong.mbank.psbc", "com.ecitic.bank.mobile",
        "cn.com.spdb.mobilebank.per", "com.cebbank.mobile.cemb", "com.cgbchina.xpt",
        "cn.com.cmbc.newmbank", "com.pingan.paces.ccms", "com.cib.cibmb", "com.hxb.mobile.client"
    )

    /** 购物 / 外卖 / 生活类 */
    private val shops = listOf(
        "com.taobao.taobao", "com.tmall.wireless", "com.jingdong.app.mall", "com.xunmeng.pinduoduo",
        "com.achievo.vipshop", "com.suning.mobile.ebuy", "com.xingin.xhs", "com.shizhuang.duapp",
        "com.sankuai.meituan.takeoutnew", "me.ele", "com.luckycoffee.luckin", "com.yum.kfc",
        "com.dianping.v1", "com.sdu.didi.psnger", "com.jingyao.easybike", "com.MobileTicket",
        "com.ctrip.android.view", "com.Qunar", "com.autonavi.minimap", "com.ss.android.ugc.aweme",
        "com.smile.gifmaker", "com.tencent.qqlive", "tv.danmaku.bili"
    )

    @Test
    fun bankPackagesAreKnown() {
        banks.forEach { pkg ->
            assertTrue("银行包名未登记：$pkg", PaymentParser.APP_NAMES.containsKey(pkg))
            assertTrue("平台名不应为空：$pkg", PaymentParser.platformName(pkg).isNotBlank())
            assertEquals("来源应识别为 App：$pkg", RecordSource.AUTO_SCREEN, PaymentParser.sourceOf(pkg))
        }
    }

    @Test
    fun shoppingPackagesAreKnown() {
        shops.forEach { pkg ->
            assertTrue("购物/外卖包名未登记：$pkg", PaymentParser.APP_NAMES.containsKey(pkg))
            assertEquals("来源应识别为 App：$pkg", RecordSource.AUTO_SCREEN, PaymentParser.sourceOf(pkg))
        }
    }

    /** 关键：模块侧硬编码集合必须包含所有登记平台，否则 hook 不会挂上去 */
    @Test
    fun allKnownPackagesAreInHookScope() {
        PaymentParser.APP_NAMES.keys.forEach { pkg ->
            when (pkg) {
                PaymentParser.PKG_WECHAT, PaymentParser.PKG_ALIPAY -> Unit
                else -> assertTrue("模块侧 FAST_WATCHED 缺这个包，hook 不会接管：$pkg", HookProtocol.isWatchedFast(pkg))
            }
        }
    }

    @Test
    fun scopeListCoversBanksAndShops() {
        banks.forEach { assertTrue("LSPosed 作用域缺少：$it", HookProtocol.isWatchedFast(it)) }
        shops.forEach { assertTrue("LSPosed 作用域缺少：$it", HookProtocol.isWatchedFast(it)) }
    }

    /**
     * 银行 App 通知（不是短信）也能解析出**正确的交易金额**。
     *
     * 重点：信用卡通知常同时出现「消费金额 / 可用额度 / 余额」，
     * 必须取交易金额（曾经把"额度"当硬拦截词，导致这类通知全被漏掉）。
     */
    @Test
    fun parsesBankAppNotification() {
        val cases = listOf(
            Triple("工商银行", "您尾号1234的储蓄卡10月7日消费人民币88.00元", 88.00),
            Triple("招商银行", "您账户1234于10月7日支出(消费)人民币￥45.50", 45.50),
            Triple("建设银行", "您尾号8888的信用卡10月7日消费88.80元，可用额度20000元，余额5000元", 88.80),
            Triple("中国银行", "您尾号6666信用卡10月7日网上支付支出人民币128.00元，可用额度30000元", 128.00)
        )
        cases.forEach { (title, text, expect) ->
            val p = PaymentParser.parse("com.icbc", title, text)
            assertNotNull("应能解析：$text", p)
            assertEquals("应为支出：$text", RecordType.EXPENSE, p!!.type)
            assertEquals("金额取错（应取交易金额而非额度/余额）：$text", expect, p.amount, 0.001)
        }
    }

    /** 银行短信：955xx 与 106 通道都要认 */
    @Test
    fun parsesBankSmsFromAllChannels() {
        val senders = listOf("95588", "95533", "1069032900202", "13800000000")
        senders.forEach { sender ->
            val p = SmsParser.parse(sender, "【招商银行】您尾号1234的账户10月7日消费人民币26.00元")
            assertNotNull("短信发送方 $sender 应被识别", p)
            assertEquals(RecordType.EXPENSE, p!!.type)
            assertEquals(26.0, p.amount, 0.001)
        }
    }

    /** 没有银行语境的普通短信不能被当成交易 */
    @Test
    fun ignoresNonBankSms() {
        assertEquals(null, SmsParser.parse("1069000000", "【某某商城】双十一大促，全场五折，点击查看"))
    }
    /**
     * **最重要的一条**：Hook 模块的接管名单必须与应用侧登记完全一致。
     *
     * 出过一次真实事故：应用侧登记了 72 个平台，而 Hook 模块内部另有一份 17 个包的老名单，
     * 结果购物/外卖/银行的账单被模块**静默丢掉**（界面显示"已接管"，实际根本没转发）。
     * 现在两边共用 HookPackages.ALL，这里双向校验，任何一侧漏改都会让测试失败。
     */
    @Test
    fun hookModuleSharesOnePackageList() {
        val hookList = com.jianji.app.hook.HookPackages.ALL
        assertEquals("应用侧与模块侧不是同一份清单", hookList, HookProtocol.FAST_WATCHED)

        // 应用登记的平台，模块必须也接管（否则通知会被模块丢掉）
        PaymentParser.APP_NAMES.keys.forEach { pkg ->
            assertTrue("模块不接管这个包：$pkg", hookList.contains(pkg))
        }
        // 模块接管的每个包，应用侧都应有展示名（否则来源/分类标注会退化成"支付"）
        hookList.forEach { pkg ->
            assertTrue("应用侧缺少展示名：$pkg", PaymentParser.APP_NAMES.containsKey(pkg))
        }
    }

    /** 银行 / 购物 / 外卖 的包必须**两条路都通**（应用表 + 模块名单） */
    @Test
    fun banksAndShopsAreWatchedByHookModule() {
        val mustWatch = banks + shops + listOf(
            "com.icbc", "cmb.pb", "com.chinamworld.main", "com.android.bankabc",
            "com.sankuai.meituan", "me.ele", "com.jingdong.app.mall", "com.taobao.taobao"
        )
        mustWatch.forEach { pkg ->
            assertTrue("模块未接管：$pkg", com.jianji.app.hook.HookPackages.ALL.contains(pkg))
            assertTrue("isWatchedFast 判定失败：$pkg", HookProtocol.isWatchedFast(pkg))
            assertTrue("接收器过滤会丢掉：$pkg", HookProtocol.isWatched(pkg))
        }
    }

    /**
     * LSPosed「推荐应用」作用域（传统格式模块读的是 AndroidManifest 里的 `xposedscope`
     * 元数据 → `@array/xposed_scope`）必须与模块接管名单一致。
     *
     * 出过的问题：这个数组以前只有 system + 微信 + 支付宝三项，
     * 于是在 LSPosed 里**看不到任何"推荐应用"**（用户反馈"LSP 里面没有推荐应用"）。
     * 这里直接读源码文件校验，避免再漏。
     */
    @Test
    fun lsposedRecommendedScopeMatchesHookList() {
        val candidates = listOf(
            java.io.File("src/main/res/values/arrays.xml"),
            java.io.File("app/src/main/res/values/arrays.xml")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull("找不到 arrays.xml（测试工作目录：${java.io.File(".").absolutePath}）", file)
        val xml = file!!.readText()
        val block = xml.substringAfter("<string-array name=\"xposed_scope\">")
            .substringBefore("</string-array>")
        val declared = Regex("<item>([^<]+)</item>").findAll(block).map { it.groupValues[1].trim() }.toSet()

        assertTrue("推荐作用域缺少系统框架，Hook 就拦不到通知", declared.contains("system"))
        com.jianji.app.hook.HookPackages.ALL.forEach { pkg ->
            assertTrue("LSPosed 推荐作用域缺少：$pkg", declared.contains(pkg))
        }
        // 反向：作用域里不该有模块不认的包（除了 system）
        declared.filter { it != "system" }.forEach { pkg ->
            assertTrue("推荐作用域多出未登记的包：$pkg", com.jianji.app.hook.HookPackages.ALL.contains(pkg))
        }
    }
}
