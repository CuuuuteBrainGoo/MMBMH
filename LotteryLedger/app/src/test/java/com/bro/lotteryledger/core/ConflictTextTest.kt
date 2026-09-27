package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冲突字段路径 → 人话（少爷 2026-09-27 反馈「bets 是什么字段」）。
 *
 * 这一层只做翻译，不改判定。但翻译"漏了"的后果很直接：
 * 用户看到一个内部路径，等于没提示。
 */
class ConflictTextTest {

    @Test
    fun `bets 翻译成注数投注内容`() {
        // 这是少爷实际看到的那个 —— 必须说人话
        assertEquals("注数 / 投注内容", ConflictText.label("bets"))
    }

    @Test
    fun `期号和日期这类字段直接对应`() {
        assertEquals("期号", ConflictText.label("issue"))
        assertEquals("开奖日期", ConflictText.label("draw_date"))
        assertEquals("票面金额（元）", ConflictText.label("amount_yuan"))
        assertEquals("彩种", ConflictText.label("lottery_type"))
    }

    @Test
    fun `带下标的字段带上第几注 且下标从 1 开始`() {
        // 内部是 0 基，给人看必须 1 基 —— 弄反了用户会去核对错的那一注
        assertEquals("第 1 注 红球", ConflictText.label("bets[0].main", LotteryType.SSQ))
        assertEquals("第 3 注 蓝球", ConflictText.label("bets[2].second", LotteryType.SSQ))
    }

    @Test
    fun `同一个字段名在两种彩票里叫法不同`() {
        // main 在双色球是红球、在大乐透是前区 —— 不能用同一个词
        assertEquals("第 1 注 红球", ConflictText.label("bets[0].main", LotteryType.SSQ))
        assertEquals("第 1 注 前区", ConflictText.label("bets[0].main", LotteryType.DLT))
        assertEquals("第 1 注 蓝球", ConflictText.label("bets[0].second", LotteryType.SSQ))
        assertEquals("第 1 注 后区", ConflictText.label("bets[0].second", LotteryType.DLT))
    }

    @Test
    fun `彩种未知时退化成中性说法`() {
        assertEquals("第 1 注 主号码", ConflictText.label("bets[0].main", null))
        assertEquals("第 1 注 副号码", ConflictText.label("bets[0].second", null))
    }

    @Test
    fun `校验层用的 betN 下标形式也要认`() {
        // BetRules 产出的路径是 `bet[0].main`（没有 s），跟 TicketDiffer 的 `bets[0]` 不同
        assertEquals("第 2 注 红球", ConflictText.label("bet[1].main", LotteryType.SSQ))
    }

    @Test
    fun `号码组下标也能翻译`() {
        assertEquals("第 1 注 第 2 组 号码", ConflictText.label("bets[0].groups[1].numbers", LotteryType.SSQ))
        assertEquals("第 1 注 第 1 组 号码组名称", ConflictText.label("bets[0].groups[0].name", LotteryType.SSQ))
    }

    @Test
    fun `票面编号有专门的说法`() {
        assertEquals("票面编号 · 验票码", ConflictText.label("ticket_identifiers.verification_code"))
        assertEquals("票面编号 · 站点号", ConflictText.label("ticket_identifiers.station_number"))
    }

    @Test
    fun `认不出来的路径原样返回 不显示空白`() {
        // 将来加了新字段忘补翻译时，至少还能看到一个标识，不至于什么都没提示
        val unknown = "some_new_field"
        assertEquals(unknown, ConflictText.label(unknown))
        assertTrue(ConflictText.label("bets[0].weird_field").isNotBlank())
    }
}
