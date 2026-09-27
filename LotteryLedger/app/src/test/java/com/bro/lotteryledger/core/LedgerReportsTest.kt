package com.bro.lotteryledger.core

import com.bro.lotteryledger.db.TicketEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分彩种 / 分期号统计报表（少爷 2026-09-27 第 5 条）。
 *
 * 这里守的核心是**一条不变量**：
 * **各桶净收支之和 = 全部票的净收支（首页总账口径）**。
 *
 * 一旦口径写岔（典型错误：过期票的中奖金额被算进了「中奖」），
 * 报表就会「加起来对不上总账」—— 用户看到会以为账本算错了。
 */
class LedgerReportsTest {

    private fun ticket(
        id: Long,
        type: String = "ssq",
        issue: String = "2026001",
        drawDate: String = "2026-01-02",
        status: String = "NOT_WON",
        purchase: Double = 2.0,
        prize: Double? = null,
        redeemed: Double? = null,
        expired: Double? = null
    ) = TicketEntity(
        id = id,
        lotteryType = type,
        issue = issue,
        purchaseTime = null,
        drawDate = drawDate,
        betType = "SINGLE",
        multiple = 1,
        additional = false,
        purchaseAmount = purchase,
        effectiveUnits = 1,
        ticketStatus = status,
        prizeAmount = prize,
        redeemedAmount = redeemed,
        expiredUnclaimedAmount = expired
    )

    // ---------------- 按彩种 ----------------

    @Test
    fun `按彩种分组 - 双色球和大乐透各一个桶`() {
        val list = listOf(
            ticket(1, type = "ssq", purchase = 10.0, prize = 100.0, status = "REDEEMED", redeemed = 100.0),
            ticket(2, type = "dlt", purchase = 6.0, prize = 3.0, status = "TO_REDEEM")
        )
        val buckets = LedgerReports.byLotteryType(list)
        assertEquals(2, buckets.size)
        // 固定顺序：双色球在前
        assertEquals("双色球", buckets[0].label)
        assertEquals("大乐透", buckets[1].label)
        assertEquals(90.0, buckets[0].net, 0.001)
        assertEquals(-3.0, buckets[1].net, 0.001)
    }

    @Test
    fun `没有票的彩种不出现在报表里`() {
        val list = listOf(ticket(1, type = "ssq"))
        val buckets = LedgerReports.byLotteryType(list)
        assertEquals(1, buckets.size)
        assertEquals("双色球", buckets[0].label)
    }

    // ---------------- 过期票口径（最容易错的地方）----------------

    @Test
    fun `过期票的中奖金额不计入中奖 - 只进过期`() {
        val list = listOf(
            ticket(
                1, purchase = 20.0, status = "EXPIRED_UNCLAIMED",
                prize = 500.0, expired = 500.0
            )
        )
        val b = LedgerReports.byLotteryType(list).single()
        assertEquals("中奖必须是 0，那笔钱没拿到", 0.0, b.prize, 0.001)
        assertEquals("过期要单独记 500", 500.0, b.expired, 0.001)
        assertEquals("净收支 = 0 - 20 = -20", -20.0, b.net, 0.001)
    }

    // ---------------- 不变量：各桶之和 = 总账 ----------------

    @Test
    fun `各彩种净收支之和等于总净收支`() {
        val list = listOf(
            ticket(1, type = "ssq", purchase = 10.0, prize = 100.0, status = "REDEEMED", redeemed = 100.0),
            ticket(2, type = "ssq", purchase = 4.0, status = "NOT_WON"),
            ticket(3, type = "dlt", purchase = 6.0, status = "EXPIRED_UNCLAIMED", prize = 80.0, expired = 80.0),
            ticket(4, type = "dlt", purchase = 8.0, prize = 20.0, status = "TO_REDEEM")
        )
        // 总账口径：purchase 全算；prize 排除 EXPIRED_UNCLAIMED
        val totalPurchase = list.sumOf { it.purchaseAmount }
        val totalPrize = list.filter { it.ticketStatus != "EXPIRED_UNCLAIMED" }
            .sumOf { it.prizeAmount ?: 0.0 }
        val totalNet = totalPrize - totalPurchase

        val sumNet = LedgerReports.byLotteryType(list).sumOf { it.net }
        assertEquals("彩种口径加不出总账，说明口径不一致", totalNet, sumNet, 0.001)

        val sumNetIssue = LedgerReports.byIssue(list).sumOf { it.net }
        assertEquals("期号口径加不出总账", totalNet, sumNetIssue, 0.001)
    }

    // ---------------- 按期号 ----------------

    @Test
    fun `按期号分组 - 同彩种同期号合成一桶`() {
        val list = listOf(
            ticket(1, type = "ssq", issue = "2026001", purchase = 10.0, prize = 5.0, status = "TO_REDEEM"),
            ticket(2, type = "ssq", issue = "2026001", purchase = 4.0, status = "NOT_WON"),
            ticket(3, type = "ssq", issue = "2026002", purchase = 2.0, status = "NOT_WON")
        )
        val buckets = LedgerReports.byIssue(list)
        assertEquals(2, buckets.size)
        val first = buckets.first { it.label == "2026001" }
        assertEquals(2, first.ticketCount)
        assertEquals(14.0, first.purchase, 0.001)
        assertEquals(5.0, first.prize, 0.001)
    }

    @Test
    fun `不同彩种的同期号不能合成一桶`() {
        val list = listOf(
            ticket(1, type = "ssq", issue = "2026001"),
            ticket(2, type = "dlt", issue = "2026001")
        )
        val buckets = LedgerReports.byIssue(list)
        assertEquals("期号相同但彩种不同，必须是两个桶", 2, buckets.size)
    }

    @Test
    fun `按期号排序 - 最近的在最前面`() {
        val list = listOf(
            ticket(1, issue = "2026001", drawDate = "2026-01-02"),
            ticket(2, issue = "2026010", drawDate = "2026-09-20"),
            ticket(3, issue = "2026005", drawDate = "2026-05-10")
        )
        val buckets = LedgerReports.byIssue(list)
        assertEquals("2026010", buckets[0].label)
        assertEquals("2026005", buckets[1].label)
        assertEquals("2026001", buckets[2].label)
    }

    @Test
    fun `空列表不炸`() {
        assertTrue(LedgerReports.byLotteryType(emptyList()).isEmpty())
        assertTrue(LedgerReports.byIssue(emptyList()).isEmpty())
    }
}
