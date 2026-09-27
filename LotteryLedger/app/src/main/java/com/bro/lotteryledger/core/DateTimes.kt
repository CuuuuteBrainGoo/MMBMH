package com.bro.lotteryledger.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 日期时间规范化。纯函数，可单测。
 *
 * 只处理交接稿里出现过的格式；遇到不认识的一律返回 null，
 * **不做模糊猜测** —— 时间是身份指纹的一部分，猜错会误判重复。
 */
object DateTimes {

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyy.MM.dd"),
        DateTimeFormatter.ofPattern("yyyyMMdd")
    )

    private val DATETIME_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
    )

    /** 解析日期为 ISO `yyyy-MM-dd`，失败返回 null。 */
    fun normalizeDate(s: String?): String? {
        val v = s?.trim() ?: return null
        if (v.isEmpty()) return null
        for (f in DATE_FORMATS) {
            try {
                return LocalDate.parse(v, f).toString()
            } catch (_: DateTimeParseException) { /* try next */ }
        }
        return null
    }

    /** 解析日期时间为 ISO `yyyy-MM-dd HH:mm:ss`，失败返回 null。 */
    fun normalize(s: String?): String? {
        val raw = s?.trim() ?: return null
        if (raw.isEmpty()) return null

        for (f in DATETIME_FORMATS) {
            try {
                return LocalDateTime.parse(raw, f).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            } catch (_: DateTimeParseException) { /* try next */ }
        }

        // 只有日期的输入，补 00:00:00
        normalizeDate(raw)?.let { return "$it 00:00:00" }
        return null
    }

    fun isValidDate(s: String?): Boolean = normalizeDate(s) != null

    fun isFutureDate(iso: String?): Boolean {
        val d = normalizeDate(iso) ?: return false
        return LocalDate.parse(d).isAfter(LocalDate.now())
    }

    /**
     * 兑奖截止日（§14 需要）。
     * 交接稿 §9.3 提到「已超过兑奖期限」，§12 提到大乐透 60 天兑奖期。
     * 中国彩票统一规则为开奖之日起 60 个自然日。
     *
     * ⚠️ 节假日顺延规则（§24 要求核验）本阶段未实现 —— 这里按 60 天朴素计算。
     * ponytail: 节假日顺延未实现，官方细则核实后在此处补正。当前误差只影响提醒时机，不影响账目。
     */
    fun claimDeadline(drawDate: String?): String? {
        val d = normalizeDate(drawDate) ?: return null
        return LocalDate.parse(d).plusDays(60).toString()
    }
}
