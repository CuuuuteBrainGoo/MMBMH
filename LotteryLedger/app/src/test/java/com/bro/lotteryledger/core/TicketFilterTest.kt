package com.bro.lotteryledger.core

import com.bro.lotteryledger.db.TicketEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 列表筛选与搜索。
 *
 * ## 为什么这个测试必须写
 *
 * 筛错了的后果不是「少看到几行」，而是**用户以为那张票丢了** ——
 * 然后可能去重新录一遍（制造重复），或者以为数据损坏。
 * 所以每个筛选档位的边界都要钉死。
 */
class TicketFilterTest {

    private fun ticket(
        id: Long = 1,
        type: String = "ssq",
        issue: String = "2026111",
        status: String = "PENDING_DRAW",
        /** 开奖日期。默认落在 2026 年，跟其他用例的时间基准一致 */
        drawDate: String = "2026-09-24",
        verificationCode: String? = null,
        serialNumber: String? = null,
        barcodeRaw: String? = null,
        terminalNumber: String? = null
    ) = TicketEntity(
        id = id,
        lotteryType = type,
        issue = issue,
        purchaseTime = null,
        drawDate = drawDate,
        betType = "SINGLE",
        multiple = 1,
        additional = false,
        purchaseAmount = 10.0,
        effectiveUnits = 5,
        verificationCode = verificationCode,
        serialNumber = serialNumber,
        barcodeRaw = barcodeRaw,
        terminalNumber = terminalNumber,
        ticketStatus = status
    )

    // ---------------- 空条件 ----------------

    @Test
    fun `没有条件时-匹配一切`() {
        val f = TicketFilter.NONE
        assertTrue(f.matches(ticket(type = "ssq")))
        assertTrue(f.matches(ticket(type = "dlt", status = "REDEEMED")))
        assertFalse("空条件不该算「已筛选」", f.active)
        assertEquals(0, f.activeCount)
    }

    @Test
    fun `只输入空格的关键词-等于没筛`() {
        val f = TicketFilter(keyword = "   ")
        assertTrue(f.matches(ticket()))
        assertFalse(f.active)
    }

    // ---------------- 彩种 ----------------

    @Test
    fun `按彩种筛-只留该彩种`() {
        val f = TicketFilter(type = LotteryType.SSQ)
        assertTrue(f.matches(ticket(type = "ssq")))
        assertFalse(f.matches(ticket(type = "dlt")))
        assertEquals(1, f.activeCount)
    }

    @Test
    fun `按彩种筛-存储值大小写不敏感`() {
        // 数据库里存的是 code（ssq/dlt），但备份导入等路径可能带大写
        assertTrue(TicketFilter(type = LotteryType.SSQ).matches(ticket(type = "SSQ")))
        assertTrue(TicketFilter(type = LotteryType.DLT).matches(ticket(type = "Dlt")))
    }

    // ---------------- 状态：单档 ----------------

    @Test
    fun `未中奖档-只留未中奖`() {
        val f = TicketFilter(status = StatusFilter.NOT_WON)
        assertTrue(f.matches(ticket(status = "NOT_WON")))
        assertFalse(f.matches(ticket(status = "TO_REDEEM")))
        assertFalse(f.matches(ticket(status = "PENDING_DRAW")))
    }

    @Test
    fun `待兑奖档-只留待兑奖`() {
        val f = TicketFilter(status = StatusFilter.TO_REDEEM)
        assertTrue(f.matches(ticket(status = "TO_REDEEM")))
        assertFalse("奖金待确认还没到待兑奖", f.matches(ticket(status = "PRIZE_PENDING")))
        assertFalse("已兑完的不算待兑", f.matches(ticket(status = "REDEEMED")))
    }

    @Test
    fun `已过期档-只留过期未兑`() {
        val f = TicketFilter(status = StatusFilter.EXPIRED)
        assertTrue(f.matches(ticket(status = "EXPIRED_UNCLAIMED")))
        assertFalse(f.matches(ticket(status = "REDEEMED")))
    }

    // ---------------- 状态：两个组合档（重点） ----------------

    @Test
    fun `待开奖档-要同时包含未开奖和等待结果`() {
        // 「已到开奖日但官方还没出结果」对用户就是「还没开奖」，不能漏
        val f = TicketFilter(status = StatusFilter.PENDING)
        assertTrue(f.matches(ticket(status = "PENDING_DRAW")))
        assertTrue("漏了「等待开奖结果」用户会以为票不见了", f.matches(ticket(status = "AWAITING_RESULT")))
        assertFalse(f.matches(ticket(status = "NOT_WON")))
    }

