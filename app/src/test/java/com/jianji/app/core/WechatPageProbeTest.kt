package com.jianji.app.core

import org.junit.Test
import java.io.File

/** 探针：用用户三张微信截图里的原文，看 ReceiptParser 为什么拒 */
class WechatPageProbeTest {

    @Test
    fun probe() {
        val out = StringBuilder()
        fun probe(name: String, pkg: String, text: String) {
            val p = ReceiptParser.parse(pkg, text)
            out.appendLine("== $name ==")
            out.appendLine("  parse => " + (p?.let { "type=${it.type} amount=${it.amount} merchant=${it.merchant} pay=${it.payMethod}" } ?: "null"))
            out.appendLine("  reason => " + ReceiptParser.lastRejectReason)
        }

        // ① 转账详情页（+9.10 已存入零钱）
        probe("转账详情页", "com.tencent.mm", """
            X
            转账-来自李四
            +9.10
            当前状态 已存入零钱
            转账说明 微信转账
            转账时间 2026年11月7日 20:15:44
            收款时间 2026年11月7日 20:15:50
            转账单号 1000050001202611070330993546368
            账单服务
            定位到聊天位置
            申请转账电子凭证
            查看往来交易
        """.trimIndent())

        // ② 零钱明细（同上 + 零钱余额）
        probe("零钱明细", "com.tencent.mm", """
            X
            零钱明细
            转账-来自李四
            +9.10
            当前状态 已存入零钱
            转账说明 微信转账
            转账时间 2026年11月07日 20:15:44
            收款时间 2026年11月07日 20:15:50
            转账单号 1000050001202611070330993546368
            零钱余额 53.70
            账单服务
        """.trimIndent())

        // ③ 微信支付账单详情（-4.50 支付成功）
        probe("微信支付账单详情", "com.tencent.mm", """
            X
            全部账单
            示例便利店
            -4.50
            当前状态 支付成功
            支付时间 2026年11月7日 17:33:13
            商品 示例便利店学院店 共1件商品
            商户全称 示例商贸有限公司(个体工商户)
            收单机构 通联支付网络服务股份有限公司
            支付方式 零钱
            交易单号 4200003230202611077895885742
            商户单号 可在支持的商户扫码退款
            261107123596426793
            账单服务
        """.trimIndent())

        File("build-alt/probe-wechat.txt").apply { parentFile?.mkdirs() }.writeText(out.toString())
        println(out)
    }
}
