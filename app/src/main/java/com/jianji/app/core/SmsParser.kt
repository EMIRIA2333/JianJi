package com.jianji.app.core

import java.util.regex.Pattern

/**
 * 银行短信支付解析器（纯 Kotlin，无 Android 依赖）。
 *
 * 覆盖各大银行借记卡/信用卡短信的常见格式：
 *  - 【招商银行】您尾号1234的账户10月1日消费人民币26.00元（美团）
 *  - 【工商银行】您尾号8888卡10月1日12:00跨行转出交易1000.00元
 *  - 您的账户*1234 10月1日入账工资5,600.00元
 *  - 【建设银行】退款50.00元已退回尾号1234储蓄卡
 */
object SmsParser {

    /** 短信硬忽略：验证码/营销/催还/非交易 */
    private val DENY_PATTERNS = listOf(
        "验证码", "校验码", "动态码", "动态密码", "网银登录", "登录", "激活",
        "积分", "礼品", "抽奖", "活动", "优惠", "专属", "额度", "贷款", "分期",
        "最低还款", "还款日", "应还款", "剩余应还", "账单日", "逾期", "催",
        "电子回单", "对账单", "止付", "挂失", "银行卡失效", "解绑",
        // 「冻结」不能单独当拦截词：境外/预授权消费短信会写
        // 「金额1.38新加坡元，**已冻结**7.36人民币」——一拦就整条丢掉（真实 bug）。
        // 只拦"账户/卡/资金被冻结"这种真正的异常状态。
        "(?:账户|卡片|该卡|资金|存款)[^，。;；]{0,8}(?:被)?冻结"
    ).map { Pattern.compile(it) }

    /**
     * 冲正 / 解冻 / 撤销：这是**反向撤销**，不是新的一笔。
     * 例：「…发生双信息无卡消费**冲正**交易，金额1.38新加坡元，已解冻7.36人民币」
     * 以前会被记成一笔支出（还取错了金额），现在直接忽略。
     */
    private val REVERSAL_PATTERNS = listOf(
        "冲正", "解冻", "交易撤销", "撤销交易", "撤单", "已冲正"
    ).map { Pattern.compile(it) }

    /** 必须具备的银行语境：降低把普通短信当交易的风险 */
    private val BANK_CONTEXT_PATTERNS = listOf(
        "尾号", "储蓄卡", "借记卡", "信用卡", "账户", "银行卡", "活期", "电子现金"
    ).map { Pattern.compile(it) }

    private val INCOME_PATTERNS = listOf(
        "收入", "入账", "到账", "工资", "代发", "贷记", "收款", "退款", "退回", "转入", "现金存入", "存入"
    ).map { Pattern.compile(it) }

    private val EXPENSE_PATTERNS = listOf(
        "支出", "消费", "借记", "划扣", "代扣", "扣款", "支付", "付款", "转出", "快捷", "还款成功", "还款"
    ).map { Pattern.compile(it) }


