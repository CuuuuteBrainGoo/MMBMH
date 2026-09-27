package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 「号码撞上本期大奖」的调侃彩蛋（少爷 2026-09-27 第 4 条 —— 他的真实意图）。
 *
 * 少爷原话：
 * > 我只想提示用户，以前购买过的一个号码意外和本期的大奖号码一样，
 * > **并不是真的中了本期的大奖**。跟用户开个小玩笑。
 *
 * 这里要守两条：
 *  1. **判据**：只有"往期那一期买的、号码和本期开奖号码完全一样"才算 ——
 *     本期自己买的票不算（那是真中奖，归 PrizeRules）
 *  2. **文案不许像中奖**：出现"中了""恭喜"这种词，玩笑就变成假好消息了
 */
class JackpotTeaseTest {

    private val latest = DrawNumbers(
        type = LotteryType.DLT,
        issue = "26112",
        drawDate = "2026-09-26",
        main = listOf("09", "11", "18", "26", "33"),
        second = listOf("09", "11")
    )

    private fun bet(
        issue: String = "26101",
        drawDate: String = "2026-09-05",
        type: LotteryType = LotteryType.DLT,
        index: Int = 1,
        main: List<String> = listOf("09", "11", "18", "26", "33"),
        second: List<String> = listOf("09", "11")
    ) = JackpotTease.PastBet(
        ticketId = 1L,
        lotteryType = type,
        issue = issue,
        drawDate = drawDate,
        betIndex = index,
        main = main,
        second = second
    )

    private fun find(vararg bets: JackpotTease.PastBet) =
        JackpotTease.find(latest, bets.toList(), Random(7))

    // ---------------- 命中 ----------------

    @Test
    fun `往期买过一模一样的号码 - 这就是要调侃的场景`() {
        val t = find(bet())
        assertTrue("号码全同应当命中", t != null)
        assertEquals("26112", t!!.latestIssue)
        assertEquals("26101", t.pastIssue)
        assertEquals(1, t.pastBetIndex)
    }

    @Test
    fun `号码顺序不同但集合相同也要认`() {
        // 票面印的顺序和开奖顺序不一定一样，比的是号码本身
        val t = find(bet(main = listOf("33", "11", "09", "26", "18"), second = listOf("11", "09")))
        assertTrue("集合相同就该命中", t != null)
    }

    @Test
    fun `多注里只要有一注撞上就命中`() {
        val t = find(
            bet(index = 1, main = listOf("01", "02", "03", "04", "05"), second = listOf("01", "02")),
            bet(index = 2)
        )
        assertEquals("应该报出撞上的那一注", 2, t!!.pastBetIndex)
    }

    @Test
    fun `天数差要算出来`() {
        val t = find(bet(drawDate = "2026-09-05"))
        // 2026-09-05 → 2026-09-26 = 21 天
        assertEquals(21L, t!!.daysBetween)
    }

    // ---------------- 不该命中的 ----------------

    @Test
    fun `本期自己买的票不算巧合 - 那是真中奖`() {
        // 如果这注是本期买的，那它是**真中了一等奖**，走 PrizeRules + 奖金确认，
        // 不该被这里当玩笑调侃 —— 把真中奖说成玩笑是灾难。
        assertNull(find(bet(issue = "26112", drawDate = "2026-09-26")))
    }

    @Test
    fun `差一个号码就不算`() {
        assertNull(find(bet(main = listOf("09", "11", "18", "26", "34"))))
        assertNull(find(bet(second = listOf("09", "12"))))
    }

    @Test
    fun `跨彩种不能混比`() {
        // 双色球的红球 6 个、蓝球 1 个，跟大乐透 5+2 是两套规则，号码碰巧一样也不算
        assertNull(find(bet(type = LotteryType.SSQ)))
    }

    @Test
    fun `空号码不许命中`() {
        assertNull(find(bet(main = emptyList(), second = emptyList())))
        assertNull(find(bet(second = emptyList())))
    }

    @Test
    fun `库里没票就没有玩笑`() {
        assertNull(JackpotTease.find(latest, emptyList(), Random(7)))
    }

    // ---------------- 文案 ----------------

    @Test
    fun `配文不能像中奖`() {
        // 玩笑必须一眼看得出是玩笑。出现"中了""恭喜"这类词 = 假好消息，比不弹还糟。
        val forbidden = listOf("中了", "恭喜", "中奖", "快去兑")
        for (seed in 0 until 40) {
            val t = JackpotTease.find(latest, listOf(bet()), Random(seed))!!
            forbidden.forEach {
                assertFalse("配文不该出现「$it」：${t.punchline}", t.punchline.contains(it))
            }
        }
    }

    @Test
    fun `配文池有多条 - 同一个玩笑不该每次一样`() {
        val seen = (0 until 60)
            .map { JackpotTease.find(latest, listOf(bet()), Random(it))!!.punchline }
            .toSet()
        // 少爷 2026-09-27 第 4 条要求「多设计几套」，池子扩到 20 条；
        // 这里放宽到"至少 8 条"守住「够多」，同时不把具体条数焊死（以后还能再扩）
        assertTrue("备选话术太少，实际只有 ${seen.size} 条", seen.size >= 8)
    }

    // ---------------- 对比范围（少爷 2026-09-27 第 4 条）----------------

    @Test
    fun `本机很久以前的票也能撞上 - 不受官方期数限制`() {
        // 少爷明确：只用最新一期做靶子，本机记录有多少期就比多少期。
        // 这里造一张"三年前"的票（期号远早于任何官方接口能返回的范围），照样要命中。
        val old = bet(issue = "2023001", drawDate = "2023-01-02")
        val t = JackpotTease.find(latest, listOf(old), Random(1))
        assertTrue("本机老票必须能撞上，跟官方只给多少期无关", t != null)
        assertEquals("2023001", t!!.pastIssue)
    }

    @Test
    fun `本机多期里任意一期撞上都算`() {
        val t = find(
            bet(issue = "2023001", drawDate = "2023-01-02", main = listOf("01", "02", "03", "04", "05")),
            bet(issue = "2026001", drawDate = "2026-01-02", main = listOf("09", "11", "18", "26", "33"))
        )
        assertTrue(t != null)
        assertEquals("2026001", t!!.pastIssue)
    }

    @Test
    fun `号码行按 - 分隔主区和次区`() {
        val t = find(bet())!!
        assertTrue("要含全部主区号码：${t.numberLine}", t.main.all { t.numberLine.contains(it) })
        assertTrue("要有分隔符", t.numberLine.contains("+"))
    }

    @Test
    fun `相隔天数为 0 时不炸`() {
        // 日期读不出来时 daysBetween = 0，界面会省略那句话
        val t = find(bet(drawDate = "读不出来"))
        assertTrue(t != null)
        assertEquals(0L, t!!.daysBetween)
    }
}
