package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投注注的构造 / 维护 / 单式复式判定。
 *
 * ## 为什么这个判定的测试重要
 *
 * 「单式还是复式」直接决定：
 *  - **有效注数**（组合数）→ 决定花了多少钱
 *  - **对奖命中的注数** → 决定该拿多少奖金
 *
 * 判错一个，账目就是错的。而它又是用户看不见的自动行为
 * （用户只管点号码，方式由程序判），所以只能靠测试兜着。
 */
class BetsTest {

    private fun bet(index: Int, type: LotteryType, main: List<String>, second: List<String>): Bet {
        val s = LotterySpecs.of(type)
        return Bet(
            index = index,
            groups = listOf(
                NumberGroup(s.groupName, main),
                NumberGroup(s.secondName, second)
            )
        )
    }

    private fun nums(range: IntRange) = range.map { "%02d".format(it) }

    // ---------------- blank：组名必须跟着彩种走 ----------------

    @Test
    fun `空白注-双色球的组是红球蓝球`() {
        val b = Bets.blank(LotteryType.SSQ, 1)
        assertEquals(2, b.groups.size)
        assertTrue("应有红球组", b.groups.any { it.name == GroupName.RED })
        assertTrue("应有蓝球组", b.groups.any { it.name == GroupName.BLUE })
        assertTrue("号码应为空", b.groups.all { it.numbers.isEmpty() })
    }

    @Test
    fun `空白注-大乐透的组是前区后区`() {
        val b = Bets.blank(LotteryType.DLT, 1)
        assertTrue("应有前区组", b.groups.any { it.name == GroupName.FRONT })
        assertTrue("应有后区组", b.groups.any { it.name == GroupName.BACK })
        assertTrue(
            "不该混进红球/蓝球组 —— 这是换彩种时号码串味的根源",
            b.groups.none { it.name == GroupName.RED || it.name == GroupName.BLUE }
        )
    }

    @Test
    fun `空白注-带上正确的注号`() {
        assertEquals(3, Bets.blank(LotteryType.SSQ, 3).index)
    }

    // ---------------- reindex：删注后不能跳号 ----------------

    @Test
    fun `重排注号-从1开始且连续`() {
        val list = listOf(
            bet(1, LotteryType.SSQ, nums(1..6), nums(1..1)),
            bet(3, LotteryType.SSQ, nums(7..12), nums(2..2)),
            bet(9, LotteryType.SSQ, nums(13..18), nums(3..3))
        )
        val out = Bets.reindex(list)
        assertEquals(listOf(1, 2, 3), out.map { it.index })
        // 号码不能被动到
        assertEquals(nums(13..18), out[2].groups.first { it.name == GroupName.RED }.numbers)
    }

    @Test
    fun `重排注号-空列表也安全`() {
        assertTrue(Bets.reindex(emptyList()).isEmpty())
    }

    // ---------------- inferType：双色球 ----------------

    @Test
    fun `双色球-6红1蓝-是单式`() {
        val bets = listOf(bet(1, LotteryType.SSQ, nums(1..6), nums(1..1)))
        assertEquals(BetType.SINGLE, Bets.inferType(LotteryType.SSQ, bets))
    }

    @Test
    fun `双色球-7红1蓝-是复式`() {
        val bets = listOf(bet(1, LotteryType.SSQ, nums(1..7), nums(1..1)))
        assertEquals(BetType.MULTIPLE, Bets.inferType(LotteryType.SSQ, bets))
    }

    @Test
    fun `双色球-6红2蓝-也是复式`() {
        // 复式不一定在多选红球，蓝球多选同样是复式
        val bets = listOf(bet(1, LotteryType.SSQ, nums(1..6), nums(1..2)))
        assertEquals(BetType.MULTIPLE, Bets.inferType(LotteryType.SSQ, bets))
    }

    @Test
    fun `双色球-多注但每注都是单式-仍是单式`() {
        // 「5 张单式票」不是复式，注入数多不代表复式
        val bets = listOf(
            bet(1, LotteryType.SSQ, nums(1..6), nums(1..1)),
            bet(2, LotteryType.SSQ, nums(7..12), nums(2..2)),
            bet(3, LotteryType.SSQ, nums(13..18), nums(3..3))
        )
        assertEquals(BetType.SINGLE, Bets.inferType(LotteryType.SSQ, bets))
    }

