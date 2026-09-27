package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投注构成的统计（[BetForm]）。
 *
 * 少爷 2026-09-27 追加的问题：
 * > 大乐透的规则也要一并检查，大乐透单票可打出 10 注或以上的单式票。
 * > 而且大乐透存在组合型票面，既有多注单式，还有复式。
 *
 * 这两句话各自对着一个真实的坑：
 *  1. **10 注单式** —— 票级只判「单式/复式」时没被误报，但界面得能显示「10 注」；
 *  2. **组合票** —— 票级只有「复式」一个词，看不出它其实是单式 + 复式混合。
 */
class BetFormTest {

    private fun dltSingle(index: Int, front: List<String>, back: List<String>) = Bet(
        index,
        listOf(
            NumberGroup(GroupName.FRONT, front),
            NumberGroup(GroupName.BACK, back)
        )
    )

    /** 大乐透一注标准单式：5 前 + 2 后。 */
    private fun dltSingleStandard(index: Int, seed: Int) = dltSingle(
        index,
        listOf(
            "%02d".format(seed), "%02d".format(seed + 1), "%02d".format(seed + 2),
            "%02d".format(seed + 3), "%02d".format(seed + 4)
        ),
        listOf("%02d".format(seed % 11 + 1), "%02d".format(seed % 11 + 2))
    )

    // ---------- 单式多注（大乐透常见：一票 10 注） ----------

    @Test
    fun `大乐透十注单式 - 就是十注单式 不是复式`() {
        val bets = (1..10).map { dltSingleStandard(it, it) }
        val f = BetForm.of(LotteryType.DLT, bets)

        assertEquals(10, f.singleBets)
        assertEquals("单式票不该有复式注", 0, f.multiBets)
        assertEquals(10, f.totalUnits)
        assertEquals(10, f.totalBets)
        assertFalse("不是组合票", f.mixed)
        assertEquals(BetType.SINGLE, f.type)
        assertEquals("单式 10 注", f.label())
    }

    @Test
    fun `大乐透十二注单式同样只是单式`() {
        val bets = (1..12).map { dltSingleStandard(it, it) }
        val f = BetForm.of(LotteryType.DLT, bets)
        assertEquals(12, f.singleBets)
        assertEquals(0, f.multiBets)
        assertEquals(12, f.totalUnits)
        assertEquals(BetType.SINGLE, f.type)
    }

    @Test
    fun `双色球五注单式不受影响`() {
        val bets = (1..5).map { i ->
            Bet(
                i,
                listOf(
                    NumberGroup(
                        GroupName.RED,
                        listOf("01", "04", "06", "17", "26", "27").map { n ->
                            "%02d".format((n.toInt() + i) % 33 + 1)
                        }
                    ),
                    NumberGroup(GroupName.BLUE, listOf("%02d".format(i)))
                )
            )
        }
        val f = BetForm.of(LotteryType.SSQ, bets)
        assertEquals(5, f.singleBets)
        assertEquals(5, f.totalUnits)
        assertEquals(BetType.SINGLE, f.type)
    }

    // ---------- 官方定义的三种复式形式 ----------

    @Test
    fun `大乐透前区复式 六前两后 = 六注`() {
        // 官方：前区复式 = 前区 ≥6 个 + 后区正好 2 个
        val bets = listOf(
            dltSingle(1, listOf("01", "02", "03", "04", "05", "06"), listOf("06", "07"))
        )
        val f = BetForm.of(LotteryType.DLT, bets)
        assertEquals(0, f.singleBets)
        assertEquals(1, f.multiBets)
        assertEquals("C(6,5) × C(2,2) = 6", 6, f.totalUnits)
        assertEquals(BetType.MULTIPLE, f.type)
        assertEquals("复式 1 注，展开 6 注", f.label())
    }

    @Test
    fun `大乐透后区复式 五前三后 = 三注`() {
        // 官方：后区复式 = 前区正好 5 个 + 后区 ≥3 个
        val bets = listOf(
            dltSingle(1, listOf("01", "02", "03", "04", "05"), listOf("06", "07", "08"))
        )
        val f = BetForm.of(LotteryType.DLT, bets)
        assertEquals(1, f.multiBets)
        assertEquals("C(5,5) × C(3,2) = 3", 3, f.totalUnits)
    }

    @Test
    fun `大乐透双区复式 六前三后 = 十八注`() {
        val bets = listOf(
            dltSingle(1, listOf("01", "02", "03", "04", "05", "06"), listOf("06", "07", "08"))
        )
        val f = BetForm.of(LotteryType.DLT, bets)
        assertEquals(1, f.multiBets)
        assertEquals("C(6,5) × C(3,2) = 18", 18, f.totalUnits)
    }

