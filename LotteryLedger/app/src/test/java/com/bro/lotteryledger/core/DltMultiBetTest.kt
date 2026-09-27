package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大乐透的多注 / 组合票 —— 校验、对奖、金额、冲突文案四条链路。
 *
 * 少爷 2026-09-27：
 * > 大乐透的规则也要一并检查，大乐透单票可打出 10 注或以上的单式票。
 * > 而且大乐透存在组合型票面，既有多注单式，还有复式。
 * > 不过我手头没有那么多大乐透彩票给你测试。
 *
 * 手头没票不代表不能测 —— 用构造数据把边界钉死。
 *
 * ## 官方规则依据（体彩《超级大乐透游戏规则》）
 * ```
 * 基本投注  前区 5 个 + 后区 2 个，每注 2 元；追加每注 +1 元
 * 前区复式  前区 ≥6 个 + 后区 正好 2 个
 * 后区复式  前区 正好 5 个 + 后区 ≥3 个
 * 双区复式  前区 ≥6 个 + 后区 ≥3 个
 * 多倍投注  2–99 倍
 * 单张限额  基本投注 ≤20000 元；基本+追加 ≤30000 元
 * ```
 * **注数没有比金额限额更小的天花板**（20000 ÷ 2 = 10000 注），
 * 所以任何地方都不能写「最多 N 注」的假设。这些用例就是在守这条。
 */
class DltMultiBetTest {

    private fun bet(index: Int, front: List<String>, back: List<String>) =
        Bet(index, listOf(NumberGroup(GroupName.FRONT, front), NumberGroup(GroupName.BACK, back)))

    /** 不中奖的填充注：前区 20-24，后区 01-02。 */
    private fun dud(index: Int) = bet(
        index,
        listOf("20", "21", "22", "23", "24"),
        listOf("01", "02")
    )

    private fun ticket(amountYuan: Double?, bets: List<Bet>, betType: BetType = BetType.SINGLE) = RawTicket(
        lotteryType = LotteryType.DLT,
        issue = "26110",
        drawDate = "2026-09-26",
        purchaseTime = "2026-09-26 18:30:00",
        betType = betType,
        amountYuan = amountYuan,
        bets = bets
    )

    /** 开奖：前区 01-05，后区 06 07。 */
    private val draw = DrawNumbers(
        type = LotteryType.DLT,
        issue = "26110",
        drawDate = "2026-09-26",
        main = listOf("01", "02", "03", "04", "05"),
        second = listOf("06", "07")
    )

    /** 一等奖那一注：前 01-05 + 后 06 07。 */
    private fun jackpotBet(index: Int) =
        bet(index, listOf("01", "02", "03", "04", "05"), listOf("06", "07"))

    // ================= 校验：多注单式不能被误报 =================

    @Test
    fun `大乐透十注单式 - 一句警告都不该有`() {
        val bets = (1..10).map { if (it == 1) jackpotBet(1) else dud(it) }
        val r = BetRules.validate(ticket(20.0, bets))

        assertEquals("10 注 × 2 元 = 20 元", 10, r.effectiveBetCount)
        assertFalse("不该有错误：${r.issues}", r.hasError)
        assertFalse(
            "10 注单式是正常的，不该有任何警告：${r.issues.map { it.message }}",
            r.hasWarning
        )
        assertTrue(r.ok)
    }

    @Test
    fun `大乐透十二注单式同样放行`() {
        val bets = (1..12).map { if (it == 1) jackpotBet(1) else dud(it) }
        val r = BetRules.validate(ticket(24.0, bets))
        assertEquals(12, r.effectiveBetCount)
        assertTrue("12 注单式也正常：${r.issues}", r.ok)
    }

    @Test
    fun `判据只看单注形态 - 十注不会被当成复式`() {
        val bets = (1..10).map { if (it == 1) jackpotBet(1) else dud(it) }
        assertFalse(Bets.hasMultiBet(LotteryType.DLT, bets))
        assertEquals(BetType.SINGLE, Bets.inferType(LotteryType.DLT, bets, BetType.SINGLE))
    }

    // ================= 校验：组合票该提示的是什么 =================

    /** 组合票：3 注单式 + 1 注前区复式（6 前 2 后 = 6 注），共 9 注 18 元。 */
    private fun comboBets() = listOf(
        jackpotBet(1),
        dud(2),
        dud(3),
        bet(4, listOf("01", "02", "03", "04", "05", "06"), listOf("06", "07"))
    )

