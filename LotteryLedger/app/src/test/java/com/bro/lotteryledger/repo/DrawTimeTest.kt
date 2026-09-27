package com.bro.lotteryledger.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 「开奖时间是否已过」的判定。
 *
 * 这个函数是少爷那条要求的开关：**入账时如果已过开奖时间，当场核验一次**。
 * 判错的代价是双向的：
 *  - 判早了 → 白跑一趟网络，官方还没出结果，用户看到「等待开奖结果」还以为出问题了
 *  - 判晚了 → 明明已经开奖，却要干等到晚上 22:30 的定时任务
 *
 * 因为依赖「当前时刻」，必须把 now 注入进来才能测。这里按真实边界逐个钉死。
 */
class DrawTimeTest {

    private val today = LocalDate.of(2026, 9, 26)

    private fun passed(
        drawDate: String?,
        hour: Int = 12,
        minute: Int = 0
    ): Boolean = PrizeService.isDrawTimePassed(
        drawDate, today, LocalTime.of(hour, minute)
    )

    // ---------------- 跨日 ----------------

    @Test
    fun `开奖日是昨天 - 无论几点都算过了`() {
        assertTrue(passed("2026-09-25", hour = 0, minute = 1))
        assertTrue(passed("2026-09-25", hour = 23, minute = 59))
    }

    @Test
    fun `开奖日是明天 - 无论几点都算没过`() {
        assertFalse(passed("2026-09-27", hour = 23, minute = 59))
    }

    @Test
    fun `开奖日是今天 - 但还差一个月（跨年也照样判对）`() {
        assertTrue(
            PrizeService.isDrawTimePassed(
                "2025-12-31", LocalDate.of(2026, 1, 1), LocalTime.of(0, 5)
            )
        )
    }

    // ---------------- 当天开奖的小时边界 ----------------

    @Test
    fun `今天开奖 - 早上9点 还没过`() {
        assertFalse(passed("2026-09-26", hour = 9))
    }

    @Test
    fun `今天开奖 - 21点30分 还没过`() {
        // 官方 21:30 开奖，但公告要几分钟才出，这时查大概率拿不到，所以还不算过
        assertFalse(passed("2026-09-26", hour = 21, minute = 30))
    }

    @Test
    fun `今天开奖 - 21点59分 还没过`() {
        assertFalse(passed("2026-09-26", hour = 21, minute = 59))
    }

    @Test
    fun `今天开奖 - 22点整 算过了`() {
        // 分界点：恰好 22:00 就算过点。改这个行为会让「入账即时核验」在晚上失效
        assertTrue(passed("2026-09-26", hour = 22, minute = 0))
    }

    @Test
    fun `今天开奖 - 23点 算过了`() {
        assertTrue(passed("2026-09-26", hour = 23))
    }

    // ---------------- 异常输入 ----------------

    @Test
    fun `开奖日为空 - 保守当作没过`() {
        // 不能因为读不到日期就贸然去查，不然每张没读到开奖日的票都会打一次网络
        assertFalse(passed(null))
        assertFalse(passed(""))
        assertFalse(passed("   "))
    }

    @Test
    fun `开奖日格式不对 - 保守当作没过`() {
        assertFalse(passed("2026年9月26日"))
        assertFalse(passed("26/09/2026"))
    }

    @Test
    fun `开奖日带多余空格 - 能解析`() {
        // 模型返回的字符串常带空格，不能让这点小事把核验关掉
        assertTrue(passed("  2026-09-25  ", hour = 1))
    }

    // ---------------- 与兑奖截止日的口径一致性 ----------------

    @Test
    fun `默认参数用的是系统时间 - 不传 today 也不炸`() {
        // 只验证能正常返回，具体值取决于运行时刻
        val r = PrizeService.isDrawTimePassed("2000-01-01")
        assertEquals("2000 年的开奖日早就过了", true, r)
    }

    // ---------------- 「开奖日过去多久」—— 判定查不到结果的关键 ----------------
    //
    // 少爷 2026-09-27 报的问题：3 张 4 月份的双色球扫进来后永远卡在「等待开奖结果」。
    // 根因是查询范围只有 50 期（约 3.8 个月），4 月的票不在里面，
    // 而代码把「查不到」一律当成「官方还没公布」→ 状态永远不前进 → 反复自动核验。

    @Test
    fun `刚开奖当天 - 过去 0 天`() {
        assertEquals(0L, PrizeService.daysSinceDraw("2026-09-26", LocalDate.of(2026, 9, 26)))
    }

    @Test
    fun `隔一天 - 过去 1 天`() {
        assertEquals(1L, PrizeService.daysSinceDraw("2026-09-26", LocalDate.of(2026, 9, 27)))
    }

    @Test
    fun `跨月也对`() {
        // 9-30 到 10-03 是 3 天，不能用「日数相减」因为它是负的
        assertEquals(3L, PrizeService.daysSinceDraw("2026-09-30", LocalDate.of(2026, 10, 3)))
    }

    @Test
    fun `五个月前的老票 - 天数很大`() {
        val d = PrizeService.daysSinceDraw("2026-04-20", LocalDate.of(2026, 9, 27))
        assertTrue("4 月到 9 月应该有一百多天，实际 $d", d > 150)
    }

    @Test
    fun `开奖日在未来 - 返回 0 而不是负数`() {
        // 负天数会让「> STALE_DAYS」的比较逻辑含义混乱，统一夹到 0
        assertEquals(0L, PrizeService.daysSinceDraw("2026-12-31", LocalDate.of(2026, 9, 27)))
    }

    @Test
    fun `日期读不出来 - 返回 0 走保守分支`() {
        assertEquals(0L, PrizeService.daysSinceDraw(null, LocalDate.of(2026, 9, 27)))
        assertEquals(0L, PrizeService.daysSinceDraw("不是日期", LocalDate.of(2026, 9, 27)))
    }

    @Test
    fun `阈值常量是 3 天`() {
        // 这个数字决定「什么时候从『等官方』翻成『查不到』」：
        // 太小会在官方延迟时误报查不到，太大会让用户白等。
        // 3 天 = 覆盖定时任务重试 + 网络故障 + 节假日延迟。
        assertEquals(3L, PrizeService.STALE_DAYS)
    }

    @Test
    fun `刚好卡在阈值边界 - 3 天还算等官方 4 天才算查不到`() {
        val draw = "2026-09-23"
        val at3 = PrizeService.daysSinceDraw(draw, LocalDate.of(2026, 9, 26))
        val at4 = PrizeService.daysSinceDraw(draw, LocalDate.of(2026, 9, 27))
        assertEquals(3L, at3)
        assertEquals(4L, at4)
        // 判定用的是 `days > STALE_DAYS`，所以第 3 天仍等待、第 4 天才翻脸
        assertTrue("第 3 天不该翻", at3 <= PrizeService.STALE_DAYS)
        assertTrue("第 4 天该翻", at4 > PrizeService.STALE_DAYS)
    }
}
