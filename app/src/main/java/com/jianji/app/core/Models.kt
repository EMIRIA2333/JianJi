package com.jianji.app.core

/** 账单类型 */
object RecordType {
    const val EXPENSE = 0
    const val INCOME = 1
}

/** 账单来源 */
object RecordSource {
    const val MANUAL = 0
    const val AUTO_WECHAT = 1
    const val AUTO_ALIPAY = 2
    const val AUTO_SCREEN = 3
    const val AUTO_SMS = 4
}

/**
 * 一条账单记录。amount 恒为正数，方向由 type 决定。
 * merchant = 商户名称（识别时自动填，也可手动填）
 * note     = 备注（自己写的话；识别时把「商品说明」放进 tag）
 * payMethod = 支付方式 / 支出工具（零钱、零钱通、花呗、储蓄卡、信用卡、余额宝、云闪付…）
 * imagePath = 账单页面截图（「账单图片」开关开启时自动保存，相对 filesDir 的路径）
 * deletedAt > 0 表示已移入回收站（软删除），可还原或彻底删除。
 */
data class Record(
    val id: Long = 0L,
    val amount: Double,
    val type: Int,
    val category: String,
    val note: String,
    val tag: String = "",
    val payMethod: String = "",
    val source: Int,
    val time: Long,
    val createTime: Long = time,
    val deletedAt: Long = 0L,
    val imagePath: String = "",
    val merchant: String = ""
) {
    /** 界面显示用名称：优先商户名称，老数据回退到备注 */
    val displayName: String get() = merchant.ifBlank { note }
}