    /**
     * **人民币金额优先**：数字 + 人民币/RMB/CNY、人民币/RMB/CNY + 数字、数字 + 元。
     *
     * 为什么单列一组：境外消费短信会同时出现两个数字
     * 「金额1.38新加坡元，已冻结7.36人民币」——记账要用 **7.36**（人民币），
     * 旧实现先匹配「金额1.38」→ 记成 1.38（真实 bug）。
     */
    private val CNY_AMOUNT_PATTERNS = listOf(
        Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:人民币|RMB|CNY)"),
        Pattern.compile("(?:人民币|RMB|CNY)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元?"),
        Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元")
    )

    /** 外币金额（人民币金额不存在时才用）：数字 + 币种，或币种缩写 + 数字 */
    private val FX_AMOUNT_PATTERNS = listOf(
        Pattern.compile(
            "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*" +
                "(?:新加坡元|新元|美元|日元|欧元|港币|港元|英镑|澳元|加元|韩元|泰铢|新台币|迪拉姆|卢布|法郎" +
                "|SGD|USD|EUR|JPY|HKD|GBP|AUD|CAD|KRW|THB|SGD|USD)"
        ),
        Pattern.compile("(?:SGD|USD|EUR|JPY|HKD|GBP|AUD|CAD|KRW|THB)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"),
        Pattern.compile("金额[:：]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    )

    /** 商户/摘要：在（美团）消费 / 收款入账（微信提现） / 消费-美团 / 支付宝消费 / 在美团消费 */
    private val MERCHANT_PATTERNS = listOf(
        Pattern.compile("[（(【\\[]([^）)】\\]]{2,20})[）)】\\]](?:消费|支出|支付|付款|收款|入账|扣款|转账)"),
        Pattern.compile("(?:消费|支出|收款|入账|扣款|支付|转账|还款)[（(【\\[]([^）)】\\]]{1,20})[）)】\\]]"),
        Pattern.compile("(?:消费|支出|收款|入账|扣款|支付)[-－:：]\\s*([^,，，。;；\\s()（）]{2,20})"),
        Pattern.compile("在([^,，。，。\\s()（）]{2,20}?)(?:消费|支付|付款)"),
        Pattern.compile("向([^,，。，。\\s()（）]{2,20}?)(?:支付|付款|转账)"),
        Pattern.compile("(支付宝|微信|云闪付)消费")
    )

    /** 提取银行名：【招商银行】/ 招商银行 */
    private val BANK_PATTERN = Pattern.compile("[【\\[]?([\\u4e00-\\u9fa5]{2,10}银行|\\u94f6\\u8054)[】\\]]?")

    data class Parsed(
        val type: Int,
        val amount: Double,
        val merchant: String,
        val bank: String,
        val confidence: Double
    )

    fun parse(sender: String, body: String): Parsed? {
        val text = body.replace('\u00A0', ' ').trim()
        if (text.length < 8 || text.length > 500) return null
        // 冲正/撤销/解冻：这是反向操作，不是新交易（记了就会多一笔、还取错金额）
        if (REVERSAL_PATTERNS.any { it.matcher(text).find() }) return null
        if (DENY_PATTERNS.any { it.matcher(text).find() }) return null

        // 方向判定
        val income = INCOME_PATTERNS.any { it.matcher(text).find() }
        val expense = EXPENSE_PATTERNS.any { it.matcher(text).find() }
        if (income == expense) return null

        // 金额：**人民币优先**，没有再退回外币/「金额X」。
        // 例：「金额1.38新加坡元，已冻结7.36人民币」→ 必须取 7.36
        var amount: Double? = null
        for (p in CNY_AMOUNT_PATTERNS) {
            val m = p.matcher(text)
            if (m.find()) {
                amount = m.group(1).toAmountOrNull()
                if (amount != null) break
            }
        }
        if (amount == null) {
            for (p in FX_AMOUNT_PATTERNS) {
                val m = p.matcher(text)
                if (m.find()) {
                    amount = m.group(1).toAmountOrNull()
                    if (amount != null) break
                }
            }
        }
        if (amount == null) return null

        // 银行语境（来自号码或正文），防止把普通通知当交易。
        //
        // 只有 **95xxx 银行短号** 才算"号码即证据"；106 通道（营销短信也大量使用）与手机号
        // **必须**在正文里出现银行语境（尾号/储蓄卡/信用卡/银行名）。
        // —— 曾经把 106 也当证据，结果 106 发的促销短信被判成银行交易（单测抓到的回归）。
        val fromBankNumber = sender.matches(Regex("95[0-9]{3}"))
        val hasBankContext = BANK_CONTEXT_PATTERNS.any { it.matcher(text).find() } ||
            BANK_PATTERN.matcher(text).find() || fromBankNumber
        if (!hasBankContext) return null

        // 收入优先匹配工资等；若同时命中收支（如"退回消费款"），以更靠前的方向词为准
        val type = if (income) RecordType.INCOME else RecordType.EXPENSE

        var merchant = extractMerchant(text) ?: ""
        if (merchant.length < 2) merchant = ""
        val bank = extractBank(text) ?: (if (fromBankNumber) "银行" else "")

        // 收入类：退款归"退款"，其余归"收款"（工资/代发在 guess 里处理）
        return Parsed(type, amount, merchant, bank, 1.0)
    }

    /** 金额字符串 → Double（去逗号、四舍五入到分、范围校验） */
    private fun String.toAmountOrNull(): Double? {
        val v = replace(",", "").toDoubleOrNull() ?: return null
        if (v <= 0.0 || v > PaymentParser.MAX_AMOUNT) return null
        return Math.round(v * 100.0) / 100.0
    }
    fun extractMerchant(text: String): String? {
        for (p in MERCHANT_PATTERNS) {
            val m = p.matcher(text)
            if (m.find()) {
                val g = (m.group(1) ?: "").trim()
                if (g.length >= 2 && !g.contains("银行") && !g.contains("尾号")) return g
            }
        }
        return null
    }

    fun extractBank(text: String): String? {
        val m = BANK_PATTERN.matcher(text)
        if (m.find()) return m.group(1)
        return null
    }
}
