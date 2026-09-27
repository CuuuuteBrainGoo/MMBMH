package com.bro.lotteryledger.core

import com.bro.lotteryledger.db.TicketEntity

/**
 * 票列表的筛选条件（§15 列表视图）。
 *
 * 纯数据 + 纯函数，能跑 JVM 单测 —— 筛选错了会让人「以为某张票不见了」，
 * 这个后果比看起来严重，必须有测试兜着。
 *
 * ## 统计要不要跟着筛选变（2026-09-27 口径改了）
 *
 * 原口径：hero 卡的「净收支」始终是**全部票**的总账，筛选只影响下面列表。
 * 理由是这样「净收支」四个字永远代表同一个东西。
 *
 * **少爷 2026-09-27 要求改成：筛选时主账要跟着变，让用户直观看到金额变化。**
 *
 * 折中方案（做法 B，见 [TicketStats.inRange]）：主账**主数字仍然是总账**，
 * 但在筛选生效时，主账卡下方多出一行「当前筛选：… 花 ¥X · 中 ¥Y · 净 ¥Z」。
 * 这样两个诉求都满足 —— 既看得到筛选后的金额，主数字也还有个不变的基准。
 *
 * 如果哪天要改成「主数字整个换成筛选结果」，改 `HeroCard` 一处即可，
 * 数据侧 [TicketStats] 已经把「全部」和「筛选后」两组数都算好了。
 */
data class TicketFilter(
    /** null = 全部彩种 */
    val type: LotteryType? = null,
    val status: StatusFilter = StatusFilter.ALL,
    /**
     * 关键词。匹配期号与票面编号。
     *
     * 为什么连票面编号一起搜：重复入账排查时，手上拿着一张票想知道「录过没」，
     * 最直接的就是把票上的验票码/票号查一遍。
     */
    val keyword: String = "",
    /**
     * 按**开奖日期**筛（少爷 2026-09-27）。[DateRange.NONE] = 不限。
     *
     * 每个维度的筛选值都保持自洽，所以这个类永远是「完整的一组条件」，
     * 不会出现「一半新一半旧」的中间态。
     */
    val dateRange: DateRange = DateRange.NONE
) {

    /** 是否有任何条件生效。界面靠它决定要不要显示「已筛选」提示。 */
    val active: Boolean
        get() = type != null || status != StatusFilter.ALL ||
            keyword.isNotBlank() || dateRange.active

    /**
     * 生效条件的数量。
     *
     * ⚠️ 这个值**曾经**用来给筛选按钮画角标（少爷 2026-09-27 要求删掉那个角标）。
     * 现在不用在按钮上了，但 `summary()` 的分支判断和单测还在用它 —— 别删。
     */
    val activeCount: Int
        get() = listOf(type != null, status != StatusFilter.ALL,
            keyword.isNotBlank(), dateRange.active).count { it }

    fun matches(t: TicketEntity): Boolean {
        if (type != null && !t.lotteryType.equals(type.code, ignoreCase = true)) return false
        if (!status.accepts(TicketStatus.from(t.ticketStatus))) return false
        if (!dateRange.contains(t.drawDate)) return false
        if (keyword.isNotBlank() && !matchesKeyword(t)) return false
        return true
    }

    private fun matchesKeyword(t: TicketEntity): Boolean {
        val k = keyword.trim()
        if (k.isEmpty()) return true
        return t.issue.contains(k, ignoreCase = true) ||
            listOf(
                t.verificationCode, t.ticketNumber, t.serialNumber,
                t.terminalNumber, t.stationNumber, t.barcodeRaw,
                t.officialTicketCode
            ).any { it?.contains(k, ignoreCase = true) == true }
    }

    /** 人类可读的条件摘要，给「已筛选：双色球 · 已中奖」这行提示用。 */
    fun summary(): String {
        val parts = buildList {
            type?.let { add(it.display) }
            if (status != StatusFilter.ALL) add(status.display)
            if (dateRange.active) add(dateRange.display())
            if (keyword.isNotBlank()) add("含「${keyword.trim()}」")
        }
        return parts.joinToString(" · ")
    }

    companion object {
        val NONE = TicketFilter()
    }
}

/**
 * 状态筛选档位。
 *
 * 刻意做了 `WON` 这个**组合档**：用户问「我中过哪些奖」时，
 * 心里想的是一次包含「等确认 + 待兑 + 已兑」的全部中奖票 ——
 * 而不是分三次筛。这一档比多选更贴合真实问法。
 */
enum class StatusFilter(val display: String) {
    ALL("全部"),
    PENDING("待开奖"),
    WON("已中奖"),
    NOT_WON("未中奖"),
    TO_REDEEM("待兑奖"),
    REDEEMED("已兑奖"),
    EXPIRED("已过期");

    fun accepts(s: TicketStatus): Boolean = when (this) {
        ALL -> true
        // 「未开奖」「已到开奖日但还没拿到结果」「查不到这一期」对用户是同一类事：
        // **还没对出结果**。归在一个档里，否则「查不到结果」的票没有任何档能筛到它
        // （TicketFilterTest 里有一条测试专门钉这一点）。
        PENDING -> s == TicketStatus.PENDING_DRAW ||
            s == TicketStatus.AWAITING_RESULT ||
            s == TicketStatus.RESULT_UNAVAILABLE
        // 一二等奖待确认也算中奖 —— 用户不会认为「还没确认金额」等于「没中」
        WON -> s == TicketStatus.PRIZE_PENDING ||
            s == TicketStatus.TO_REDEEM ||
            s == TicketStatus.REDEEMED
        NOT_WON -> s == TicketStatus.NOT_WON
        TO_REDEEM -> s == TicketStatus.TO_REDEEM
        REDEEMED -> s == TicketStatus.REDEEMED
        EXPIRED -> s == TicketStatus.EXPIRED_UNCLAIMED
    }
}
