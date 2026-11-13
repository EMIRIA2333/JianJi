package com.jianji.app.core

import org.junit.Test
import java.io.File

/** 探针：用真机 OCR 原文（微信转账详情）看 ReceiptParser 为什么拒 */
class WechatTransferProbeTest {

    private val ocr = "X 19:59 当前状态 转账说明 转账时间 收款时间 支付方式 转账单号 账单服务 " +
        "转账-转检阮祝後 对方己收钱 -14.40 微信转账 2026年/1 月12日22:27:2/ 零钱 B46站办"

    @Test
    fun probe() {
        val sb = StringBuilder()
        val p = ReceiptParser.parse("com.tencent.mm", ocr)
        sb.appendLine("parse => " + (p?.let {
            "type=${it.type} amount=${it.amount} merchant=${it.merchant} pay=${it.payMethod}"
        } ?: "null"))
        sb.appendLine("reason => ${ReceiptParser.lastRejectReason}")
        sb.appendLine("transferDirection => ${ReceiptParser.transferDirection(ocr)}")
        sb.appendLine("extractAmount => ${ReceiptParser.extractAmount(ocr)}")
        sb.appendLine("extractMerchant => ${ReceiptParser.extractMerchant(ocr)}")
        File("build-alt/probe-wechat-transfer.txt").apply { parentFile?.mkdirs() }.writeText(sb.toString())
        println(sb)
    }
}
