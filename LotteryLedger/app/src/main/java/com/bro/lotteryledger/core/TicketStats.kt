package com.bro.lotteryledger.core

import com.bro.lotteryledger.db.TicketEntity

/**
 * 一组票的金额汇总。**口径必须跟总账完全一致**，否则用户会发现
 * 「筛选后的金额加起来对不上首页主账」—— 这是最容易被质疑的那类 bug。
 *
 * 口径（跟 `TicketDao.stats()` 的 SQL 一一对应，改一边必须改另一边）：
 *
 * | 字段 | 怎么算 |
 * |---|---|
 * | [purchase] | 全部票的购彩金额，不管中没中、兑没兑 |
 * | [prize] | `prizeAmount`，但**排除 `EXPIRED_UNCLAIMED`** |
 * | [redeemed] | `redeemedAmount` |
 * | [toRedeem] | 状态是 `TO_REDEEM` 且有 `prizeAmount` |
 * | [expired] | `expiredUnclaimedAmount` |
 *
 * **为什么 `prize` 要排除过期票**：过期票在转状态时 `prize_amount` 是保留的
 * （界面要显示"作废了多少钱"）。如果不过滤，那笔**永远拿不到的钱**会被算进收入，
 * 净收支凭空多一笔，而用户以为钱已经到手了。少爷 2026-09-27 明确要求过。
 */
data class TicketStats(
    val purchase: Double = 0.0,
    val prize: Double = 0.0,
    val redeemed: Double = 0.0,
    val toRedeem: Double = 0.0,
    val expired: Double = 0.0,
    val count: Int = 0
) {
    /** 净收支 = 中奖 − 花出去。跟首页主数字是同一个公式。 */
    val net: Double get() = prize - purchase

    companion object {
        /**
         * 在一批票上跑同一套口径。
         *
         * 用 `fold` 而不是 `sumOf` × 5 —— 五个字段各自 `sumOf` 会遍历五遍列表，
         * 筛选是每次输入关键词都会重跑的热路径。
         */
        fun of(tickets: List<TicketEntity>): TicketStats = tickets.fold(TicketStats()) { acc, t ->
            val status = TicketStatus.from(t.ticketStatus)
            val prize = t.prizeAmount
            TicketStats(
                purchase = acc.purchase + t.purchaseAmount,
                // 过期票的奖金不算收入 —— 唯一的排除项
                prize = acc.prize + if (prize != null && status != TicketStatus.EXPIRED_UNCLAIMED) prize else 0.0,
                redeemed = acc.redeemed + (t.redeemedAmount ?: 0.0),
                toRedeem = acc.toRedeem + if (status == TicketStatus.TO_REDEEM && prize != null) prize else 0.0,
                expired = acc.expired + (t.expiredUnclaimedAmount ?: 0.0),
                count = acc.count + 1
            )
        }
    }
}