    @Test
    fun `已中奖档-要同时包含待确认待兑和已兑`() {
        // 用户问「我中过哪些奖」时，想一次看到全部中奖票，而不是分三次筛
        val f = TicketFilter(status = StatusFilter.WON)
        assertTrue("一二等奖待确认也是中奖", f.matches(ticket(status = "PRIZE_PENDING")))
        assertTrue(f.matches(ticket(status = "TO_REDEEM")))
        assertTrue(f.matches(ticket(status = "REDEEMED")))
        assertFalse(f.matches(ticket(status = "NOT_WON")))
        assertFalse("过期未兑说明中过奖，但钱没拿到 —— 单独一档更清楚", f.matches(ticket(status = "EXPIRED_UNCLAIMED")))
        assertFalse(f.matches(ticket(status = "PENDING_DRAW")))
    }

    @Test
    fun `全部档-七个状态都收`() {
        val all = listOf(
            "PENDING_DRAW", "AWAITING_RESULT", "NOT_WON",
            "PRIZE_PENDING", "TO_REDEEM", "REDEEMED", "EXPIRED_UNCLAIMED"
        )
        all.forEach {
            assertTrue("StatusFilter.ALL 漏了 $it", StatusFilter.ALL.accepts(TicketStatus.from(it)))
        }
    }

    @Test
    fun `所有状态都被至少一个非全部档覆盖`() {
        // 防止加了新状态却忘了加筛选档 —— 那样新状态的票永远筛不出来
        val uncovered = TicketStatus.entries.filter { s ->
            StatusFilter.entries.none { it != StatusFilter.ALL && it.accepts(s) }
        }
        assertTrue("这些状态没有任何筛选档能选中：$uncovered", uncovered.isEmpty())
    }

    // ---------------- 关键词 ----------------

    @Test
    fun `关键词-匹配期号`() {
        val f = TicketFilter(keyword = "2026111")
        assertTrue(f.matches(ticket(issue = "2026111")))
        assertFalse(f.matches(ticket(issue = "2026112")))
    }

    @Test
    fun `关键词-匹配期号的一部分`() {
        assertTrue(TicketFilter(keyword = "111").matches(ticket(issue = "2026111")))
        assertTrue(TicketFilter(keyword = "2026").matches(ticket(issue = "2026111")))
    }

    @Test
    fun `关键词-匹配各类票面编号`() {
        // 手上拿着一张票想知道录过没，最直接的就是把票上的编号查一遍
        assertTrue(TicketFilter(keyword = "VC-01").matches(ticket(verificationCode = "VC-0123")))
        assertTrue(TicketFilter(keyword = "SN99").matches(ticket(serialNumber = "SN9988")))
        assertTrue(TicketFilter(keyword = "6901234").matches(ticket(barcodeRaw = "6901234567890")))
        assertTrue(TicketFilter(keyword = "TERM").matches(ticket(terminalNumber = "TERM-07")))
    }

    @Test
    fun `关键词-编号为 null 时不会崩`() {
        // 弱票（模型啥编号都没读到）在真实使用里很常见
        val f = TicketFilter(keyword = "ABC")
        assertFalse(f.matches(ticket(verificationCode = null, serialNumber = null)))
    }

    @Test
    fun `关键词-忽略大小写`() {
        assertTrue(TicketFilter(keyword = "vc-01").matches(ticket(verificationCode = "VC-0123")))
        assertTrue(TicketFilter(keyword = "VC-01").matches(ticket(verificationCode = "vc-0123")))
    }

    @Test
    fun `关键词-前后空格自动去掉`() {
        assertTrue(TicketFilter(keyword = "  2026111  ").matches(ticket(issue = "2026111")))
    }

    // ---------------- 多条件组合（AND） ----------------

    @Test
    fun `多条件是并且关系`() {
        val f = TicketFilter(
            type = LotteryType.DLT,
            status = StatusFilter.WON,
            keyword = "26109"
        )
        assertTrue(f.matches(ticket(type = "dlt", issue = "26109", status = "TO_REDEEM")))
        assertFalse("彩种不对", f.matches(ticket(type = "ssq", issue = "26109", status = "TO_REDEEM")))
        assertFalse("状态不对", f.matches(ticket(type = "dlt", issue = "26109", status = "NOT_WON")))
        assertFalse("关键词不对", f.matches(ticket(type = "dlt", issue = "26110", status = "TO_REDEEM")))
        assertEquals(3, f.activeCount)
    }

    // ---------------- 界面提示用的辅助 ----------------

    @Test
    fun `条件摘要-单条件`() {
        assertEquals("大乐透", TicketFilter(type = LotteryType.DLT).summary())
        assertEquals("已中奖", TicketFilter(status = StatusFilter.WON).summary())
    }

    @Test
    fun `条件摘要-多条件拼起来`() {
        val f = TicketFilter(
            type = LotteryType.SSQ,
            status = StatusFilter.TO_REDEEM,
            keyword = "2026111"
        )
        assertEquals("双色球 · 待兑奖 · 含「2026111」", f.summary())
    }

