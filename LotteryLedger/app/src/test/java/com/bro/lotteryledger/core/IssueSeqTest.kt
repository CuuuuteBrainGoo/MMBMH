package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「同一期买了多张」的序号标注（少爷 2026-09-27 第 2 条）。
 *
 * 序号的意义：一张 5 注的票，和「同一期买的 5 张单注票」，
 * 在列表里都是「双色球 · 2026102 期 · 5 注」—— 不标序号根本分不清。
 */
class IssueSeqTest {

    private data class T(val id: Long, val type: String, val issue: String)

    private fun build(list: List<T>) = IssueSeq.build(
        items = list,
        keyOf = { IssueSeq.key(it.type, it.issue) },
        idOf = { it.id }
    )

    @Test
    fun `同一期两张要编号`() {
        val m = build(
            listOf(
                T(11, "ssq", "2026102"),
                T(12, "ssq", "2026102")
            )
        )
        assertEquals(1 to 2, m[11])
        assertEquals(2 to 2, m[12])
    }

    @Test
    fun `同一期五张编号到尾`() {
        val m = build((1L..5L).map { T(it, "ssq", "2026102") })
        assertEquals(5, m.size)
        assertEquals(1 to 5, m[1])
        assertEquals(3 to 5, m[3])
        assertEquals(5 to 5, m[5])
    }

    @Test
    fun `只有一张时不编号 - 标 1-1 是噪音`() {
        val m = build(listOf(T(11, "ssq", "2026102")))
        assertTrue("只买一张不需要区分", m.isEmpty())
        assertNull(m[11])
    }

    @Test
    fun `不同期各自编号互不影响`() {
        val m = build(
            listOf(
                T(1, "ssq", "2026102"),
                T(2, "ssq", "2026102"),
                T(3, "ssq", "2026103"),
                T(4, "ssq", "2026103"),
                T(5, "ssq", "2026103")
            )
        )
        assertEquals(1 to 2, m[1])
        assertEquals(2 to 2, m[2])
        assertEquals(1 to 3, m[3])
        assertEquals(3 to 3, m[5])
    }

    @Test
    fun `同一期号但不同彩种要分开算`() {
        // 「26101」这个期号双色球和大乐透都可能出现，不能混成一组
        val m = build(
            listOf(
                T(1, "ssq", "26101"),
                T(2, "dlt", "26101")
            )
        )
        assertTrue("不同彩种的同期不该互相编号", m.isEmpty())
    }

    @Test
    fun `序号严格跟着列表顺序`() {
        // 列表已经按购票时间从早到晚排好，所以「1」永远是最早买的那张。
        // 这条是硬约束：序号和列表顺序不一致，用户核对时会以为排序错了。
        val m = build(
            listOf(
                T(77, "dlt", "26101"),   // 最早
                T(55, "dlt", "26101"),
                T(99, "dlt", "26101")    // 最晚
            )
        )
        assertEquals(1 to 3, m[77])
        assertEquals(2 to 3, m[55])
        assertEquals(3 to 3, m[99])
    }

    @Test
    fun `空列表不出错`() {
        assertTrue(build(emptyList()).isEmpty())
    }

    @Test
    fun `期号缺失时也能正常分组`() {
        val m = build(listOf(T(1, "ssq", ""), T(2, "ssq", "")))
        assertEquals(1 to 2, m[1])
    }
}