    @Test
    fun `组合票 - 标为单式时要提示 且指出是哪一注`() {
        val r = BetRules.validate(ticket(18.0, comboBets()))
        assertEquals("3 注单式 + 6 注复式展开 = 9", 9, r.effectiveBetCount)

        val issue = r.warnings.firstOrNull { it.field == "bet_type" }
        assertTrue("组合票含复式，标成单式该提示：${r.issues}", issue != null)
        assertTrue(
            "必须指出具体哪一注哪个区，实际：${issue!!.message}",
            issue.message.contains("第 4 注") && issue.message.contains("前区选了 6 个")
        )
    }

    @Test
    fun `组合票 - 标为复式时不再问`() {
        val r = BetRules.validate(ticket(18.0, comboBets(), betType = BetType.MULTIPLE))
        assertTrue(
            "已确认是复式就不该再问：${r.warnings.map { it.message }}",
            r.warnings.none { it.field == "bet_type" }
        )
    }

    @Test
    fun `组合票 - 金额核对把单式和复式一起算`() {
        // 9 注 × 2 元 = 18 元；给 18 元就不该说「金额与推算不符」
        val ok = BetRules.validate(ticket(18.0, comboBets()))
        assertTrue(
            "金额 18 元就该匹配 9 注：${ok.warnings.map { it.message }}",
            ok.warnings.none { it.field == "amount_yuan" }
        )

        // 给错金额（按 4 注算的 8 元）必须报出来
        val bad = BetRules.validate(ticket(8.0, comboBets()))
        assertTrue(
            "金额对不上必须提示",
            bad.warnings.any { it.field == "amount_yuan" }
        )
    }

    @Test
    fun `前区复式单独存在时也要提示`() {
        val bets = listOf(
            bet(1, listOf("01", "02", "03", "04", "05", "06"), listOf("06", "07"))
        )
        val r = BetRules.validate(ticket(12.0, bets))
        assertEquals(6, r.effectiveBetCount)
        assertTrue(r.warnings.any { it.field == "bet_type" })
    }

    @Test
    fun `后区复式的提示要说后区`() {
        val bets = listOf(
            bet(1, listOf("01", "02", "03", "04", "05"), listOf("06", "07", "08"))
        )
        val r = BetRules.validate(ticket(6.0, bets))
        assertEquals(3, r.effectiveBetCount)
        val issue = r.warnings.first { it.field == "bet_type" }
        assertTrue("该指出后区：${issue.message}", issue.message.contains("后区选了 3 个"))
    }

    // ================= 对奖：组合票每一注都要参与 =================

    @Test
    fun `组合票对奖 - 单式注和复式注都算进去`() {
        val r = PrizeRules.judge(LotteryType.DLT, comboBets(), draw)

        // 第 1 注（单式）：前 5 + 后 2 → 一等奖 1 注
        // 第 4 注（复式 6 前 2 后）展开 6 注：
        //   含 01-05 的那 1 注 → 一等奖；把 06 顶掉一个的 5 注 → 4+2 三等奖
        // 第 2、3 注：不中
        assertEquals("1（第1注）+ 1（第4注展开）= 2 注一等；再加 5 注三等 = 7", 7L, r.units)

        val first = r.levels.first { it.level == 1 }
        assertEquals("两个一等奖：单式那注 + 复式展开里含全 5 个前区的那注", 2L, first.units)

        val third = r.levels.first { it.level == 3 }
        assertEquals("4 前 + 2 后 是三等奖，只有复式那注展开出来的 5 注", 5L, third.units)

        assertTrue("含一等奖 → 需要人工确认金额", r.hasFloating)
        assertEquals(1, r.bestLevel)
    }

    @Test
    fun `十注单式对奖 - 中几注就是几注`() {
        val bets = (1..10).map { if (it == 1) jackpotBet(1) else dud(it) }
        val r = PrizeRules.judge(LotteryType.DLT, bets, draw)
        assertEquals(1L, r.units)
        assertEquals(1, r.bestLevel)
    }

    @Test
    fun `十注单式对奖 - 两注中奖分别统计`() {
        val bets = listOf(
            jackpotBet(1),
            // 4 前 + 2 后 → 三等奖
            bet(2, listOf("01", "02", "03", "04", "30"), listOf("06", "07"))
        ) + (3..10).map { dud(it) }

        val r = PrizeRules.judge(LotteryType.DLT, bets, draw)
        assertEquals(1L, r.levels.first { it.level == 1 }.units)
        assertEquals(1L, r.levels.first { it.level == 3 }.units)
        assertEquals(2L, r.units)
    }

