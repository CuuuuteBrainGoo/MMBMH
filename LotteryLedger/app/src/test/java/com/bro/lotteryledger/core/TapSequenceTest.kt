package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「拜拜财神」的连点计数。
 *
 * 少爷要求「**一次性连续**点击 13 次」。这个窗口边界写错的表现很隐蔽：
 *  - 窗口太大 → 今天点 6 次 + 明天点 7 次也能触发（就不是"连续"了）
 *  - 窗口太小 → 手速慢一点就永远点不到 13（用户以为功能坏了）
 *
 * 两种都不会报错，只能靠测试钉。
 */
class TapSequenceTest {

    private val t0 = 1_800_000_000_000L

    @Test
    fun `第一次点击 - 计数为 1`() {
        assertEquals(1, TapSequence.next(currentTaps = 0, lastTapAt = 0L, now = t0))
    }

    @Test
    fun `紧接着点 - 计数累加`() {
        assertEquals(2, TapSequence.next(currentTaps = 1, lastTapAt = t0, now = t0 + 50))
        assertEquals(7, TapSequence.next(currentTaps = 6, lastTapAt = t0, now = t0 + 300))
    }

    @Test
    fun `正好卡在窗口边界 - 还算连续`() {
        // 边界取 `<=`：手速慢到这个点上也该算连点。
        // 如果哪天改成 `<`，这条会红。
        assertEquals(
            5,
            TapSequence.next(
                currentTaps = 4, lastTapAt = t0,
                now = t0 + TapSequence.WINDOW_MS
            )
        )
    }

    @Test
    fun `超过窗口 1 毫秒 - 从头数`() {
        assertEquals(
            1,
            TapSequence.next(
                currentTaps = 4, lastTapAt = t0,
                now = t0 + TapSequence.WINDOW_MS + 1
            )
        )
    }

    @Test
    fun `隔了很久 - 从头数`() {
        assertEquals(1, TapSequence.next(currentTaps = 12, lastTapAt = t0, now = t0 + 60_000))
        // 「隔一天」的极端情况
        assertEquals(
            1,
            TapSequence.next(currentTaps = 12, lastTapAt = t0, now = t0 + 86_400_000)
        )
    }

    @Test
    fun `关键场景 - 两次分开点凑不满 13`() {
        // 模拟少爷最可能遇到的问题：今天点 6 次，明天再点 7 次。
        // 因为中间隔了太久，第二次的 7 次会从 1 开始数，最终只有 7 —— 不触发。
        var taps = 0
        var last = 0L
        var now = t0
        repeat(6) {
            taps = TapSequence.next(taps, last, now)
            last = now
            now += 200
        }
        assertEquals(6, taps)

        // 隔一天
        now += 86_400_000
        repeat(7) {
            taps = TapSequence.next(taps, last, now)
            last = now
            now += 200
        }
        assertEquals("第二天这 7 次不该和昨天的累加", 7, taps)
    }

    @Test
    fun `时间倒流 - 保守从 1 重新数`() {
        // 用户改了系统时间。用错误的时间差凑出虚高计数更糟，宁可重数
        assertEquals(1, TapSequence.next(currentTaps = 9, lastTapAt = t0, now = t0 - 1))
        assertEquals(1, TapSequence.next(currentTaps = 9, lastTapAt = t0, now = t0 - 100_000))
    }

    @Test
    fun `没有上次记录 - 从 1 开始`() {
        assertEquals(1, TapSequence.next(currentTaps = 0, lastTapAt = t0, now = t0 + 10))
    }

    @Test
    fun `连续快速点 13 次能到 13`() {
        // 正着走一遍触发路径，确认计数不会因为实现细节卡在 12
        var taps = 0
        var last = 0L
        var now = t0
        repeat(13) {
            taps = TapSequence.next(taps, last, now)
            last = now
            now += 150      // 人手连点的节奏
        }
        assertEquals(13, taps)
    }

    @Test
    fun `窗口是 1_5 秒`() {
        // 改成别的值需要重新确认手感：太短点不到，太长就不叫"连续"了
        assertEquals(1_500L, TapSequence.WINDOW_MS)
    }
}
