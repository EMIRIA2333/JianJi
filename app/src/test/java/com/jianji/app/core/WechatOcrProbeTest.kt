package com.jianji.app.core

import org.junit.Test
import java.io.File

/** 探针：用真机 OCR 出来的微信转账详情页文本，看金额被解析成什么 */
class WechatOcrProbeTest {

    @Test
    fun probeWechatTransferOcr() {
        val ocr = """
            9:29令 X 当前状态 转账说明 转账时间 收款时间 转账单号 |账单服务
            转账-来自张三 已存入零钱 +9.10 微信转账 2026年11月7日 20:52:28
            2026年11月7日 20:52:39 100005000120261107070635515823612
            定位到聊天位置 申请转账电子凭证 查看往来交易
            本服务由财付通提供
        """.trimIndent()

        val sb = StringBuilder()
        val p = ReceiptParser.parse("com.tencent.mm", ocr)
        sb.appendLine("ReceiptParser => " + (p?.let {
            "type=${it.type} amount=${it.amount} merchant=${it.merchant} pay=${it.payMethod} reason=${ReceiptParser.lastRejectReason}"
        } ?: "null（${ReceiptParser.lastRejectReason}）"))

        val pay = PaymentParser.parse("com.tencent.mm", "", ocr, strict = false)
        sb.appendLine("PaymentParser => " + (pay?.let { "type=${it.type} amount=${it.amount} merchant=${it.merchant}" } ?: "null"))

        // 单独看金额提取
        sb.appendLine("extractAmount => " + ReceiptParser.extractAmount(ocr))

        File("build-alt/probe-ocr-amount.txt").apply { parentFile?.mkdirs() }.writeText(sb.toString())
        println(sb)
    }
}
