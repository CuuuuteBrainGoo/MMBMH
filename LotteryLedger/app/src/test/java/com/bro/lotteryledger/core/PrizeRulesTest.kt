package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对奖引擎的规则测试。
 *
 * 这些用例逐条对着官方规则写（见 [PrizeRules] 的文档注释），
 * 覆盖**全部 13 个中奖条件**。改动奖金表或奖级映射时，这里必须同步 —— 否则就是算错钱。
 */
class PrizeRulesTest {

    // ---------------- 工具 ----------------

    private fun bet(vararg groups: Pair<GroupName, List<String>>) =
        Bet(index = 1, groups = groups.map { NumberGroup(it.first, it.second) })

    private fun ssqDraw(red: List<String>, blue: String) =
        DrawNumbers(LotteryType.SSQ, "2026107", "2026-09-24", red, listOf(blue))

    private fun dltDraw(front: List<String>, back: List<String>) =
        DrawNumbers(LotteryType.DLT, "26101", "2026-09-05", front, back)

    /** 对单注（单式）判奖并返回最高奖级。 */
    private fun ssqLevel(red: List<String>, blue: String, drawRed: List<String>, drawBlue: String): Int? =
        PrizeRules.judge(
            LotteryType.SSQ,
            listOf(
                bet(
                    GroupName.RED to red,
                    GroupName.BLUE to listOf(blue)
                )
            ),
            ssqDraw(drawRed, drawBlue)
        ).bestLevel

    private fun dltLevel(front: List<String>, back: List<String>, drawFront: List<String>, drawBack: List<String>, poolHigh: Boolean = false): Int? =
        PrizeRules.judge(
            LotteryType.DLT,
            listOf(
                bet(
                    GroupName.FRONT to front,
                    GroupName.BACK to back
                )
            ),
            dltDraw(drawFront, drawBack),
            poolHigh = poolHigh
        ).bestLevel

    private val R = listOf("01", "02", "03", "04", "05", "06")
    private val R7 = listOf("01", "02", "03", "04", "05", "06", "07")
    private val B = "09"

    // ---------------- 双色球 ----------------

    @Test
    fun `双色球 六个奖级全部对得上官方规则`() {
        // 6+1 / 6+0 / 5+1 / 5+0 或 4+1 / 4+0 或 3+1 / 2+1 或 1+1 或 0+1
        assertEquals("6+1 应为一等奖", 1, ssqLevel(R, B, R, B))
        assertEquals("6+0 应为二等奖", 2, ssqLevel(R, "15", R, B))
        assertEquals("5+1 应为三等奖", 3, ssqLevel(listOf("01", "02", "03", "04", "05", "10"), B, R, B))
        assertEquals("5+0 应为四等奖", 4, ssqLevel(listOf("01", "02", "03", "04", "05", "10"), "15", R, B))
        assertEquals("4+1 应为四等奖", 4, ssqLevel(listOf("01", "02", "03", "04", "10", "11"), B, R, B))
        assertEquals("4+0 应为五等奖", 5, ssqLevel(listOf("01", "02", "03", "04", "10", "11"), "15", R, B))
        assertEquals("3+1 应为五等奖", 5, ssqLevel(listOf("01", "02", "03", "10", "11", "12"), B, R, B))
        assertEquals("2+1 应为六等奖", 6, ssqLevel(listOf("01", "02", "10", "11", "12", "13"), B, R, B))
        assertEquals("1+1 应为六等奖", 6, ssqLevel(listOf("01", "10", "11", "12", "13", "14"), B, R, B))
        assertEquals("0+1 应为六等奖", 6, ssqLevel(listOf("10", "11", "12", "13", "14", "15"), B, R, B))
    }

    @Test
    fun `双色球 3+0 不中奖（福运奖只在特别派奖期间存在）`() {
        assertNull(
            "3+0 常规期间不该中奖",
            ssqLevel(listOf("01", "02", "03", "10", "11", "12"), "15", R, B)
        )
    }

    @Test
    fun `双色球固定奖金额与官方一致`() {
        assertEquals(3000.0, PrizeRules.fixedAmount(LotteryType.SSQ, 3)!!, 0.001)
        assertEquals(200.0, PrizeRules.fixedAmount(LotteryType.SSQ, 4)!!, 0.001)
        assertEquals(10.0, PrizeRules.fixedAmount(LotteryType.SSQ, 5)!!, 0.001)
        assertEquals(5.0, PrizeRules.fixedAmount(LotteryType.SSQ, 6)!!, 0.001)
        // 一二等奖是浮动奖，不该有固定金额
        assertNull(PrizeRules.fixedAmount(LotteryType.SSQ, 1))
        assertNull(PrizeRules.fixedAmount(LotteryType.SSQ, 2))
    }

    // ---------------- 大乐透（2026 新规 7 奖级）----------------

