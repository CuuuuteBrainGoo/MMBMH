package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 开奖时刻判定。
 *
 * 背景（2026-09-28 少爷报的 bug）：
 * 大乐透 26111 期，票面开奖日 **2026-09-28**（就是当天），当晚 21:30 才开奖。
 * 结果下午 18:12 打开 App，首页就弹「有 1 张彩票已过开奖日期」。
 * 根因是待核验查询只比了日期字符串，没看时间。
 *
 * 这三组测试对应三个消费者，都必须钉死：
 *  - [DrawSchedule.checkCutoff]   → 待核验队列的截止日（首页黄条 / 「待开奖」计数）
 *  - [DrawSchedule.drawTimePassed] → 核验前问「能不能去查了」
 *  - [DrawSchedule.effectiveStatus] → 列表 / 详情页的状态徽章
 */
class DrawScheduleTest {

    /** 少爷报 bug 那天：2026-09-28（周一，大乐透开奖日）。 */
    private val today = LocalDate.of(2026, 9, 28)

    private fun cut(hour: Int, minute: Int = 0): String =
        DrawSchedule.checkCutoff(today, LocalTime.of(hour, minute))

    private fun passed(date: String?, hour: Int, minute: Int = 0): Boolean =
        DrawSchedule.drawTimePassed(date, today, LocalTime.of(hour, minute))

    private fun eff(stored: TicketStatus, date: String?, hour: Int = 12): TicketStatus =
        DrawSchedule.effectiveStatus(stored, date, today, LocalTime.of(hour, 0))

    // ============ checkCutoff：bug 的正面战场 ============

    @Test
    fun `22 点之前 截止日是昨天`() {
        assertEquals("2026-09-27", cut(0, 0))
        assertEquals("2026-09-27", cut(12, 0))
        assertEquals("2026-09-27", cut(18, 12))   // ← 少爷截图里的时刻
        assertEquals("2026-09-27", cut(21, 59))
    }

    @Test
    fun `22 点整起 截止日变成今天`() {
        assertEquals("2026-09-28", cut(22, 0))
        assertEquals("2026-09-28", cut(23, 59))
    }

    @Test
    fun `少爷这张票 18 点 12 分不该进待核验队列`() {
        // 这是本 bug 的最小复现：开奖日当天的票，下午不能被捞进队列。
        val cutoff = cut(18, 12)
        assertFalse(
            "draw_date(2026-09-28) <= cutoff($cutoff) 不该成立，否则首页会误报「已过开奖日期」",
            "2026-09-28" <= cutoff
        )
        // 当天 22:00 之后就**应该**被捞出来了
        assertTrue("2026-09-28" <= cut(22, 0))
    }

    @Test
    fun `月初 22 点前 截止日跨月到上个月最后一天`() {
        val c = DrawSchedule.checkCutoff(LocalDate.of(2026, 10, 1), LocalTime.of(8, 0))
        assertEquals("2026-09-30", c)
    }

    @Test
    fun `元旦 22 点前 截止日跨年到去年最后一天`() {
        val c = DrawSchedule.checkCutoff(LocalDate.of(2027, 1, 1), LocalTime.of(8, 30))
        assertEquals("2026-12-31", c)
    }

    @Test
    fun `定时任务 22 点 30 跑的时候 截止日就是今天`() {
        // 晚上 22:30 的核验任务必须能捞到「今天开奖」的票
        assertEquals("2026-09-28", cut(22, 30))
    }

    @Test
    fun `次日 8 点 30 补查时 昨天开奖的票仍能被捞到`() {
        // 8:30 < 22:00 → 截止日 = 昨天 = 09-27，昨天开奖的票不会被漏掉
        val cutoff = cut(8, 30)
        assertEquals("2026-09-27", cutoff)
        assertTrue("2026-09-27 的票要能捞到", "2026-09-27" <= cutoff)
    }

    // ============ drawTimePassed ============

    @Test
    fun `开奖日在昨天 无论几点都算过了`() {
        assertTrue(passed("2026-09-27", 0, 1))
        assertTrue(passed("2026-09-27", 23, 59))
    }

    @Test
    fun `开奖日在明天 无论几点都算没过`() {
        assertFalse(passed("2026-09-29", 0, 0))
        assertFalse(passed("2026-09-29", 23, 59))
    }

