package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 兑奖截止日计算的测试。
 *
 * 顺延规则来自少爷 2026-09-26 的明确要求：
 * 国庆**头 3 天**、春节**头 7 天**顺延，其它时间段不顺延。
 */
class RedemptionDeadlineTest {

    private val today = LocalDate.of(2026, 9, 26)

    @Test
    fun `基本规则是开奖后60天`() {
        val info = RedemptionDeadline.compute("2026-07-01", today)!!
        assertEquals(LocalDate.of(2026, 7, 1), info.drawDate)
        assertEquals(LocalDate.of(2026, 8, 30), info.deadline)   // 7/1 + 60 天
        assertFalse("这个日期不涉及假期，不该顺延", info.extended)
    }

    // ---------------- 国庆：头 3 天 ----------------

    @Test
    fun `截止日落在国庆头三天会顺延到10月4日`() {
        // 让 开奖日+60 恰好落在 10/1：开奖日 = 10/1 - 60 天 = 8/2
        val info = RedemptionDeadline.compute("2026-08-02", today)!!
        assertEquals("原始期限", LocalDate.of(2026, 10, 1), info.rawDeadline)
        assertEquals("顺延后", LocalDate.of(2026, 10, 4), info.deadline)
        assertEquals(3L, info.extendedDays)
        assertEquals("国庆假期", info.extensionName)
    }

    @Test
    fun `截止日落在国庆第3天也顺延到10月4日`() {
        val info = RedemptionDeadline.compute("2026-08-04", today)!!   // +60 = 10/3
        assertEquals(LocalDate.of(2026, 10, 3), info.rawDeadline)
        assertEquals(LocalDate.of(2026, 10, 4), info.deadline)
        assertEquals(1L, info.extendedDays)
    }

    @Test
    fun `截止日落在国庆第4天不顺延`() {
        // 少爷明确「头3天」才顺延，10/4 属于假期但不在头三天内
        val info = RedemptionDeadline.compute("2026-08-05", today)!!   // +60 = 10/4
        assertEquals(LocalDate.of(2026, 10, 4), info.rawDeadline)
        assertEquals(LocalDate.of(2026, 10, 4), info.deadline)
        assertFalse(info.extended)
    }

    // ---------------- 春节：头 7 天 ----------------

    @Test
    fun `截止日落在春节头七天会顺延到初八`() {
        // 2027 年春节 2/6。取截止日 = 2/6 → 开奖日 = 2/6 - 60 = 2026-12-08
        val info = RedemptionDeadline.compute("2026-12-08", today)!!
        assertEquals(LocalDate.of(2027, 2, 6), info.rawDeadline)
        assertEquals("顺延到正月初八", LocalDate.of(2027, 2, 13), info.deadline)
        assertEquals(7L, info.extendedDays)
        assertEquals("春节假期", info.extensionName)
    }

    @Test
    fun `截止日落在春节第7天也顺延一天`() {
        // 2/6 + 6 天 = 2/12 是春节窗口最后一天 → 开奖日 = 2/12 - 60 = 2026-12-14
        val info = RedemptionDeadline.compute("2026-12-14", today)!!
        assertEquals(LocalDate.of(2027, 2, 12), info.rawDeadline)
        assertEquals(LocalDate.of(2027, 2, 13), info.deadline)
        assertEquals(1L, info.extendedDays)
    }

    @Test
    fun `截止日落在春节第8天不顺延`() {
        val info = RedemptionDeadline.compute("2026-12-15", today)!!   // +60 = 2027-02-13
        assertEquals(LocalDate.of(2027, 2, 13), info.rawDeadline)
        assertFalse("初八已经出了头七天窗口", info.extended)
    }

    // ---------------- 其它时间一律不顺延 ----------------

    @Test
    fun `元旦和劳动节都不顺延`() {
        // 12/31 截止 → 开奖日 11/1
        val newYear = RedemptionDeadline.compute("2026-11-01", today)!!
        assertEquals(LocalDate.of(2026, 12, 31), newYear.rawDeadline)
        assertFalse("元旦不在顺延范围内", newYear.extended)

        // 5/1 截止 → 开奖日 3/2
        val labour = RedemptionDeadline.compute("2026-03-02", today)!!
        assertEquals(LocalDate.of(2026, 5, 1), labour.rawDeadline)
        assertFalse("劳动节不在顺延范围内", labour.extended)
    }

    // ---------------- 边界与异常 ----------------

    @Test
    fun `日期解析失败返回 null 而不是崩溃`() {
        assertNull(RedemptionDeadline.compute("", today))
        assertNull(RedemptionDeadline.compute(null, today))
        assertNull(RedemptionDeadline.compute("2026/07/01", today))
        assertNull(RedemptionDeadline.compute("七月一日", today))
        assertNull(RedemptionDeadline.compute("2026-13-45", today))
    }

    @Test
    fun `剩余天数的文案`() {
        val future = RedemptionDeadline.compute("2026-08-20", today)!!
        assertTrue(future.daysLeft > 0)
        assertTrue(future.remainingText().contains("还剩"))
        assertFalse(future.expired)

        val past = RedemptionDeadline.compute("2026-01-01", today)!!
        assertTrue(past.expired)
        assertTrue(past.remainingText().contains("已过期"))
    }

    @Test
    fun `窗口表能列出来当年的顺延区间`() {
        val w = RedemptionDeadline.extensionWindows(2027)
        assertEquals(2, w.size)
        assertTrue(w.any { it.first.contains("国庆") && it.second.contains("2027-10-01") })
        assertTrue(w.any { it.first.contains("春节") && it.second.contains("2027-02-06") })
    }

    @Test
    fun `春节日期表覆盖到2035年`() {
        // 表覆盖不到的年份宁可不顺延，也不能算错 —— 这里钉住覆盖范围
        assertNotNull(RedemptionDeadline.compute("2034-12-21", today))  // +60 = 2035-02-19 春节
        val covered = RedemptionDeadline.extensionWindows(2035)
        assertTrue("2035 年春节应在表里", covered.any { it.first.contains("春节") })
    }
}