    @Test
    fun `大乐透 七个奖级全部对得上新规`() {
        val f = listOf("01", "02", "03", "04", "05")
        val b = listOf("06", "07")

        assertEquals("5+2 一等奖", 1, dltLevel(f, b, f, b))
        assertEquals("5+1 二等奖", 2, dltLevel(f, listOf("06", "12"), f, b))
        assertEquals("5+0 三等奖", 3, dltLevel(f, listOf("11", "12"), f, b))
        assertEquals("4+2 三等奖（新规合并）", 3, dltLevel(listOf("01", "02", "03", "04", "10"), b, f, b))
        assertEquals("4+1 四等奖", 4, dltLevel(listOf("01", "02", "03", "04", "10"), listOf("06", "12"), f, b))
        assertEquals("4+0 五等奖", 5, dltLevel(listOf("01", "02", "03", "04", "10"), listOf("11", "12"), f, b))
        assertEquals("3+2 五等奖（新规合并）", 5, dltLevel(listOf("01", "02", "03", "10", "11"), b, f, b))
        assertEquals("3+1 六等奖", 6, dltLevel(listOf("01", "02", "03", "10", "11"), listOf("06", "12"), f, b))
        assertEquals("2+2 六等奖", 6, dltLevel(listOf("01", "02", "10", "11", "12"), b, f, b))
        assertEquals("3+0 七等奖", 7, dltLevel(listOf("01", "02", "03", "10", "11"), listOf("11", "12"), f, b))
        assertEquals("2+1 七等奖", 7, dltLevel(listOf("01", "02", "10", "11", "12"), listOf("06", "12"), f, b))
        assertEquals("1+2 七等奖", 7, dltLevel(listOf("01", "10", "11", "12", "13"), b, f, b))
        assertEquals("0+2 七等奖", 7, dltLevel(listOf("10", "11", "12", "13", "14"), b, f, b))
    }

    @Test
    fun `大乐透 2+0 不中奖`() {
        assertNull(
            dltLevel(
                listOf("01", "02", "10", "11", "12"),
                listOf("11", "12"),
                listOf("01", "02", "03", "04", "05"),
                listOf("06", "07")
            )
        )
    }

    @Test
    fun `大乐透 13 个中奖条件一个不多一个不少`() {
        // 官方明确「13 个中奖条件不变，奖级由 9 个合并为 7 个」
        val front = listOf("01", "02", "03", "04", "05")
        val back = listOf("06", "07")
        var hit = 0
        for (a in 0..5) {
            for (b in 0..2) {
                val f = buildList {
                    repeat(a) { add("%02d".format(it + 1)) }        // 命中的前区号
                    repeat(5 - a) { add("%02d".format(it + 20)) }   // 未命中的前区号
                }
                val bk = buildList {
                    repeat(b) { add("%02d".format(it + 6)) }
                    repeat(2 - b) { add("%02d".format(it + 9)) }
                }
                if (dltLevel(f, bk, front, back) != null) hit++
            }
        }
        assertEquals("大乐透应有且仅有 13 个中奖组合", 13, hit)
    }

    @Test
    fun `大乐透奖池达到8亿时三至七等奖全部上调`() {
        val low = PrizeRules.fixedAmount(LotteryType.DLT, 3, poolHigh = false)!!
        val high = PrizeRules.fixedAmount(LotteryType.DLT, 3, poolHigh = true)!!
        assertEquals(5000.0, low, 0.001)
        assertEquals(6666.0, high, 0.001)

        assertEquals(300.0, PrizeRules.fixedAmount(LotteryType.DLT, 4, false)!!, 0.001)
        assertEquals(380.0, PrizeRules.fixedAmount(LotteryType.DLT, 4, true)!!, 0.001)

        assertEquals(150.0, PrizeRules.fixedAmount(LotteryType.DLT, 5, false)!!, 0.001)
        assertEquals(200.0, PrizeRules.fixedAmount(LotteryType.DLT, 5, true)!!, 0.001)

        // 少爷关心的六等奖：<8亿 15 元，≥8亿 18 元
        assertEquals(15.0, PrizeRules.fixedAmount(LotteryType.DLT, 6, false)!!, 0.001)
        assertEquals(18.0, PrizeRules.fixedAmount(LotteryType.DLT, 6, true)!!, 0.001)

        assertEquals(5.0, PrizeRules.fixedAmount(LotteryType.DLT, 7, false)!!, 0.001)
        assertEquals(7.0, PrizeRules.fixedAmount(LotteryType.DLT, 7, true)!!, 0.001)
    }

    // ---------------- 复式：组合数必须算准 ----------------

