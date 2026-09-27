package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公益贡献推算（少爷 2026-09-27 要求：首页加「公益贡献」合计）。
 *
 * 比例来自财政部公告：福彩双色球、体彩超级大乐透的公益金提取比例**都是 36%**。
 * 票面上印的「感谢您为公益事业贡献 X 元」就是这个数。
 */
class PublicWelfareTest {

    @Test
    fun `少爷给的例子 - 20 元贡献 7_2 元`() {
        assertEquals(7.2, PublicWelfare.fromAmount(20.0), 0.001)
    }

    @Test
    fun `官方口径 - 2 元一注贡献 0_72 元`() {
        // 票面上印的就是这个数，能对上说明比例取对了
        assertEquals(0.72, PublicWelfare.fromAmount(2.0), 0.001)
    }

    @Test
    fun `大乐透追加票也一样 - 3 元贡献 1_08 元`() {
        // 追加部分同样按 36% 提取，所以不用按彩种区分
        assertEquals(1.08, PublicWelfare.fromAmount(3.0), 0.001)
    }

    @Test
    fun `空值和非法值都算 0 不抛异常`() {
        assertEquals(0.0, PublicWelfare.fromAmount(null), 0.0001)
        assertEquals(0.0, PublicWelfare.fromAmount(0.0), 0.0001)
        assertEquals(0.0, PublicWelfare.fromAmount(-5.0), 0.0001)
    }

    @Test
    fun `一批票的合计`() {
        // 10 + 20 + 30 = 60 元 → 21.6 元
        val total = PublicWelfare.totalOf(listOf(10.0, 20.0, 30.0))
        assertEquals(21.6, total, 0.001)
    }

    @Test
    fun `合计会跳过没有金额的票`() {
        assertEquals(7.2, PublicWelfare.totalOf(listOf(20.0, null, 0.0)), 0.001)
        assertEquals(0.0, PublicWelfare.totalOf(emptyList()), 0.0001)
    }

    @Test
    fun `说明文字里必须写明比例`() {
        // 不写的话，用户拿计算器一算发现和票面印的差几分钱（四舍五入），
        // 会以为程序算错了。坦白是估算，比装精确好。
        val s = PublicWelfare.describe(7.2)
        assertTrue("实际：$s", s.contains("36%"))
        assertTrue("实际：$s", s.contains("估算"))
    }

    @Test
    fun `比例是 36%`() {
        // 财政部 2026 年第 24 号公告：双色球与超级大乐透均为 36%
        // （奖金 51% + 发行费 13% + 公益金 36% = 100%）。
        // 要改这个数字，先回去查公告。
        assertEquals(0.36, PublicWelfare.RATIO, 0.0001)
    }
}
