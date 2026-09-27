package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用票面金额交叉验证注数（少爷 2026-09-27：「只有出现演算异常时才应该确认」）。
 *
 * 这个模块的价值在于**减少打扰**：以前两个模型只要注数不一致就弹确认框，
 * 而少爷反馈「感觉大部分需要确认的都是这个」。
 *
 * 反过来，如果这个验证**判错**，后果是「该确认的没确认」——
 * 错误数据被直接入账。所以两个方向都要钉住。
 */
class TicketCrossCheckTest {

    // ---------------- 反推注数 ----------------

    @Test
    fun `少爷给的例子 - 20 元 2 倍 = 5 注`() {
        assertEquals(
            5,
            TicketCrossCheck.estimateUnits(LotteryType.SSQ, 20.0, multiple = 2, additional = false)
        )
    }

    @Test
    fun `单倍 - 10 元 = 5 注`() {
        assertEquals(
            5,
            TicketCrossCheck.estimateUnits(LotteryType.SSQ, 10.0, multiple = 1, additional = false)
        )
    }

    @Test
    fun `大乐透追加 - 每注 3 元`() {
        // 15 元 ÷ (2 + 1) = 5 注
        assertEquals(
            5,
            TicketCrossCheck.estimateUnits(LotteryType.DLT, 15.0, multiple = 1, additional = true)
        )
    }

    @Test
    fun `双色球没有追加 - 即使标了追加也按 2 元算`() {
        // 规则引擎里只有 DLT 才加价，这里必须和它一致
        assertEquals(
            5,
            TicketCrossCheck.estimateUnits(LotteryType.SSQ, 10.0, multiple = 1, additional = true)
        )
    }

    @Test
    fun `金额与倍数对不上 - 返回 null 不做仲裁`() {
        // 19 元 / 2 元 = 9.5 注 —— 不是整数，说明金额读错了，不能拿来仲裁
        assertNull(TicketCrossCheck.estimateUnits(LotteryType.SSQ, 19.0, 1, false))
        assertEquals(5, TicketCrossCheck.estimateUnits(LotteryType.SSQ, 10.0, 1, false))
    }

    @Test
    fun `异常输入 - 一律返回 null`() {
        assertNull(TicketCrossCheck.estimateUnits(LotteryType.SSQ, null, 1, false))
        assertNull(TicketCrossCheck.estimateUnits(LotteryType.SSQ, 0.0, 1, false))
        assertNull(TicketCrossCheck.estimateUnits(LotteryType.SSQ, -10.0, 1, false))
        // 倍数非法时按 1 倍处理，而不是除以 0
        assertEquals(5, TicketCrossCheck.estimateUnits(LotteryType.SSQ, 10.0, 0, false))
    }

    @Test
    fun `彩种未知时按基本价 2 元算`() {
        assertEquals(5, TicketCrossCheck.estimateUnits(null, 10.0, 1, false))
    }

    @Test
    fun `大复式也能反推`() {
        // 6 前区 3 后区 = C(6,5)×C(3,2) = 6×3 = 18 注 → 36 元
        assertEquals(
            18,
            TicketCrossCheck.estimateUnits(LotteryType.DLT, 36.0, multiple = 1, additional = false)
        )
    }

    // ---------------- 仲裁 ----------------

    @Test
    fun `两边注数一致 - 无需仲裁`() {
        assertEquals(
            TicketCrossCheck.Verdict.AGREE,
            TicketCrossCheck.arbitrate(LotteryType.SSQ, 20.0, 2, false, 5, 5)
        )
    }

    @Test
    fun `主识别与金额吻合 - 抑制冲突 不打扰用户`() {
        // 这是本次改进的核心场景：主识别 5 注、复核 4 注，
        // 而票面 20 元 ÷ (2×2) = 5 注 → 主识别被金额印证 → 不必让用户确认
        val v = TicketCrossCheck.arbitrate(LotteryType.SSQ, 20.0, 2, false, 5, 4)
        assertEquals(TicketCrossCheck.Verdict.PRIMARY_CONSISTENT, v)
        assertTrue(TicketCrossCheck.canSuppressConflict(v))
    }

    @Test
    fun `复核与金额吻合 - 主识别错了 必须让用户看`() {
        // 反过来：主识别 4 注、复核 5 注，金额印证的是复核 ——
        // 说明**主识别读错了**，而界面上显示、即将入账的是主识别的结果，
        // 这种情况绝不能静默放行
        val v = TicketCrossCheck.arbitrate(LotteryType.SSQ, 20.0, 2, false, 4, 5)
        assertEquals(TicketCrossCheck.Verdict.REVIEW_CONSISTENT, v)
        assertFalse("主识别错了还抑制冲突 = 把错数据放进去", TicketCrossCheck.canSuppressConflict(v))
    }

    @Test
    fun `都对不上 - 必须让用户看`() {
        val v = TicketCrossCheck.arbitrate(LotteryType.SSQ, 20.0, 2, false, 3, 4)
        assertEquals(TicketCrossCheck.Verdict.NEITHER, v)
        assertFalse(TicketCrossCheck.canSuppressConflict(v))
    }

    @Test
    fun `金额算不出注数 - 不做仲裁 交给用户`() {
        // 19 元除不尽 → 没有依据判断谁对
        val v = TicketCrossCheck.arbitrate(LotteryType.SSQ, 19.0, 1, false, 9, 10)
        assertEquals(TicketCrossCheck.Verdict.NEITHER, v)
        assertFalse(TicketCrossCheck.canSuppressConflict(v))
    }

    @Test
    fun `金额缺失 - 不做仲裁`() {
        val v = TicketCrossCheck.arbitrate(LotteryType.SSQ, null, 1, false, 5, 4)
        assertEquals(TicketCrossCheck.Verdict.NEITHER, v)
    }

    // ---------------- 冲突提示的措辞 ----------------

    @Test
    fun `吻合时文案写明与金额吻合`() {
        val s = TicketCrossCheck.annotate(LotteryType.SSQ, 5, 20.0, 2, false)
        assertTrue("实际：$s", s.contains("5 注"))
        assertTrue("实际：$s", s.contains("吻合"))
    }

    @Test
    fun `不吻合时文案给出金额对应的注数`() {
        val s = TicketCrossCheck.annotate(LotteryType.SSQ, 4, 20.0, 2, false)
        assertTrue("实际：$s", s.contains("4 注"))
        assertTrue("实际：$s", s.contains("5 注"))
    }

    @Test
    fun `算不出金额对应注数时只报注数 不硬编`() {
        val s = TicketCrossCheck.annotate(LotteryType.SSQ, 4, null, 1, false)
        assertEquals("4 注", s)
    }

    @Test
    fun `单价与规则引擎一致`() {
        // 单价写两份迟早不一致，所以这里断言两边相等
        assertEquals(2.0, TicketCrossCheck.unitPrice(LotteryType.SSQ, false), 0.001)
        assertEquals(2.0, TicketCrossCheck.unitPrice(LotteryType.DLT, false), 0.001)
        assertEquals(3.0, TicketCrossCheck.unitPrice(LotteryType.DLT, true), 0.001)
        // 和「正向」实现对齐
        assertEquals(
            BetRules.expectedAmount(LotteryType.DLT, 5, 2, true),
            TicketCrossCheck.unitPrice(LotteryType.DLT, true) * 5 * 2,
            0.001
        )
    }
}
