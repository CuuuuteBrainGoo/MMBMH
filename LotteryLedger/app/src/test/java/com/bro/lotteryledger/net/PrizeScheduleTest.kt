package com.bro.lotteryledger.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 定时对奖的时间计算。
 *
 * 这段逻辑错一天，对奖就会晚一天跑 —— 而彩票有兑奖期限，晚一天可能就过期了。
 * 所以边界（正好 22:30、跨天、月初月末）都要钉住。
 */
class PrizeScheduleTest {

    private val t2230 = LocalTime.of(22, 30)
    private val t0830 = LocalTime.of(8, 30)

    @Test
    fun `22点30之前 下一个22点30是今天`() {
        val now = LocalDateTime.of(2026, 9, 26, 20, 0)
        // 20:00 → 22:30 是 150 分钟
        assertEquals(150L, PrizeCheckWorker.minutesUntil(t2230, now))
    }

    @Test
    fun `22点30之后 下一个是明天`() {
        val now = LocalDateTime.of(2026, 9, 26, 23, 0)
        // 23:00 → 次日 22:30 = 23.5 小时 = 1410 分钟
        assertEquals(1410L, PrizeCheckWorker.minutesUntil(t2230, now))
    }

    @Test
    fun `正好是22点30时排到明天而不是当天`() {
        val now = LocalDateTime.of(2026, 9, 26, 22, 30)
        // 不能返回 0（那会立刻又跑一次），要排到明天
        assertEquals(1440L, PrizeCheckWorker.minutesUntil(t2230, now))
    }

    @Test
    fun `跨天时8点30排到明天早上`() {
        val now = LocalDateTime.of(2026, 9, 26, 23, 30)
        // 23:30 → 次日 08:30 = 9 小时 = 540 分钟
        assertEquals(540L, PrizeCheckWorker.minutesUntil(t0830, now))
    }

    @Test
    fun `清晨时8点30是今天早上`() {
        val now = LocalDateTime.of(2026, 9, 26, 6, 0)
        // 06:00 → 08:30 = 150 分钟
        assertEquals(150L, PrizeCheckWorker.minutesUntil(t0830, now))
    }

    @Test
    fun `月末跨月也能算对`() {
        val now = LocalDateTime.of(2026, 9, 30, 23, 0)
        // 9/30 23:00 → 10/1 22:30
        assertEquals(1410L, PrizeCheckWorker.minutesUntil(t2230, now))
    }

    @Test
    fun `年末跨年也能算对`() {
        val now = LocalDateTime.of(2026, 12, 31, 23, 0)
        // 12/31 23:00 → 次年 1/1 22:30
        assertEquals(1410L, PrizeCheckWorker.minutesUntil(t2230, now))
    }

    @Test
    fun `延迟永远是正数 不会排到过去`() {
        // 这是最要命的错误：算出负数或 0，WorkManager 会立刻执行或干脆不执行
        val cases = listOf(
            LocalDateTime.of(2026, 9, 26, 0, 0),
            LocalDateTime.of(2026, 9, 26, 22, 29),
            LocalDateTime.of(2026, 9, 26, 22, 30),
            LocalDateTime.of(2026, 9, 26, 23, 59)
        )
        cases.forEach { now ->
            val d = PrizeCheckWorker.minutesUntil(t2230, now)
            assertTrue("$now 算出的延迟 $d 必须 ≥ 1 分钟", d >= 1)
        }
    }

    @Test
    fun `默认参数用当前时间且是正数`() {
        assertTrue(PrizeCheckWorker.minutesUntil(t2230) >= 1)
    }
}