    @Test
    fun `双色球红球7个全中6个 应有1注一等奖加6注三等奖`() {
        // 7 个红球里命中 6 个、蓝球命中 → C(7,6)=7 注
        //   选满 6 个命中 → 1 注 6+1（一等）
        //   选 5 命中 + 1 未命中 → C(6,5)×C(1,1)=6 注 5+1（三等）
        val result = PrizeRules.judge(
            LotteryType.SSQ,
            listOf(bet(GroupName.RED to R7, GroupName.BLUE to listOf(B))),
            ssqDraw(R, B)
        )
        assertEquals("总注数", 7L, result.units)
        val l1 = result.levels.first { it.level == 1 }
        val l3 = result.levels.first { it.level == 3 }
        assertEquals("一等奖注数", 1L, l1.units)
        assertEquals("三等奖注数", 6L, l3.units)
        // 固定奖小计 = 6 × 3000
        assertEquals(18000.0, result.fixedTotalYuan, 0.001)
        assertTrue("含浮动奖，需人工填金额", result.hasFloating)
    }

    @Test
    fun `大乐透前区6中5 后区2中2 应有1注一等奖加5注三等奖`() {
        val front6 = listOf("01", "02", "03", "04", "05", "06")
        val frontDraw = listOf("01", "02", "03", "04", "05")
        val back = listOf("07", "08")
        val result = PrizeRules.judge(
            LotteryType.DLT,
            listOf(bet(GroupName.FRONT to front6, GroupName.BACK to back)),
            dltDraw(frontDraw, back)
        )
        assertEquals("C(6,5)=6 注", 6L, result.units)
        assertEquals(1L, result.levels.first { it.level == 1 }.units)
        assertEquals(5L, result.levels.first { it.level == 3 }.units)
        assertEquals("5 × 5000", 25000.0, result.fixedTotalYuan, 0.001)
    }

    @Test
    fun `多注票的注数会各自累加`() {
        // 两注独立单式，都中六等奖 → 10 注？不，是 2 注
        val r1 = listOf("01", "02", "10", "11", "12", "13")
        val r2 = listOf("01", "03", "10", "11", "12", "13")
        val result = PrizeRules.judge(
            LotteryType.SSQ,
            listOf(
                Bet(1, listOf(NumberGroup(GroupName.RED, r1), NumberGroup(GroupName.BLUE, listOf(B)))),
                Bet(2, listOf(NumberGroup(GroupName.RED, r2), NumberGroup(GroupName.BLUE, listOf(B))))
            ),
            ssqDraw(R, B)
        )
        assertEquals(2L, result.units)
        assertEquals(6, result.bestLevel)
        assertEquals(10.0, result.fixedTotalYuan, 0.001)
    }

    // ---------------- 倍数 / 追加 / 未中奖 ----------------

    @Test
    fun `倍投会把注数和金额一起乘`() {
        val result = PrizeRules.judge(
            LotteryType.SSQ,
            listOf(bet(GroupName.RED to R7, GroupName.BLUE to listOf(B))),
            ssqDraw(R, B),
            multiple = 3
        )
        assertEquals("7 注 × 3 倍 = 21 注", 21L, result.units)
        assertEquals("6 注三等 × 3 倍 × 3000", 54000.0, result.fixedTotalYuan, 0.001)
    }

    @Test
    fun `一注都没中时 won 为 false 且金额为 0`() {
        val result = PrizeRules.judge(
            LotteryType.SSQ,
            listOf(bet(GroupName.RED to listOf("20", "21", "22", "23", "24", "25"), GroupName.BLUE to listOf("16"))),
            ssqDraw(R, B)
        )
        assertFalse(result.won)
        assertEquals(0L, result.units)
        assertEquals(0.0, result.fixedTotalYuan, 0.001)
        assertNull(result.bestLevel)
    }

    @Test
    fun `浮动奖不会用 0 元冒充金额`() {
        // 中一等奖：有一等奖这一级，但金额未知
        val result = PrizeRules.judge(
            LotteryType.SSQ,
            listOf(bet(GroupName.RED to R, GroupName.BLUE to listOf(B))),
            ssqDraw(R, B)
        )
        val top = result.levels.first { it.level == 1 }
        assertNull("一等奖单注金额必须是 null（未知），不能是 0", top.unitYuan)
        assertNull(top.subtotal)
        assertEquals("固定奖合计不含浮动奖", 0.0, result.fixedTotalYuan, 0.001)
        assertTrue(result.hasFloating)
    }

    @Test
    fun `追加投注只对浮动奖标注加成`() {
        val result = PrizeRules.judge(
            LotteryType.DLT,
            listOf(bet(GroupName.FRONT to listOf("01", "02", "03", "04", "05"), GroupName.BACK to listOf("06", "07"))),
            dltDraw(listOf("01", "02", "03", "04", "05"), listOf("06", "07")),
            additional = true
        )
        val top = result.levels.first { it.level == 1 }
        assertTrue("一等奖是浮动奖，追加应标注加成", top.additionalBonus)
    }
}