    @Test
    fun `当天开奖 21 点 30 还没过`() {
        // 官方 21:30 开奖，公告要几分钟才出，这时查大概率拿不到
        assertFalse(passed("2026-09-28", 21, 30))
    }

    @Test
    fun `当天开奖 21 点 59 还没过`() {
        assertFalse(passed("2026-09-28", 21, 59))
    }

    @Test
    fun `当天开奖 22 点整算过了`() {
        assertTrue(passed("2026-09-28", 22, 0))
    }

    @Test
    fun `开奖日读不出来 保守当没过`() {
        // 读不到日期就去打网络，等于每张残票都白跑一次
        assertFalse(passed(null, 23, 0))
        assertFalse(passed("", 23, 0))
        assertFalse(passed("   ", 23, 0))
        assertFalse(passed("2026年9月28日", 23, 0))
    }

    @Test
    fun `开奖日带空格 能解析`() {
        assertTrue(passed("  2026-09-27  ", 1, 0))
    }

    // ============ 口径一致性（机制性测试，防止再漂移）============

    @Test
    fun `遍历一天 24 小时 checkCutoff 与 drawTimePassed 必须等价`() {
        // 这是本文件最重要的一条：两个函数是同一个口径的两种表达 ——
        //   进队列（draw_date <= cutoff）  ⟺  开奖时间已过
        // 哪天有人只改其中一个，这条就会红。
        val drawDate = "2026-09-28"   // 就是今天
        for (hour in 0..23) {
            val now = LocalTime.of(hour, 0)
            val isPassed = DrawSchedule.drawTimePassed(drawDate, today, now)
            val inQueue = drawDate <= DrawSchedule.checkCutoff(today, now)
            assertEquals("$hour 点：drawTimePassed 与 checkCutoff 口径不一致", isPassed, inQueue)
        }
    }

    @Test
    fun `分界点就是 DRAW_HOUR 本身`() {
        assertEquals(22, DrawSchedule.DRAW_HOUR)
        assertFalse(DrawSchedule.drawTimePassed("2026-09-28", today, LocalTime.of(21, 59)))
        assertTrue(DrawSchedule.drawTimePassed("2026-09-28", today, LocalTime.of(22, 0)))
    }

    // ============ effectiveStatus：存量票的展示修正 ============

    @Test
    fun `当天下午的「等待开奖结果」 展示成「未开奖」`() {
        assertEquals(
            TicketStatus.PENDING_DRAW,
            eff(TicketStatus.AWAITING_RESULT, "2026-09-28", hour = 18)
        )
    }

    @Test
    fun `当天 22 点后的「等待开奖结果」 保持不变`() {
        assertEquals(
            TicketStatus.AWAITING_RESULT,
            eff(TicketStatus.AWAITING_RESULT, "2026-09-28", hour = 22)
        )
    }

    @Test
    fun `昨天开奖的票 保持「等待开奖结果」`() {
        assertEquals(
            TicketStatus.AWAITING_RESULT,
            eff(TicketStatus.AWAITING_RESULT, "2026-09-27", hour = 0)
        )
    }

    @Test
    fun `本来就是未开奖 修正后还是未开奖`() {
        assertEquals(
            TicketStatus.PENDING_DRAW,
            eff(TicketStatus.PENDING_DRAW, "2026-09-28", hour = 18)
        )
    }

    @Test
    fun `真实结果类状态一律不动`() {
        // 这些状态是核验/兑奖留下的**真实结果**，绝不能被展示层改写
        for (s in listOf(
            TicketStatus.NOT_WON,
            TicketStatus.TO_REDEEM,
            TicketStatus.PRIZE_PENDING,
            TicketStatus.REDEEMED,
            TicketStatus.EXPIRED_UNCLAIMED,
            TicketStatus.RESULT_UNAVAILABLE
        )) {
            assertEquals(s, eff(s, "2026-09-28", hour = 10))
        }
    }

    @Test
    fun `开奖日读不出来时 不会显示成「等待开奖结果」`() {
        // 日期缺失 → drawTimePassed = false → 收敛到「未开奖」。
        // 保守方向：宁可显示「未开奖」，也不要挂「等待结果」误导用户去点核验。
        assertEquals(TicketStatus.PENDING_DRAW, eff(TicketStatus.AWAITING_RESULT, null, hour = 23))
    }
}
