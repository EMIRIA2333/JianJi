package com.jianji.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeUtil {
    private val ZONE: ZoneId = ZoneId.systemDefault()
    private val LIST_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val FULL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val MINUTE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val SLASH_DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd")
    private val DAY_HEADER_FMT = DateTimeFormatter.ofPattern("MM.dd")
    private val WEEK_FMT = DateTimeFormatter.ofPattern("EEEE")

    fun formatList(time: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZONE).format(LIST_FMT)

    fun formatFull(time: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZONE).format(FULL_FMT)

    /** 列表日期分组标题，如 10.01 */
    fun formatDayHeader(time: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZONE).format(DAY_HEADER_FMT)

    /** 星期几（中文环境返回 星期三） */
    fun formatWeekday(time: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZONE).format(WEEK_FMT)

    fun parseFull(s: String): Long? = runCatching {
        LocalDateTime.parse(s, FULL_FMT).atZone(ZONE).toInstant().toEpochMilli()
    }.getOrNull()

    fun parseMinute(s: String): Long? = runCatching {
        LocalDateTime.parse(s, MINUTE_FMT).atZone(ZONE).toInstant().toEpochMilli()
    }.getOrNull()

    fun parseDateOnly(s: String): Long? {
        runCatching {
            LocalDate.parse(s, DATE_FMT).atStartOfDay(ZONE).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
        return runCatching {
            LocalDate.parse(s, SLASH_DATE_FMT).atStartOfDay(ZONE).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /**
     * 宽松解析：支持
     * `2026年10月1日 18:04:53`、`2026年10月1日 18:04`、`2026-10-01 18:04:53`、
     * `2026-10-01 18:04`、`2026/10/01`、`2026-10-01`
     */
    fun parseAny(raw: String): Long? {
        val s = raw.trim().replace('\u00A0', ' ')
        if (s.isEmpty()) return null
        CN_FORMATS.forEach { f ->
            runCatching { LocalDateTime.parse(s, f).atZone(ZONE).toInstant().toEpochMilli() }
                .getOrNull()?.let { return it }
        }
        parseFull(s)?.let { return it }
        parseMinute(s)?.let { return it }
        parseDateOnly(s)?.let { return it }
        return null
    }

    /** 某天的 0 点（本地时区） */
    fun dayStart(time: Long): Long =
        Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant().toEpochMilli()

    /** 所在自然月第一天 0 点 */
    fun monthStart(time: Long): Long =
        Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate()
            .withDayOfMonth(1).atStartOfDay(ZONE).toInstant().toEpochMilli()

    /** 下一个自然月第一天 0 点 */
    fun nextMonthStart(time: Long): Long =
        Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate()
            .withDayOfMonth(1).plusMonths(1).atStartOfDay(ZONE).toInstant().toEpochMilli()

    /** 今年第一天 0 点 */
    fun yearStart(time: Long): Long =
        Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate()
            .withDayOfYear(1).atStartOfDay(ZONE).toInstant().toEpochMilli()

    /** 明年第一天 0 点 */
    fun nextYearStart(time: Long): Long =
        Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate()
            .withDayOfYear(1).plusYears(1).atStartOfDay(ZONE).toInstant().toEpochMilli()

    /** 月份平移（delta 可正可负），返回目标月 0 点 */
    fun shiftMonth(time: Long, delta: Int): Long =
        Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate()
            .withDayOfMonth(1).plusMonths(delta.toLong())
            .atStartOfDay(ZONE).toInstant().toEpochMilli()

    /** 月份标题，如 2026-10 */
    fun formatMonthTitle(time: Long): String =
        Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate()
            .format(DateTimeFormatter.ofPattern("yyyy-MM"))

    /** 中文星期短标签，如 周四 */
    fun weekdayShort(time: Long): String {
        val dow = Instant.ofEpochMilli(time).atZone(ZONE).toLocalDate().dayOfWeek.value
        return WEEKDAYS[(dow - 1).coerceIn(0, 6)]
    }

    /** 日期分组标签：今天 / 昨天 / 周X */
    fun formatWeekdayLabel(dayStart: Long): String {
        val today = dayStart(System.currentTimeMillis())
        return when (dayStart) {
            today -> "今天"
            today - DAY_MS -> "昨天"
            else -> weekdayShort(dayStart)
        }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** 中文日期时间（微信/支付宝账单页常见） */
    private val CN_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"),
        DateTimeFormatter.ofPattern("yyyy年M月d日"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
    )

    private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
}
