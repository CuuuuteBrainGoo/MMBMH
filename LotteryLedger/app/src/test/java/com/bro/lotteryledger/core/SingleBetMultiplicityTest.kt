package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「单式多注」vs「复式多注」的判定。
 *
 * 少爷 2026-09-27 报的误报：一张 **5 注单式**的票（每注 6 红 + 1 蓝）被提示
 * 「标为单式但存在多注（5 注），请确认投注方式」，多了一步没必要的确认。
 *
 * 根因是判据用错了：原来判 `总注数 > 1`，但**单式本来就可以多注** ——
 * 每注一组号码，5 注就是 5 注。真正的复式判据是
 * **「某一注自己的号码个数超过单式所需」**。
 *
 * 这两者搞混的代价不只是烦人：如果为了消掉提示而把类型改成「复式」，
 * 注数算法就会从「5 注」变成「按组合数展开」，账目直接错。
 */
class SingleBetMultiplicityTest {

    private fun ssqBet(index: Int, reds: List<String>, blue: String) = Bet(
        index,
        listOf(
            NumberGroup(GroupName.RED, reds),
            NumberGroup(GroupName.BLUE, listOf(blue))
        )
    )

    /** 一张 5 注单式的双色球票：每注 6 红 + 1 蓝，共 5 × 2 = 10 元。 */
    private fun fiveSingleBets() = RawTicket(
        lotteryType = LotteryType.SSQ,
        issue = "2026092",
        drawDate = "2026-08-11",
        purchaseTime = "2026-08-10 17:32:02",
        amountYuan = 10.0,
        bets = listOf(
            ssqBet(1, listOf("01", "04", "06", "17", "26", "27"), "09"),
            ssqBet(2, listOf("02", "05", "11", "19", "24", "31"), "12"),
            ssqBet(3, listOf("03", "08", "13", "16", "22", "30"), "05"),
            ssqBet(4, listOf("07", "10", "14", "20", "28", "33"), "02"),
            ssqBet(5, listOf("09", "12", "15", "21", "25", "29"), "16")
        )
    )

    // ---------- 应该放行：单式多注 ----------

    @Test
    fun `五注单式是正常的 - 一句提示都不该有`() {
        val r = BetRules.validate(fiveSingleBets())
        assertEquals("5 注就是 5 注，不该被展开成组合数", 5, r.effectiveBetCount)
        assertFalse("不该有错误：${r.issues}", r.hasError)
        assertFalse("这是最核心的一条：不能有任何警告：${r.issues}", r.hasWarning)
        assertTrue("整张票应该是干净的", r.ok)
    }

    @Test
    fun `单式多注的提示语不能出现`() {
        val messages = BetRules.validate(fiveSingleBets()).issues.map { it.message }
        assertTrue(
            "不能再出现「标为单式但存在多注」这种把正常票当成异常的说法：$messages",
            messages.none { it.contains("标为单式但存在多注") }
        )
    }

    @Test
    fun `大乐透三注单式同样放行`() {
        val t = RawTicket(
            lotteryType = LotteryType.DLT,
            issue = "26109",
            drawDate = "2026-09-23",
            amountYuan = 6.0,
            bets = listOf(
                Bet(1, listOf(
                    NumberGroup(GroupName.FRONT, listOf("01", "02", "03", "04", "05")),
                    NumberGroup(GroupName.BACK, listOf("01", "02"))
                )),
                Bet(2, listOf(
                    NumberGroup(GroupName.FRONT, listOf("06", "07", "08", "09", "10")),
                    NumberGroup(GroupName.BACK, listOf("03", "04"))
                )),
                Bet(3, listOf(
                    NumberGroup(GroupName.FRONT, listOf("11", "12", "13", "14", "15")),
                    NumberGroup(GroupName.BACK, listOf("05", "06"))
                ))
            )
        )
        val r = BetRules.validate(t)
        assertEquals(3, r.effectiveBetCount)
        assertFalse("不该有任何警告：${r.issues}", r.hasWarning)
    }

    // ---------- 应该报出：复式标成了单式 ----------

    @Test
    fun `一注里红球选八个仍然要问 - 那是真复式`() {
        val t = fiveSingleBets().let { base ->
            base.copy(
                amountYuan = null,
                bets = listOf(
                    ssqBet(1, listOf("01", "02", "03", "04", "05", "06", "07", "08"), "09")
                )
            )
        }
        val r = BetRules.validate(t)
        assertEquals("C(8,6) × C(1,1) = 28", 28, r.effectiveBetCount)
        val betTypeIssue = r.warnings.firstOrNull { it.field == "bet_type" }
        assertTrue("红球 8 个必须提示确认：${r.issues}", betTypeIssue != null)
        // 提示要说清**哪一注、哪个号码组**，否则用户只能对着整张票瞎找
        assertTrue(
            "提示应指出具体位置，实际是：${betTypeIssue!!.message}",
            betTypeIssue.message.contains("第 1 注") && betTypeIssue.message.contains("红球选了 8 个")
        )
    }

    @Test
    fun `一注里蓝球选两个也要问`() {
        val t = fiveSingleBets().let { base ->
            base.copy(
                amountYuan = null,
                bets = listOf(
                    Bet(1, listOf(
                        NumberGroup(GroupName.RED, listOf("01", "02", "03", "04", "05", "06")),
                        NumberGroup(GroupName.BLUE, listOf("09", "11"))
                    ))
                )
            )
        }
        val r = BetRules.validate(t)
        assertEquals("C(6,6) × C(2,1) = 2", 2, r.effectiveBetCount)
        val issue = r.warnings.firstOrNull { it.field == "bet_type" }
        assertTrue("蓝球 2 个必须提示：${r.issues}", issue != null)
        assertTrue("应指出蓝球：${issue!!.message}", issue.message.contains("蓝球选了 2 个"))
    }

    @Test
    fun `已经标成复式的票不再重复问`() {
        val t = fiveSingleBets().let { base ->
            base.copy(
                betType = BetType.MULTIPLE,
                amountYuan = null,
                bets = listOf(
                    ssqBet(1, listOf("01", "02", "03", "04", "05", "06", "07"), "09")
                )
            )
        }
        val r = BetRules.validate(t)
        assertTrue(
            "用户/模型已经认为是复式，就不该再问一遍",
            r.warnings.none { it.field == "bet_type" }
        )
    }

    // ---------- 判据本身（给 inferType 和校验共用） ----------

    @Test
    fun `判据只看单注形态 不看总注数`() {
        val fiveSingles = fiveSingleBets().bets
        assertFalse("5 注单式不是复式", Bets.hasMultiBet(LotteryType.SSQ, fiveSingles))
        assertEquals(
            "inferType 也不该被总注数带偏",
            BetType.SINGLE,
            Bets.inferType(LotteryType.SSQ, fiveSingles, BetType.SINGLE)
        )

        val oneMulti = listOf(
            ssqBet(1, listOf("01", "02", "03", "04", "05", "06", "07"), "09")
        )
        assertTrue("7 红是复式", Bets.hasMultiBet(LotteryType.SSQ, oneMulti))
        assertEquals(
            BetType.MULTIPLE,
            Bets.inferType(LotteryType.SSQ, oneMulti, BetType.SINGLE)
        )
    }

    @Test
    fun `单式形态时没有细节可报`() {
        assertNull(Bets.firstMultiBetDetail(LotteryType.SSQ, fiveSingleBets().bets))
    }
}