    @Test
    fun `复式上限 十八前十二后 不炸且注数正确`() {
        // 前区 18 个 = C(18,5) = 8568；后区 12 个 = C(12,2) = 66 → 565,488 注
        val front = (1..18).map { "%02d".format(it) }
        val back = (1..12).map { "%02d".format(it) }
        val f = BetForm.of(LotteryType.DLT, listOf(dltSingle(1, front, back)))
        assertEquals(1, f.multiBets)
        assertEquals(8568 * 66, f.totalUnits)
    }

    // ---------- 组合型票面（少爷提的那种） ----------

    @Test
    fun `组合票 - 三注单式加一注复式`() {
        val bets = listOf(
            dltSingleStandard(1, 1),
            dltSingleStandard(2, 7),
            dltSingleStandard(3, 13),
            // 第 4 注前区选 6 个 → 复式，C(6,5) = 6 注
            dltSingle(4, listOf("20", "21", "22", "23", "24", "25"), listOf("01", "02"))
        )
        val f = BetForm.of(LotteryType.DLT, bets)

        assertEquals(3, f.singleBets)
        assertEquals(1, f.multiBets)
        assertEquals(4, f.totalBets)
        assertEquals("3 + 6 = 9", 9, f.totalUnits)
        assertTrue("这就是少爷说的组合型票面", f.mixed)

        // 组合票在票级仍归「复式」（它含复式投注，注数也按组合数算），
        // 但描述里必须把混合说清楚
        assertEquals(BetType.MULTIPLE, f.type)
        assertEquals("组合票 4 注（单式 3 + 复式 1），展开 9 注", f.label())
        assertEquals("组合票 4 注（单式 3 + 复式 1）", f.labelCompact())
    }

    @Test
    fun `组合票 - 多注复式混多注单式`() {
        val bets = listOf(
            dltSingleStandard(1, 1),
            dltSingle(2, listOf("10", "11", "12", "13", "14", "15", "16"), listOf("01", "02")),
            dltSingleStandard(3, 20),
            dltSingle(4, listOf("01", "02", "03", "04", "05"), listOf("03", "04", "05", "06"))
        )
        val f = BetForm.of(LotteryType.DLT, bets)
        // 第 2 注 C(7,5)=21，第 4 注 C(5,5)×C(4,2)=1×6=6
        assertEquals(2, f.singleBets)
        assertEquals(2, f.multiBets)
        assertEquals(1 + 21 + 1 + 6, f.totalUnits)
        assertTrue(f.mixed)
    }

    // ---------- 中间态 ----------

    @Test
    fun `号码没填全的注不算单式`() {
        val bets = listOf(
            dltSingle(1, listOf("01", "02", "03", "04", "05"), listOf("06", "07")),
            dltSingle(2, emptyList(), emptyList())
        )
        val f = BetForm.of(LotteryType.DLT, bets)
        assertEquals("只有一注填好了", 1, f.singleBets)
        assertEquals(0, f.multiBets)
        assertEquals(1, f.emptyBets)
        assertEquals(1, f.totalBets)
        assertEquals(1, f.totalUnits)
        assertTrue("要说清还有没填的：${f.label()}", f.label().contains("另有 1 注号码未填"))
    }

    @Test
    fun `一注都没填时不要谎报注数`() {
        val f = BetForm.of(LotteryType.DLT, listOf(dltSingle(1, emptyList(), emptyList())))
        assertEquals(0, f.singleBets)
        assertEquals(0, f.totalUnits)
        assertEquals("号码还没填完", f.label())
    }

    @Test
    fun `没有投注内容`() {
        val f = BetForm.of(LotteryType.DLT, emptyList())
        assertFalse(f.hasAny)
        assertEquals("没有投注内容", f.label())
    }

    // ---------- 两条统计路径必须一致 ----------

    @Test
    fun `从每注组合数统计 与 从号码统计 结果一致`() {
        // of() 走号码计算；fromPerBetUnits() 走库里的 units 字段。
        // 两者分家就会出现「核对页说 9 注、详情页说别的」这种怪事。
        val bets = listOf(
            dltSingleStandard(1, 1),
            dltSingleStandard(2, 7),
            dltSingleStandard(3, 13),
            dltSingle(4, listOf("20", "21", "22", "23", "24", "25"), listOf("01", "02"))
        )
        val fromBets = BetForm.of(LotteryType.DLT, bets)

        val perBetUnits = listOf(1, 1, 1, 6)
        val fromUnits = BetForm.fromPerBetUnits(perBetUnits)

        assertEquals(fromBets.singleBets, fromUnits.singleBets)
        assertEquals(fromBets.multiBets, fromUnits.multiBets)
        assertEquals(fromBets.totalUnits, fromUnits.totalUnits)
        assertEquals(fromBets.label(), fromUnits.label())
    }

    @Test
    fun `从每注组合数统计 - 空店按未填算`() {
        val f = BetForm.fromPerBetUnits(listOf(1, 1, 6, 0))
        assertEquals(2, f.singleBets)
        assertEquals(1, f.multiBets)
        assertEquals(1, f.emptyBets)
        assertEquals(8, f.totalUnits)
    }
}