    @Test
    fun `后区复式对奖 - 一等一注 二等两注`() {
        val bets = listOf(bet(1, listOf("01", "02", "03", "04", "05"), listOf("06", "07", "08")))
        val r = PrizeRules.judge(LotteryType.DLT, bets, draw)

        assertEquals("C(3,2) = 3 注", 3L, r.units)
        assertEquals(1L, r.levels.first { it.level == 1 }.units)
        assertEquals("后区挑中 06 和 07 里只含一个的有 2 注", 2L, r.levels.first { it.level == 2 }.units)
        assertTrue("一二等奖都是浮动奖", r.hasFloating)
        assertEquals("浮动奖不计入固定奖合计", 0.0, r.fixedTotalYuan, 0.001)
    }

    @Test
    fun `倍数对所有奖级整体生效`() {
        val bets = listOf(jackpotBet(1))
        val one = PrizeRules.judge(LotteryType.DLT, bets, draw, multiple = 1)
        val five = PrizeRules.judge(LotteryType.DLT, bets, draw, multiple = 5)
        assertEquals(one.units * 5, five.units)
    }

    @Test
    fun `十注全部不中 - units 为 0 而不是报错`() {
        val bets = (1..10).map { dud(it) }
        val r = PrizeRules.judge(LotteryType.DLT, bets, draw)
        assertFalse(r.won)
        assertEquals(0L, r.units)
    }

    // ================= 金额交叉验证 =================

    @Test
    fun `大乐透注数与金额的换算`() {
        assertEquals("10 注基本投注 = 20 元", 20.0, BetRules.expectedAmount(LotteryType.DLT, 10, 1, false), 0.001)
        assertEquals("10 注含追加 = 30 元", 30.0, BetRules.expectedAmount(LotteryType.DLT, 10, 1, true), 0.001)
        assertEquals("组合票 9 注 = 18 元", 18.0, BetRules.expectedAmount(LotteryType.DLT, 9, 1, false), 0.001)
    }

    @Test
    fun `从金额反推注数 - 组合同样算得出`() {
        assertEquals(10, TicketCrossCheck.estimateUnits(LotteryType.DLT, 20.0, 1, false))
        assertEquals(10, TicketCrossCheck.estimateUnits(LotteryType.DLT, 30.0, 1, true))
        assertEquals("组合票 18 元 → 9 注", 9, TicketCrossCheck.estimateUnits(LotteryType.DLT, 18.0, 1, false))
        assertEquals("2 倍投注", 5, TicketCrossCheck.estimateUnits(LotteryType.DLT, 20.0, 2, false))
    }

    @Test
    fun `大额单票也能算 - 一百注`() {
        assertEquals(200.0, BetRules.expectedAmount(LotteryType.DLT, 100, 1, false), 0.001)
        assertEquals(100, TicketCrossCheck.estimateUnits(LotteryType.DLT, 200.0, 1, false))
    }

    // ================= 冲突文案：号码组名不能漏英文 =================

    @Test
    fun `大乐透多注冲突要翻成中文 不能出现 front back`() {
        // TicketDiffer 产出的路径用的是号码组 id（front/back），
        // 以前 ConflictText 只认 main/second，会原样吐出「第 1 注 front」
        val a = ConflictText.label("bets[0].front", LotteryType.DLT)
        assertEquals("第 1 注 前区", a)

        val b = ConflictText.label("bets[9].back", LotteryType.DLT)
        assertEquals("下标从 1 数起，bets[9] 是第 10 注", "第 10 注 后区", b)
    }

    @Test
    fun `双色球的号码组名也要翻`() {
        assertEquals("第 4 注 红球", ConflictText.label("bet[3].red", LotteryType.SSQ))
        assertEquals("第 1 注 蓝球", ConflictText.label("bets[0].blue", LotteryType.SSQ))
    }

    @Test
    fun `冲突文案里不该残留任何内部字段名`() {
        val paths = listOf(
            "bets[0].front" to LotteryType.DLT,
            "bets[0].back" to LotteryType.DLT,
            "bet[1].red" to LotteryType.SSQ,
            "bet[1].blue" to LotteryType.SSQ,
            "bet[1].main" to LotteryType.DLT,
            "bet[1].second" to LotteryType.DLT,
            "bets[0].groups" to LotteryType.DLT,
            "bets[0].groups[0].numbers" to LotteryType.DLT
        )
        val leaked = paths.map { (p, t) -> p to ConflictText.label(p, t) }
            .filter { (_, label) ->
                listOf("front", "back", "red", "blue", "main", "second", "groups")
                    .any { label.contains(it) }
            }
        assertTrue("这些路径的人话里还残留英文内部字段名：$leaked", leaked.isEmpty())
    }
}
