package com.jianji.app.core

/**
 * 记账决策（纯逻辑，可单测）。
 *
 * 这里写死了两条曾经踩过坑、必须保持的约定：
 *
 * 1. **「与近期记录雷同」只是提示信息，永远不能阻止记账。**
 *    同一人、同金额的多笔（收款 + 付款 + 红包）本来就是多笔真实交易；
 *    曾因为在通知通路上写成"similar 就只弹提示、不入库"，导致
 *    同金额的第一笔之后**全部静默不记**（免密支付、微信/支付宝通知全走那条路）。
 * 2. **默认（未开启「记账前确认」）直接记账**，并给一条带「撤销」的通知；
 *    免密支付 / 自动扣款属于"钱已经扣完"，即使开启了确认也应直接记账。
 */
object RecordPolicy {

    /** 直接入库（另发一条可撤销通知） */
    const val SAVE_DIRECT = 0

    /** 先弹「保存 / 修改」等用户确认 */
    const val ASK_CONFIRM = 1

    /**
     * @param confirmBeforeSave 用户是否开启了「记账前确认」
     * @param autoSaveMianmi    是否开启「免密支付自动记账」
     * @param isMianmi          本条消息是否为免密/自动扣款/代扣/续费
     */
    fun decide(confirmBeforeSave: Boolean, autoSaveMianmi: Boolean, isMianmi: Boolean): Int =
        if (!confirmBeforeSave || (autoSaveMianmi && isMianmi)) SAVE_DIRECT else ASK_CONFIRM

    /** 雷同只影响提示文案，不影响是否记账（保留此函数以固化这条约定） */
    fun saveRegardlessOfSimilar(): Boolean = true
}