    @Test
    fun `条件摘要-无条件时为空串`() {
        assertEquals("", TicketFilter.NONE.summary())
    }

    @Test
    fun `activeCount-逐项累加`() {
        assertEquals(1, TicketFilter(type = LotteryType.SSQ).activeCount)
        assertEquals(1, TicketFilter(status = StatusFilter.WON).activeCount)
        assertEquals(1, TicketFilter(keyword = "abc").activeCount)
        // 日期筛是一维（起止算一个条件，不是两个）—— 用户脑子里「按日期筛了」是一件事
        assertEquals(1, TicketFilter(dateRange = DateRange(from = "2026-01-01")).activeCount)
        assertEquals(1, TicketFilter(
            dateRange = DateRange(from = "2026-01-01", to = "2026-06-30")
        ).activeCount)
        assertEquals(2, TicketFilter(type = LotteryType.SSQ, status = StatusFilter.WON).activeCount)
        assertEquals(3, TicketFilter(
            type = LotteryType.SSQ, status = StatusFilter.WON, keyword = "abc"
        ).activeCount)
        assertEquals(4, TicketFilter(
            type = LotteryType.SSQ, status = StatusFilter.WON, keyword = "abc",
            dateRange = DateRange(from = "2026-01-01")
        ).activeCount)
    }

    // ---------------- 按日期筛（少爷 2026-09-27） ----------------

    @Test
    fun `日期-单边起点`() {
        val f = TicketFilter(dateRange = DateRange(from = "2026-06-01"))
        assertTrue(f.matches(ticket(drawDate = "2026-06-01")))
        assertTrue("边界当天要算在内", f.matches(ticket(drawDate = "2026-06-01")))
        assertTrue(f.matches(ticket(drawDate = "2026-09-24")))
        assertFalse(f.matches(ticket(drawDate = "2026-05-31")))
    }

    @Test
    fun `日期-单边终点`() {
        val f = TicketFilter(dateRange = DateRange(to = "2026-06-30"))
        assertTrue(f.matches(ticket(drawDate = "2026-06-30")))
        assertTrue(f.matches(ticket(drawDate = "2020-01-01")))
        assertFalse(f.matches(ticket(drawDate = "2026-07-01")))
    }

    @Test
    fun `日期-两端都给了`() {
        val f = TicketFilter(dateRange = DateRange("2026-06-01", "2026-06-30"))
        assertTrue("起点当天在内", f.matches(ticket(drawDate = "2026-06-01")))
        assertTrue("终点当天在内", f.matches(ticket(drawDate = "2026-06-30")))
        assertFalse(f.matches(ticket(drawDate = "2026-05-31")))
        assertFalse(f.matches(ticket(drawDate = "2026-07-01")))
    }

    @Test
    fun `日期-不限时不筛掉任何票`() {
        val f = TicketFilter(dateRange = DateRange.NONE)
        assertTrue(f.matches(ticket(drawDate = "1999-01-01")))
        assertTrue(f.matches(ticket(drawDate = "2099-12-31")))
        assertFalse(f.active)
    }

    @Test
    fun `日期-开奖日期解析不出来的票不被筛掉`() {
        // 宁可多显示也不让用户以为票丢了。库里理论上不该有脏数据，但不能靠这个假设。
        val f = TicketFilter(dateRange = DateRange(from = "2026-01-01", to = "2026-12-31"))
        assertTrue(f.matches(ticket(drawDate = "")))
    }

    @Test
    fun `日期-跟其他条件是与关系`() {
        val f = TicketFilter(
            type = LotteryType.SSQ,
            dateRange = DateRange(from = "2026-06-01", to = "2026-06-30")
        )
        assertTrue(f.matches(ticket(type = "ssq", drawDate = "2026-06-15")))
        assertFalse("彩种不对", f.matches(ticket(type = "dlt", drawDate = "2026-06-15")))
        assertFalse("日期不在区间", f.matches(ticket(type = "ssq", drawDate = "2026-07-15")))
    }

    @Test
    fun `日期-摘要里带上区间`() {
        val f = TicketFilter(dateRange = DateRange("2026-06-01", "2026-06-30"))
        assertEquals("2026-06-01 → 2026-06-30", f.summary())
    }

    @Test
    fun `日期-摘要-多条件按固定顺序`() {
        val f = TicketFilter(
            type = LotteryType.SSQ,
            status = StatusFilter.WON,
            keyword = "2026111",
            dateRange = DateRange("2026-06-01", "2026-06-30")
        )
        // 彩种 · 状态 · 区间 · 关键词 —— 顺序固定，别让界面上的摘要跳来跳去
        assertEquals("双色球 · 已中奖 · 2026-06-01 → 2026-06-30 · 含「2026111」", f.summary())
    }

