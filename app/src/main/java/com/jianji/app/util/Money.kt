package com.jianji.app.util

import android.content.Context
import com.jianji.app.core.RecordType
import java.util.Locale

/**
 * 金额格式化（含隐私模式：开启后金额显示为 ****）。
 */
object Money {

    const val MASK = "✱✱✱✱"

    fun isMasked(c: Context): Boolean = Prefs.isPrivacyMask(c)

    /** 纯数字，如 1234.50 */
    fun plain(amount: Double): String = String.format(Locale.US, "%.2f", amount)

    /** 带符号，如 -1234.50 / +88.00 */
    fun signed(type: Int, amount: Double): String =
        (if (type == RecordType.INCOME) "+" else "-") + plain(amount)

    /** 带 ¥ 前缀，隐私模式返回 **** */
    fun yuan(c: Context, amount: Double): String =
        if (isMasked(c)) MASK else "¥" + plain(amount)

    /** 带符号与 ¥，隐私模式返回 **** */
    fun signedYuan(c: Context, type: Int, amount: Double): String =
        if (isMasked(c)) MASK else (if (type == RecordType.INCOME) "+¥" else "-¥") + plain(amount)

    /** 结算/结余等可为负的金额 */
    fun balanceYuan(c: Context, amount: Double): String {
        if (isMasked(c)) return MASK
        val sign = if (amount < 0) "-" else ""
        return sign + "¥" + plain(kotlin.math.abs(amount))
    }
}
