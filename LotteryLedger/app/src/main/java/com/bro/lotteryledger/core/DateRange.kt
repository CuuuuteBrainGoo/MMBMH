package com.bro.lotteryledger.core

import java.time.LocalDate

/**
 * 日期区间筛选（少爷 2026-09-27 要求）。
 *
 * 起点 / 终点都是 **ISO `yyyy-MM-dd` 字符串**，跟 [TicketEntity.drawDate] 同格式。
 * 这样 [TicketFilter.matches] 里可以直接字典序比较，不需要每次解析成日期 ——
 * 列表筛选是每次输入关键词都会跑一遍的热路径。
 *
 * ## 为什么筛的是「开奖日期」而不是「录入时间」
 *
 * 列表本来就按 `draw_date DESC` 排序（见 `TicketDao.all()`）。
 * 如果用录入时间筛，会出现「筛出来一片票，但它们在列表里的顺序跟日期对不上」的割裂感。
 * 而且补录往期老票很常见 —— 录入时间可能是今天，实际开奖日期是四个月前。
 *
 * ## 快捷段 = 直接算出起止日期，而不是另立一套状态
 *
 * 点「近 3 个月」以后，界面上那两个日期框会**真的变成** 2026-06-27 → 2026-09-27。
 * 好处只有一个真相来源：不会出现「选了快捷段，但我又手改了日期，到底听谁的」。
 */
data class DateRange(
    /** null = 不限起点 */
    val from: String? = null,
    /** null = 不限终点 */
    val to: String? = null
) {
    val active: Boolean get() = from != null || to != null

    /** 区间内？任一端为 null 表示那一端不限。 */
    fun contains(drawDate: String?): Boolean {
        val d = drawDate?.trim().orEmpty()
        // 日期解析不出来的票（理论上不该有，但库里可能有脏数据）：
        // **不把它筛掉**，宁可多显示也不让用户以为票丢了。跟别的筛选条件一致。
        if (d.isEmpty()) return true
        if (from != null && d < from) return false
        if (to != null && d > to) return false
        return true
    }

    /** 界面上那行小字：`2026-06-27 → 2026-09-27` / `2026-01-01 起` / `截止 2026-06-30`。 */
    fun display(): String = when {
        from != null && to != null -> "$from → $to"
        from != null -> "$from 起"
        to != null -> "截止 $to"
        else -> "不限"
    }

    companion object {
        val NONE = DateRange()
    }
}

/**
 * 现成的日期段。点一下就填进 [DateRange]。
 *
 * 边界口径（写死在 [resolve] 里，单测钉住）：
 * - [LAST_MONTH]  上一个**完整自然月**。9 月 27 日点它就是 8/1 ~ 8/31。
 * - [RECENT_3M]   今天往前推 3 个月（滚动窗口，不是整月对齐）。
 * - [H1] / [H2]   今年上半年 = 1/1~6/30；下半年 = 7/1~12/31。
 * - [THIS_YEAR]   今年全年 1/1 ~ 12/31。
 *
 * ⚠️ 「下半年」如果今天是 9 月，它的终点 12/31 **还没到** —— 这是**故意**的。
 * 用户点「今年下半年」是想看下半年的票，12 月的票将来录进来就该落在这个区间里。
 * 如果把终点截到「今天」，那今天录一张 12 月开奖的票（票面就是 12 月开奖），
 * 反而筛不出来，用户会觉得坏了。
 *
 * ⚠️ 「近 3 个月」和「今年上半年」可能指向**同一天**（比如 2026-09-27 点前者是
 * 6/27，后者终点是 6/30）—— 这是两个不同的问题，不许去重。
 */
enum class DateQuickPick(val display: String) {
    LAST_MONTH("上个月"),
    RECENT_3M("近 3 个月"),
    H1("今年上半年"),
    H2("今年下半年"),
    THIS_YEAR("今年");

    /**
     * 算出对应的日期区间。
     *
     * @param today 注入是为了能写单测 —— 直接读 `LocalDate.now()` 的函数测不了跨年边界。
     */
    fun resolve(today: LocalDate = LocalDate.now()): DateRange = when (this) {
        LAST_MONTH -> {
            // 上一个完整自然月：本月 1 号往前一天 = 上月末，再把日截到 1 号 = 上月首
            val lastDay = today.withDayOfMonth(1).minusDays(1)
            DateRange(from = lastDay.withDayOfMonth(1).toString(), to = lastDay.toString())
        }
        RECENT_3M -> DateRange(from = today.minusMonths(3).toString(), to = today.toString())
        H1 -> DateRange(from = LocalDate.of(today.year, 1, 1).toString(),
                        to = LocalDate.of(today.year, 6, 30).toString())
        H2 -> DateRange(from = LocalDate.of(today.year, 7, 1).toString(),
                        to = LocalDate.of(today.year, 12, 31).toString())
        THIS_YEAR -> DateRange(from = LocalDate.of(today.year, 1, 1).toString(),
                               to = LocalDate.of(today.year, 12, 31).toString())
    }
}
