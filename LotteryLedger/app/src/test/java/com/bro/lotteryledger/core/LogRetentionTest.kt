package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志按周清空（少爷 2026-09-27 要求）。
 *
 * 这个判定的错法有两种，**而且都不会报错**：
 *  - 判太松 → 日志被反复清掉，真出事时导出日志一看是空的
 *  - 判太紧 → 永远不清，日志无限增长（正是少爷要避免的）
 *
 * 所以边界必须逐个钉住。
 */
class LogRetentionTest {

    private val day = 24L * 60 * 60 * 1000

    /** 固定一个基准时刻，避免用真实时间导致测试随时间漂移。 */
    private val t0 = 1_800_000_000_000L

    @Test
    fun `首次运行不清 - 只建立基准`() {
        // 关键：装好当天如果就清，会把刚写的启动日志清掉，而且**每次启动都清**
        assertFalse(LogRetention.shouldSweep(t0, null))
        assertFalse(LogRetention.shouldSweep(t0, 0L))
    }

    @Test
    fun `刚清过 1 天 - 不清`() {
        assertFalse(LogRetention.shouldSweep(t0, t0 - 1 * day))
    }

    @Test
    fun `差一点点到 7 天 - 不清`() {
        // 边界：6 天 23:59:59.999
        assertFalse(LogRetention.shouldSweep(t0, t0 - (7 * day - 1)))
    }

    @Test
    fun `正好 7 天 - 清`() {
        assertTrue(LogRetention.shouldSweep(t0, t0 - 7 * day))
    }

    @Test
    fun `超过 7 天 - 清`() {
        assertTrue(LogRetention.shouldSweep(t0, t0 - 8 * day))
        assertTrue(LogRetention.shouldSweep(t0, t0 - 365 * day))
    }

    @Test
    fun `时间倒流 - 保守不清`() {
        // 用户改过系统时间、或时区变动。
        // 宁可多留几天日志，也不能因为时间乱跳就把日志删了。
        assertFalse(LogRetention.shouldSweep(t0, t0 + 1))
        assertFalse(LogRetention.shouldSweep(t0, t0 + 100 * day))
    }

    @Test
    fun `同一时刻 - 不清`() {
        assertFalse(LogRetention.shouldSweep(t0, t0))
    }

    @Test
    fun `负数时间戳当作没有记录`() {
        assertFalse(LogRetention.shouldSweep(t0, -1L))
    }

    // ---------------- 剩余时间（给界面显示） ----------------

    @Test
    fun `没有记录时 剩余时间按一整周算`() {
        assertEquals(LogRetention.INTERVAL_MS, LogRetention.msUntilNextSweep(t0, null))
    }

    @Test
    fun `刚清过 - 剩余接近一整周`() {
        val left = LogRetention.msUntilNextSweep(t0, t0 - 1)
        assertTrue("应该还剩接近 7 天，实际 $left", left > 7 * day - 1000)
    }

    @Test
    fun `已到期 - 剩余为 0 不返回负数`() {
        assertEquals(0L, LogRetention.msUntilNextSweep(t0, t0 - 10 * day))
    }

    @Test
    fun `时间倒流 - 剩余仍按一整周算`() {
        assertEquals(
            LogRetention.INTERVAL_MS,
            LogRetention.msUntilNextSweep(t0, t0 + 5 * day)
        )
    }

    @Test
    fun `保留期是 7 天`() {
        // 少爷要求「每周自动清空一次」，改成别的值要重新确认
        assertEquals(7L, LogRetention.INTERVAL_MS / day)
    }
}