    // ---------------- 快捷段边界（重点：跨月/跨年） ----------------

    @Test
    fun `快捷段-上个月是完整自然月`() {
        // 9 月 27 日点「上个月」→ 8/1 ~ 8/31（不是 8/27 ~ 9/27）
        val got = DateQuickPick.LAST_MONTH.resolve(LocalDate.of(2026, 9, 27))
        assertEquals(DateRange("2026-08-01", "2026-08-31"), got)
    }

    @Test
    fun `快捷段-上个月跨年`() {
        // 1 月点「上个月」→ 去年 12 月。这条最容易写错（要减年）
        val got = DateQuickPick.LAST_MONTH.resolve(LocalDate.of(2026, 1, 15))
        assertEquals(DateRange("2025-12-01", "2025-12-31"), got)
    }

    @Test
    fun `快捷段-上个月碰到二月`() {
        // 3 月点「上个月」→ 平年 2 月是 28 天。用 minusMonths 而不是 minusDays(30) 才不会错
        val got = DateQuickPick.LAST_MONTH.resolve(LocalDate.of(2026, 3, 10))
        assertEquals(DateRange("2026-02-01", "2026-02-28"), got)
        // 闰年
        assertEquals(
            DateRange("2024-02-01", "2024-02-29"),
            DateQuickPick.LAST_MONTH.resolve(LocalDate.of(2024, 3, 10))
        )
    }

    @Test
    fun `快捷段-近3个月是滚动窗口`() {
        val got = DateQuickPick.RECENT_3M.resolve(LocalDate.of(2026, 9, 27))
        assertEquals(DateRange("2026-06-27", "2026-09-27"), got)
    }

    @Test
    fun `快捷段-上半年和下半年是整半年`() {
        assertEquals(
            DateRange("2026-01-01", "2026-06-30"),
            DateQuickPick.H1.resolve(LocalDate.of(2026, 9, 27))
        )
        // 下半年终点是 12/31 而不是"今天" —— 否则将来录的 10~12 月票会筛不出来
        assertEquals(
            DateRange("2026-07-01", "2026-12-31"),
            DateQuickPick.H2.resolve(LocalDate.of(2026, 9, 27))
        )
    }

    @Test
    fun `快捷段-今年是整年`() {
        assertEquals(
            DateRange("2026-01-01", "2026-12-31"),
            DateQuickPick.THIS_YEAR.resolve(LocalDate.of(2026, 9, 27))
        )
    }

    @Test
    fun `快捷段-上半年和近3个月在同一天时是两个不同的段`() {
        // 2026-09-27 点「近 3 个月」是 6/27 起，点「上半年」是 1/1~6/30 ——
        // 两组区间确实不同，不许因为"看起来差不多"就去重
        val t = LocalDate.of(2026, 9, 27)
        assertNotEquals(DateQuickPick.H1.resolve(t), DateQuickPick.RECENT_3M.resolve(t))
    }

    @Test
    fun `快捷段-区间显示文案`() {
        assertEquals("2026-06-01 → 2026-06-30", DateRange("2026-06-01", "2026-06-30").display())
        assertEquals("2026-06-01 起", DateRange(from = "2026-06-01").display())
        assertEquals("截止 2026-06-30", DateRange(to = "2026-06-30").display())
        assertEquals("不限", DateRange.NONE.display())
    }

    // ---------------- 真实场景 ----------------

    @Test
    fun `场景-筛出待兑奖的票提醒自己去兑`() {
        val tickets = listOf(
            ticket(id = 1, status = "TO_REDEEM"),
            ticket(id = 2, status = "NOT_WON"),
            ticket(id = 3, status = "TO_REDEEM"),
            ticket(id = 4, status = "REDEEMED")
        )
        val got = tickets.filter { TicketFilter(status = StatusFilter.TO_REDEEM).matches(it) }
        assertEquals(listOf(1L, 3L), got.map { it.id })
    }

    @Test
    fun `场景-手上有个验票码想查录过没`() {
        val tickets = listOf(
            ticket(id = 1, verificationCode = "VC-0001"),
            ticket(id = 2, verificationCode = "VC-0002")
        )
        val got = tickets.filter { TicketFilter(keyword = "VC-0002").matches(it) }
        assertEquals(listOf(2L), got.map { it.id })
    }

    @Test
    fun `场景-已经筛过的列表再筛一次`() {
        // 用户在面板里连续点不同的 chip，每次都基于最新条件，不能叠加出空集
        var f = TicketFilter.NONE
        f = f.copy(type = LotteryType.SSQ)
        f = f.copy(status = StatusFilter.WON)
        f = f.copy(type = null)          // 改主意，取消彩种
        assertEquals(StatusFilter.WON, f.status)
        assertTrue(f.matches(ticket(type = "dlt", status = "REDEEMED")))
    }
}
