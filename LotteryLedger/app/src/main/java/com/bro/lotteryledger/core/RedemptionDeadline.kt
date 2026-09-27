package com.bro.lotteryledger.core

import java.time.LocalDate

/**
 * 兑奖截止日计算（§13）。
 *
 * 基本规则：**开奖之日起 60 个自然日**（双色球与大乐透的官方规则一致）。
 *
 * ## 顺延规则（少爷 2026-09-26 定）
 *
 * - **国庆**：截止日落在假期**头 3 天**（10/1~10/3）内 → 顺延到 10/4
 * - **春节**：截止日落在假期**头 7 天**（正月初一~初七）内 → 顺延到初八
 * - **其它时间段一律不顺延**
 *
 * 官方原文（福彩开奖公告）：
 * 「兑奖截止日在国家法定节假日或彩票市场休市期间等的，兑奖截止日相应顺延」
 * —— 也就是说顺延这件事本身有官方依据，这里只是把范围收窄到少爷常碰到的两个假期。
 *
 * ## 为什么要把春节日期写成表
 *
 * 春节是农历，公历日期每年都不一样，且平年闰年、有无年三十都会影响。
 * 硬编码一张表最可靠（数据来自香港天文台农历对照表，2025~2034 连续五年无年三十）。
 * 表覆盖不到的年份**不顺延**（宁可保守，也不能算错）。
 */
object RedemptionDeadline {

    /** 官方兑奖期：60 个自然日。 */
    const val DAYS = 60L

    /** 春节（农历正月初一）的公历日期。来源：香港天文台农历对照表。 */
    private val SPRING_FESTIVAL = mapOf(
        2025 to LocalDate.of(2025, 1, 29),
        2026 to LocalDate.of(2026, 2, 17),
        2027 to LocalDate.of(2027, 2, 6),
        2028 to LocalDate.of(2028, 1, 26),
        2029 to LocalDate.of(2029, 2, 13),
        2030 to LocalDate.of(2030, 2, 3),
        2031 to LocalDate.of(2031, 1, 23),
        2032 to LocalDate.of(2032, 2, 11),
        2033 to LocalDate.of(2033, 1, 31),
        2034 to LocalDate.of(2034, 2, 19),
        2035 to LocalDate.of(2035, 2, 8)
    )

    /** 国庆顺延窗口长度：头 3 天。 */
    private const val NATIONAL_DAY_DAYS = 3L

    /** 春节顺延窗口长度：头 7 天。 */
    private const val SPRING_FESTIVAL_DAYS = 7L

    /**
     * 计算截止日。
     *
     * @param drawDate yyyy-MM-dd 的开奖日期字符串；**可为 null**（识别的票可能没读到开奖日）
     * @param today    今天（便于单测注入；默认取系统日期）
     * @return 解析不出日期时返回 null，由调用方决定怎么提示
     */
    fun compute(drawDate: String?, today: LocalDate = LocalDate.now()): Info? {
        val d = parse(drawDate) ?: return null

        val raw = d.plusDays(DAYS)
        val extension = extensionFor(raw)

        val finalDeadline = extension?.second ?: raw
        val extendedBy = extension?.let { it.second.toEpochDay() - raw.toEpochDay() } ?: 0L

        return Info(
            drawDate = d,
            rawDeadline = raw,
            deadline = finalDeadline,
            extendedDays = extendedBy,
            extensionName = extension?.first,
            daysLeft = finalDeadline.toEpochDay() - today.toEpochDay()
        )
    }

    /**
     * 判断某天是否落在顺延窗口内，返回 (原因, 顺延到哪天)。
     *
     * 顺延目标是**窗口结束后第一天** —— 例如截止日落在 10/2，则顺延到 10/4。
     */
    private fun extensionFor(date: LocalDate): Pair<String, LocalDate>? {
        val year = date.year

        // 国庆：10/1 起头 3 天
        val ndStart = LocalDate.of(year, 10, 1)
        val ndEnd = ndStart.plusDays(NATIONAL_DAY_DAYS - 1)
        if (!date.isBefore(ndStart) && !date.isAfter(ndEnd)) {
            return "国庆假期" to ndEnd.plusDays(1)
        }

        // 春节：正月初一起头 7 天
        val sfStart = SPRING_FESTIVAL[year] ?: return null
        val sfEnd = sfStart.plusDays(SPRING_FESTIVAL_DAYS - 1)
        if (!date.isBefore(sfStart) && !date.isAfter(sfEnd)) {
            return "春节假期" to sfEnd.plusDays(1)
        }

        return null
    }

    fun parse(s: String?): LocalDate? = try {
        if (s.isNullOrBlank()) null else LocalDate.parse(s.trim())
    } catch (_: Exception) {
        null
    }

    fun format(d: LocalDate): String = "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)

    /** 顺延窗口表（给界面展示"哪段时间会顺延"，也方便自查）。 */
    fun extensionWindows(year: Int): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val ndStart = LocalDate.of(year, 10, 1)
        out += "国庆（头 3 天）" to "${format(ndStart)} ~ ${format(ndStart.plusDays(NATIONAL_DAY_DAYS - 1))}"
        SPRING_FESTIVAL[year]?.let { sf ->
            out += "春节（头 7 天）" to "${format(sf)} ~ ${format(sf.plusDays(SPRING_FESTIVAL_DAYS - 1))}"
        }
        return out
    }

    /**
     * 兑奖截止日信息。
     *
     * [rawDeadline] 是没顺延的 60 天期限，[deadline] 是实际生效的。
     * 两者不同时界面要说明"因 X 假期顺延 N 天"，否则用户按 60 天算会以为算错了。
     */
    data class Info(
        val drawDate: LocalDate,
        val rawDeadline: LocalDate,
        val deadline: LocalDate,
        val extendedDays: Long,
        val extensionName: String?,
        val daysLeft: Long
    ) {
        val extended: Boolean get() = extendedDays > 0
        val expired: Boolean get() = daysLeft < 0

        /** 还剩几天，或已过期几天。 */
        fun remainingText(): String = when {
            daysLeft > 0 -> "还剩 $daysLeft 天"
            daysLeft == 0L -> "今天是最后一天"
            else -> "已过期 ${-daysLeft} 天"
        }
    }
}
