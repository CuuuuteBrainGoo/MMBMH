package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 彩票展示编号（少爷 2026-09-27 要求）。
 *
 * > 「在首页每个彩票前面加一个编号，越新的编号数字越大」
 * > 「后录入的彩票，日期在若干彩票之前，就将这个彩票标号往前插入，
 * >  并且给其后的彩票全部更新编号。不过这个编号在筛选时不变。」
 *
 * 列表按开奖日期**倒序**（越新越靠前），所以编号 = 总数 - 下标：
 * **列表最下面（最旧）= 1，最上面（最新）= 总数**。
 */
class TicketCodeTest {

    private data class T(val id: Long)

    /** 按列表顺序（= 开奖日期倒序）传进来，模拟 `TicketDao.all()` 的输出。 */
    private fun assign(list: List<T>) = TicketCode.assign(list) { it.id }

    @Test
    fun `最上面最新编号最大 - 最下面最旧编号是1`() {
        // 列表顺序：最新 → 最旧
        val m = assign(listOf(T(30), T(20), T(10)))
        assertEquals(3, m[30])
        assertEquals(2, m[20])
        assertEquals(1, m[10])
    }

    @Test
    fun `编号连续且无空号`() {
        val m = assign((1L..7L).map { T(it) })
        // 7 张票 → 编号应恰好是 1..7，一个不多一个不少
        assertEquals((1..7).toSet(), m.values.toSet())
    }

    @Test
    fun `后录的老票会插到下面 - 它后面的票编号顺移`() {
        // 场景：已有 3 张（日期从新到旧：A、B、C，编号 3/2/1）。
        // 现在补录一张 D，它的开奖日期比 C 还早 —— 排在列表最下面。
        // 按规则 D 应该是 1，而 A/B/C 的编号**整体加 1**。
        val before = assign(listOf(T(1), T(2), T(3)))      // A=3 B=2 C=1
        assertEquals(3, before[1])
        assertEquals(1, before[3])

        val after = assign(listOf(T(1), T(2), T(3), T(4)))  // D 落在最下面
        assertEquals(4, after[1])
        assertEquals(2, after[3])
        assertEquals(1, after[4])
    }

    @Test
    fun `后录的新票排最上面 - 编号是新的最大值`() {
        val before = assign(listOf(T(1), T(2), T(3)))
        assertEquals(3, before[1])

        // 新票 E 的开奖日期最新 → 排最上面 → 编号 4
        val after = assign(listOf(T(9), T(1), T(2), T(3)))
        assertEquals(4, after[9])
        assertEquals(3, after[1])
    }

    @Test
    fun `删掉中间一张 - 它上面的编号各减一`() {
        // 4 张：编号 4/3/2/1。删掉第 2 张之后剩 3 张。
        val before = assign(listOf(T(1), T(2), T(3), T(4)))
        assertEquals(4, before[1])
        assertEquals(3, before[2])

        val after = assign(listOf(T(1), T(3), T(4)))
        assertEquals(3, after[1])   // 原来 4 → 3
        assertEquals(2, after[3])   // 原来 2 → 2（在列表里位置没变）
        assertEquals(1, after[4])
        assertNull("删掉的票不该再有编号", after[2])
    }

    @Test
    fun `编号跟着全量列表走 - 不是筛选结果`() {
        // 少爷原话：「这个编号在筛选时不变」。
        // 所以 TicketCode 只接受全量列表；筛选后的列表**不参与编号**。
        // 用 5 张票验证：全量编号 5..1，筛出其中 2 张后，它们的编号仍是原值。
        val all = assign((1L..5L).map { T(it) })
        assertEquals(5, all[1])
        assertEquals(2, all[4])

        // 假设筛选只留下 id=1 和 id=4 —— 从全量 map 里取，值不变
        val visibleIds = listOf(1L, 4L)
        val shown = visibleIds.map { TicketCode.codeOf(all, it) }
        assertEquals(listOf(5, 2), shown)
    }

    @Test
    fun `空列表返回空map`() {
        assertTrue(assign(emptyList()).isEmpty())
    }

    @Test
    fun `只有一张票时编号是1`() {
        assertEquals(1, assign(listOf(T(42)))[42])
    }

    @Test
    fun `编号拿不到时返回null`() {
        val m = assign(listOf(T(1)))
        assertNull("不在列表里的 id 应该拿不到编号", TicketCode.codeOf(m, 999))
    }

    @Test
    fun `label 统一成井号格式`() {
        assertEquals("#12", TicketCode.label(12))
        assertNull(TicketCode.label(null))
    }

    @Test
    fun `join 按编号从大到小排 - 跟列表顺序一致`() {
        // 用户从上往下看列表，编号是从大到小的 —— 提示里也按这个序，好对。
        val s = TicketCode.join(listOf(3, 12, 7))
        assertEquals("#12、#7、#3", s)
    }

    @Test
    fun `join 会跳过拿不到编号的票`() {
        assertEquals("#5、#2", TicketCode.join(listOf(5, null, 2)))
    }

    @Test
    fun `join 超过上限时省略并报总数`() {
        val s = TicketCode.join(listOf(9, 8, 7, 6, 5, 4, 3, 2, 1), max = 3)
        assertEquals("#9、#8、#7 等 9 张", s)
    }

    @Test
    fun `join 全为空时返回空串`() {
        assertEquals("", TicketCode.join(listOf(null, null)))
        assertEquals("", TicketCode.join(emptyList()))
    }
}
