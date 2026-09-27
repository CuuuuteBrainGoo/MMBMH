package com.bro.lotteryledger.repo

import com.bro.lotteryledger.core.DrawNumbers
import com.bro.lotteryledger.core.LotteryType
import com.bro.lotteryledger.core.PrizeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量核验的汇总文案。
 *
 * 少爷 2026-09-27 报的问题里有一半是**文案骗人**：
 * 他点了「立即核验」，看到「核验了 3 张，都没中」，
 * 但列表里那 3 张还挂着「等待开奖结果」。
 *
 * 真实原因是那 3 张**根本没查到结果**（查询范围只覆盖 3.8 个月，票是 5 个月前的），
 * 而旧文案把「查不到」和「等官方公布」都归进了「都没中」——
 * 这不是描述不准，是**说反了**。
 */
class PrizeSummarizeTest {

    private fun won() = PrizeService.Outcome.Won(
        result = PrizeResult(
            levels = emptyList(),
            draw = DrawNumbers(
                type = LotteryType.SSQ,
                issue = "2026100",
                drawDate = "2026-09-24",
                main = listOf("01", "02", "03", "04", "05", "06"),
                second = listOf("07")
            ),
            poolHigh = false,
            multiple = 1,
            additional = false
        ),
        prizeYuan = 10.0,
        needManualConfirm = false,
        officialFloatingUsed = false
    )

    @Test
    fun `空批次直接说不必核验`() {
        assertEquals("没有待核验的票", PrizeService.summarize(emptyList()))
    }

    @Test
    fun `查不到结果不能算作没中奖`() {
        // 这是本次修复的核心：少爷看到的那句误导性文案
        val s = PrizeService.summarize(
            listOf(
                PrizeService.Outcome.ResultUnavailable("查不到"),
                PrizeService.Outcome.ResultUnavailable("查不到"),
                PrizeService.Outcome.ResultUnavailable("查不到")
            )
        )
        assertTrue("不能出现「都没中」这种把查不到说成没中的表述", !s.contains("都没中"))
        assertTrue("应该说清楚是查不到", s.contains("查不到"))
        assertTrue("并且提示需要人工处理", s.contains("手动"))
    }

    @Test
    fun `等待官方公布和查不到是两回事`() {
        val awaiting = PrizeService.summarize(listOf(PrizeService.Outcome.AwaitingResult("还没出")))
        val unavailable = PrizeService.summarize(listOf(PrizeService.Outcome.ResultUnavailable("查不到")))
        assertTrue(awaiting.contains("官方还没公布"))
        assertTrue(unavailable.contains("查不到"))
        // 两者的说法必须不同，否则用户分不清该等还是该处理
        assertTrue(awaiting != unavailable)
    }

    @Test
    fun `多种结果混在一起时逐项列出`() {
        val s = PrizeService.summarize(
            listOf(
                won(),
                PrizeService.Outcome.NotWon,
                PrizeService.Outcome.AwaitingResult("还没出"),
                PrizeService.Outcome.ResultUnavailable("查不到"),
                PrizeService.Outcome.Failed("网络错误")
            )
        )
        assertTrue(s.contains("核验了 5 张"))
        assertTrue(s.contains("1 张中奖"))
        assertTrue(s.contains("1 张未中"))
        assertTrue(s.contains("1 张官方还没公布"))
        assertTrue(s.contains("1 张查不到结果"))
        assertTrue(s.contains("1 张查询失败"))
    }

    @Test
    fun `没中奖时才说未中`() {
        val s = PrizeService.summarize(
            listOf(PrizeService.Outcome.NotWon, PrizeService.Outcome.NotWon)
        )
        assertTrue(s.contains("2 张未中"))
    }

    @Test
    fun `还没到开奖日的票单独说`() {
        val s = PrizeService.summarize(listOf(PrizeService.Outcome.NotDue))
        assertTrue(s.contains("还没到开奖日"))
    }

    @Test
    fun `查询失败要提示去看日志`() {
        val s = PrizeService.summarize(listOf(PrizeService.Outcome.Failed("timeout")))
        assertTrue(s.contains("失败"))
    }
}
