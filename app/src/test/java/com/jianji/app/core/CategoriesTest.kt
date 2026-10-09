package com.jianji.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分类扩展与识别准确度测试 */
class CategoriesTest {

    @Test
    fun allCategoriesHaveColorAndAreNonEmpty() {
        Categories.EXPENSE_LIST.forEach { assertTrue("缺少颜色: $it", Categories.BG_COLORS.containsKey(it)) }
        Categories.INCOME_LIST.forEach { assertTrue("缺少颜色: $it", Categories.BG_COLORS.containsKey(it)) }
        assertTrue(Categories.EXPENSE_LIST.size >= 20)
        assertTrue(Categories.INCOME_LIST.size >= 10)
    }

    @Test
    fun expenseClassification() {
        assertEquals("餐饮", Categories.guess("示例小吃", false))
        assertEquals("餐饮", Categories.guess("瑞幸咖啡（国贸店）", false))
        assertEquals("交通", Categories.guess("中国石化加油站", false))
        assertEquals("交通", Categories.guess("滴滴出行", false))
        assertEquals("汽车", Categories.guess("某某4S店保养", false))
        assertEquals("快递", Categories.guess("顺丰速运", false))
        assertEquals("服饰", Categories.guess("优衣库", false))
        assertEquals("数码", Categories.guess("小米之家", false))
        assertEquals("母婴", Categories.guess("某某母婴店", false))
        assertEquals("宠物", Categories.guess("宠物医院", false))
        assertEquals("教育", Categories.guess("某某培训学校", false))
        assertEquals("旅行", Categories.guess("携程旅行网", false))
        assertEquals("医疗", Categories.guess("人民医院", false))
        assertEquals("居住", Categories.guess("万科物业", false))
        assertEquals("通讯", Categories.guess("中国移动话费", false))
        assertEquals("娱乐", Categories.guess("万达影城", false))
        assertEquals("美容", Categories.guess("某某理发店", false))
        assertEquals("税费", Categories.guess("社保缴费", false))
        assertEquals("保险", Categories.guess("平安保险", false))
        assertEquals("人情", Categories.guess("某某婚礼份子", false))
        assertEquals("购物", Categories.guess("永辉超市", false))
    }

    @Test
    fun incomeClassification() {
        assertEquals("工资", Categories.guess("某某公司", true, "代发工资 8000 元"))
        assertEquals("奖金", Categories.guess("", true, "年终奖金到账"))
        assertEquals("红包", Categories.guess("", true, "你收到了一个红包"))
        assertEquals("退款", Categories.guess("", true, "退款成功 39.90"))
        assertEquals("报销", Categories.guess("", true, "差旅报销到账"))
        assertEquals("理财", Categories.guess("", true, "基金收益入账"))
        assertEquals("利息", Categories.guess("", true, "存款利息 12.30"))
        assertEquals("分红", Categories.guess("", true, "股票分红派息"))
        assertEquals("礼金", Categories.guess("", true, "收到礼金 600"))
        assertEquals("兼职", Categories.guess("", true, "稿费到账"))
        assertEquals("收款", Categories.guess("", true, "收到转账 100 元"))
    }

    @Test
    fun platformFallback() {
        assertEquals("餐饮", Categories.guess("某某店", false, "", "com.sankuai.meituan"))
        assertEquals("旅行", Categories.guess("某某店", false, "", "com.ctrip.android.view"))
        assertEquals("购物", Categories.guess("某某店", false, "", "com.jingdong.app.mall"))
        assertEquals("其他", Categories.guess("某某工作室", false, "", "com.unknown.app"))
    }

    @Test
    fun specificBeatsGeneric() {
        // “猫眼电影”不能被宠物分类的“猫”抢走
        assertEquals("娱乐", Categories.guess("猫眼电影", false))
        // “书亦烧仙草”属于餐饮而不是教育
        assertEquals("餐饮", Categories.guess("书亦烧仙草", false))
        // 加油站属于交通而不是汽车
        assertEquals("交通", Categories.guess("中国石油加油站", false))
    }
}
