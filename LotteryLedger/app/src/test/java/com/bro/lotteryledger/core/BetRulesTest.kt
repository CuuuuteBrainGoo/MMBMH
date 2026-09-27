package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则引擎与指纹的核心断言。
 * 这些测试锁死交接稿里最不能违背的行为（§6.1 / §6.4 / §7.4 / §23）。
 */
class BetRulesTest {

    private fun ssqSingle() = RawTicket(
        lotteryType = LotteryType.SSQ,
        issue = "2026111",
        drawDate = "2026-09-24",
        purchaseTime = "2026-09-24 13:54:02",
        amountYuan = 2.0,
        bets = listOf(
            Bet(1, listOf(
                NumberGroup(GroupName.RED, listOf("02", "07", "17", "18", "22", "26")),
                NumberGroup(GroupName.BLUE, listOf("13"))
            ))
        )
    )

    // ---------- 单式 ----------

    @Test
    fun `双色球单式合法`() {
        val r = BetRules.validate(ssqSingle())
        assertEquals(1, r.effectiveBetCount)
        assertFalse("不应有错误: ${r.issues}", r.hasError)
    }

    // ---------- 复式注数（§7.4）----------

    @Test
    fun `大乐透复式 前区6后区2 应为6注`() {
        // C(6,5) * C(2,2) = 6 * 1 = 6
        val t = RawTicket(
            lotteryType = LotteryType.DLT, issue = "26109", drawDate = "2026-09-23",
            betType = BetType.MULTIPLE,
            bets = listOf(
                Bet(1, listOf(
                    NumberGroup(GroupName.FRONT, listOf("01", "02", "03", "04", "05", "06")),
                    NumberGroup(GroupName.BACK, listOf("01", "02"))
                ))
            )
        )
        val r = BetRules.validate(t)
        assertEquals(6, r.effectiveBetCount)
    }

    @Test
    fun `大乐透复式 前区6后区3 应为18注`() {
        // C(6,5) * C(3,2) = 6 * 3 = 18
        val t = RawTicket(
            lotteryType = LotteryType.DLT, issue = "26109", drawDate = "2026-09-23",
            betType = BetType.MULTIPLE,
            bets = listOf(
                Bet(1, listOf(
                    NumberGroup(GroupName.FRONT, listOf("01", "02", "03", "04", "05", "06")),
                    NumberGroup(GroupName.BACK, listOf("01", "02", "03"))
                ))
            )
        )
        val r = BetRules.validate(t)
        assertEquals(18, r.effectiveBetCount)
    }

    @Test
    fun `双色球复式 红7蓝2 应为14注`() {
        // C(7,6) * C(2,1) = 7 * 2 = 14
        val t = RawTicket(
            lotteryType = LotteryType.SSQ, issue = "2026111", drawDate = "2026-09-24",
            betType = BetType.MULTIPLE,
            bets = listOf(
                Bet(1, listOf(
                    NumberGroup(GroupName.RED, listOf("01", "02", "03", "04", "05", "06", "07")),
                    NumberGroup(GroupName.BLUE, listOf("01", "02"))
                ))
            )
        )
        val r = BetRules.validate(t)
        assertEquals(14, r.effectiveBetCount)
    }

    // ---------- 号码格式（§2.3）----------

    @Test
    fun `一位数字母号应报错而不是被自动补零`() {
        val t = ssqSingle().let { base ->
            base.copy(bets = listOf(
                Bet(1, listOf(
                    NumberGroup(GroupName.RED, listOf("2", "07", "17", "18", "22", "26")),
                    NumberGroup(GroupName.BLUE, listOf("13"))
                ))
            ))
        }
        val r = BetRules.validate(t)
        assertTrue("一位数字应被判非法", r.hasError)
        assertNull("不应自动补零后通过", r.effectiveBetCount.takeIf { it > 0 && !r.hasError })
    }

    @Test
    fun `红球超出33应报错`() {
        val t = ssqSingle().let { base ->
            base.copy(bets = listOf(
                Bet(1, listOf(
                    NumberGroup(GroupName.RED, listOf("34", "07", "17", "18", "22", "26")),
                    NumberGroup(GroupName.BLUE, listOf("13"))
                ))
            ))
        }
        assertTrue(BetRules.validate(t).hasError)
    }

    @Test
    fun `蓝球超出16应报错`() {
        val t = ssqSingle().let { base ->
            base.copy(bets = listOf(
                Bet(1, listOf(
                    NumberGroup(GroupName.RED, listOf("02", "07", "17", "18", "22", "26")),
                    NumberGroup(GroupName.BLUE, listOf("17"))
                ))
            ))
        }
        assertTrue(BetRules.validate(t).hasError)
    }

    @Test
    fun `候选号码存在时必须要求人工确认`() {
        val t = ssqSingle().let { base ->
            base.copy(bets = listOf(
                Bet(1, listOf(
                    NumberGroup(
                        GroupName.RED,
                        listOf("02", "07", "17", "18", "22", "26"),
                        candidates = listOf(listOf("03"), listOf("08"))
                    ),
                    NumberGroup(GroupName.BLUE, listOf("13"))
                ))
            ))
        }
        val r = BetRules.validate(t)
        assertTrue("有候选必须报错要求确认", r.hasError)
    }

    // ---------- 金额（§7.3）----------

    @Test
    fun `单式一倍双色球金额应为2元`() {
        assertEquals(2.0, BetRules.expectedAmount(LotteryType.SSQ, 1, 1, false), 0.001)
    }

    @Test
    fun `单式一倍大乐透追加应为3元`() {
        assertEquals(3.0, BetRules.expectedAmount(LotteryType.DLT, 1, 1, true), 0.001)
    }

    @Test
    fun `大乐透5注2倍追加应为30元`() {
        // (2+1) * 5 * 2 = 30
        assertEquals(30.0, BetRules.expectedAmount(LotteryType.DLT, 5, 2, true), 0.001)
    }

    @Test
    fun `复式18注一倍不追加应为36元`() {
        assertEquals(36.0, BetRules.expectedAmount(LotteryType.DLT, 18, 1, false), 0.001)
    }

    @Test
    fun `金额不符应产生warning而非error`() {
        val t = ssqSingle().copy(amountYuan = 999.0)
        val r = BetRules.validate(t)
        assertFalse("金额不符不应阻断入账", r.hasError)
        assertTrue("应提示金额异常", r.hasWarning)
    }

    @Test
    fun `双色球不应允许追加`() {
        val t = ssqSingle().copy(additional = true)
        assertTrue(BetRules.validate(t).hasError)
    }

    // ---------- 缺失关键字段 ----------

    @Test
    fun `缺少开奖日期必须报错（对奖依赖它）`() {
        val t = ssqSingle().copy(drawDate = null)
        assertTrue(BetRules.validate(t).hasError)
    }

    @Test
    fun `缺少彩种必须报错`() {
        val t = ssqSingle().copy(lotteryType = null)
        assertTrue(BetRules.validate(t).hasError)
    }

    @Test
    fun `期号格式异常应报错`() {
        val t = ssqSingle().copy(issue = "abc")
        assertTrue(BetRules.validate(t).hasError)
    }

    // ---------- 组合数 ----------

    @Test
    fun `组合数计算正确`() {
        assertEquals(6L, Combinatorics.c(6, 5))
        assertEquals(1L, Combinatorics.c(2, 2))
        assertEquals(3L, Combinatorics.c(3, 2))
        assertEquals(7L, Combinatorics.c(7, 6))
        assertEquals(0L, Combinatorics.c(3, 5))
        assertEquals(1L, Combinatorics.c(5, 5))
    }
}