    @Test
    fun `双色球-只有其中一注是复式-整体判为复式`() {
        val bets = listOf(
            bet(1, LotteryType.SSQ, nums(1..6), nums(1..1)),
            bet(2, LotteryType.SSQ, nums(7..13), nums(2..2))   // 7 个红球
        )
        assertEquals(BetType.MULTIPLE, Bets.inferType(LotteryType.SSQ, bets))
    }

    // ---------------- inferType：大乐透 ----------------

    @Test
    fun `大乐透-5前2后-是单式`() {
        val bets = listOf(bet(1, LotteryType.DLT, nums(1..5), nums(1..2)))
        assertEquals(BetType.SINGLE, Bets.inferType(LotteryType.DLT, bets))
    }

    @Test
    fun `大乐透-6前2后-是复式`() {
        val bets = listOf(bet(1, LotteryType.DLT, nums(1..6), nums(1..2)))
        assertEquals(BetType.MULTIPLE, Bets.inferType(LotteryType.DLT, bets))
    }

    @Test
    fun `大乐透-5前3后-是复式`() {
        val bets = listOf(bet(1, LotteryType.DLT, nums(1..5), nums(1..3)))
        assertEquals(BetType.MULTIPLE, Bets.inferType(LotteryType.DLT, bets))
    }

    // ---------------- inferType：不降级 ----------------

    @Test
    fun `已标复式但号码看起来像单式-保持复式不降级`() {
        // 这是**故意**的：复式降成单式可能是「模型漏读了一个号码」，
        // 自动降级会把真问题悄悄掩盖掉。要让用户自己判断。
        val bets = listOf(bet(1, LotteryType.SSQ, nums(1..6), nums(1..1)))
        assertEquals(
            BetType.MULTIPLE,
            Bets.inferType(LotteryType.SSQ, bets, current = BetType.MULTIPLE)
        )
    }

    @Test
    fun `号码为空-保持原样`() {
        val bets = listOf(Bets.blank(LotteryType.SSQ, 1))
        assertEquals(BetType.SINGLE, Bets.inferType(LotteryType.SSQ, bets))
        assertEquals(
            BetType.MULTIPLE,
            Bets.inferType(LotteryType.SSQ, bets, current = BetType.MULTIPLE)
        )
    }

    @Test
    fun `没有注-保持原样`() {
        assertEquals(BetType.SINGLE, Bets.inferType(LotteryType.SSQ, emptyList()))
    }

    // ---------------- 与注数推算对上（端到端一致性） ----------------

    @Test
    fun `判为复式时-有效注数应大于1`() {
        // 把「方式判定」和「注数推算」放在一起对一遍：
        // 如果哪天有人改了 inferType 的口径，这里会立刻发现不一致
        val dlt = listOf(bet(1, LotteryType.DLT, nums(1..6), nums(1..3)))
        val ticket = RawTicket(
            lotteryType = LotteryType.DLT,
            issue = "26109",
            drawDate = "2026-09-26",
            betType = Bets.inferType(LotteryType.DLT, dlt),
            bets = dlt
        )
        val v = BetRules.validate(ticket)
        assertEquals(BetType.MULTIPLE, ticket.betType)
        assertEquals("6 前 3 后 = C(6,5)×C(3,2) = 18 注", 18, v.effectiveBetCount)
        assertTrue("不该有「标为单式但多注」的警告", v.warnings.none { it.field == "bet_type" })
    }

    @Test
    fun `手工录一张完整票-校验应全通过`() {
        // 手工录入的典型场景：用户自己填全，不该有任何 ERROR
        val ticket = RawTicket(
            lotteryType = LotteryType.SSQ,
            issue = "2026111",
            drawDate = "2026-09-24",
            purchaseTime = "2026-09-24 19:03",
            betType = BetType.MULTIPLE,
            multiple = 2,
            additional = false,
            amountYuan = 56.0,   // 7 红 1 蓝 = 7 注 × 2 元 × 2 倍 = 28… 见下
            bets = listOf(
                bet(1, LotteryType.SSQ, nums(1..7), nums(1..1)),
                bet(2, LotteryType.SSQ, nums(8..14), nums(2..2))
            )
        )
        val v = BetRules.validate(ticket)
        // 7 注 + 7 注 = 14 注，× 2 元 × 2 倍 = 56 元
        assertEquals(14, v.effectiveBetCount)
        assertTrue("这个金额是算对的，不该报警", v.warnings.none { it.field == "amount_yuan" })
        assertTrue("不该有 ERROR：${v.errors.map { it.message }}", !v.hasError)
    }
}
