package com.jianji.app.ui

import com.jianji.app.R

/**
 * 分类 -> 图标资源映射（UI 层专属，保持 core 包无 Android 资源依赖、可纯 JVM 测试）。
 */
object CategoryIcons {
    fun iconRes(category: String): Int = when (category) {
        // 支出
        "餐饮" -> R.drawable.ic_cat_food
        "交通" -> R.drawable.ic_cat_traffic
        "购物" -> R.drawable.ic_cat_shop
        "服饰" -> R.drawable.ic_cat_clothes
        "日用" -> R.drawable.ic_cat_daily
        "娱乐" -> R.drawable.ic_cat_fun
        "医疗" -> R.drawable.ic_cat_medical
        "居住" -> R.drawable.ic_cat_home
        "通讯" -> R.drawable.ic_cat_phone
        "教育" -> R.drawable.ic_cat_book
        "旅行" -> R.drawable.ic_cat_plane
        "宠物" -> R.drawable.ic_cat_pet
        "运动" -> R.drawable.ic_cat_sport
        "数码" -> R.drawable.ic_cat_device
        "母婴" -> R.drawable.ic_cat_baby
        "汽车" -> R.drawable.ic_cat_traffic
        "人情" -> R.drawable.ic_cat_gift
        "快递" -> R.drawable.ic_cat_box
        "美容" -> R.drawable.ic_cat_daily
        "保险" -> R.drawable.ic_shield
        "税费" -> R.drawable.ic_cat_doc
        "办公" -> R.drawable.ic_cat_salary
        // 收入
        "工资" -> R.drawable.ic_cat_salary
        "奖金" -> R.drawable.ic_cat_salary
        "兼职" -> R.drawable.ic_cat_salary
        "收款" -> R.drawable.ic_cat_income
        "红包" -> R.drawable.ic_cat_redpacket
        "退款" -> R.drawable.ic_cat_refund
        "报销" -> R.drawable.ic_cat_refund
        "理财" -> R.drawable.ic_nav_chart
        "利息" -> R.drawable.ic_cat_income
        "分红" -> R.drawable.ic_cat_income
        "礼金" -> R.drawable.ic_cat_redpacket
        // 其他
        "短信" -> R.drawable.ic_cat_sms
        else -> R.drawable.ic_cat_other
    }
}
