package com.bro.lotteryledger.repo

import com.bro.lotteryledger.core.DrawNumbers
import com.bro.lotteryledger.core.LotteryType
import com.bro.lotteryledger.core.NoticeKind
import com.bro.lotteryledger.core.PrizeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 核验提示的**颜色**映射。
 *
 * 少爷 2026-09-27 的原话：
 * > 红色提示中奖，绿色提示未中奖，黄色提示彩票过期。
 *
 * 这件事看着像纯 UI，其实必须单测 —— 因为「颜色配错」不会让任何东西崩掉，
 * 只会让用户**把坏消息当好消息**（比如「核验失败」配成红色，
 * 跟「中了 15 块」一个色）。这种错只有断言能拦住。
 */
class PrizeNoticeTest {

    private fun won(
        units: Int = 1,
        prizeYuan: Double = 10.0,
        needManualConfirm: Boolean = false,
        expiredUnclaimed: Boolean = false
    ) = PrizeService.Outcome.Won(
        result = PrizeResult(
            levels = emptyList(),
            draw = DrawNumbers(
                type = LotteryType.SSQ,
                issue = "2026100",
                drawDate = "2026-09-24",
                main = listOf("01", "02", "03", "04", "05", "06"),
                second = listOf("07")
            ),
            poolHigh = false,
            multiple = 1,
            additional = false
        ),
        prizeYuan = prizeYuan,
        needManualConfirm = needManualConfirm,
        officialFloatingUsed = false,
        expiredUnclaimed = expiredUnclaimed
    )

    // ---------- 单张票 ----------

    @Test
    fun `中奖是红色`() {
        val n = PrizeNotice.describe(won())
        assertEquals(NoticeKind.WIN, n.kind)
        assertTrue("得说清中了多少", n.text.contains("奖金"))
    }

    @Test
    fun `一等奖待确认也是红色`() {
        val n = PrizeNotice.describe(won(needManualConfirm = true))
        assertEquals(NoticeKind.WIN, n.kind)
        assertTrue("要提示用户去确认金额", n.text.contains("待你确认"))
    }

    @Test
    fun `未中奖是绿色`() {
        val n = PrizeNotice.describe(PrizeService.Outcome.NotWon)
        assertEquals(NoticeKind.LOSE, n.kind)
        assertEquals("这张票没中奖", n.text)
    }

    @Test
    fun `中奖和未中奖的颜色必须不同`() {
        // 这条是本次改动的全部意义 —— 以前两者都是同一个灰黑块
        assertNotEquals(
            PrizeNotice.describe(won()).kind,
            PrizeNotice.describe(PrizeService.Outcome.NotWon).kind
        )
    }

    @Test
    fun `过期作废的中奖是黄色 不是红色`() {
        val n = PrizeNotice.describe(won(units = 2, prizeYuan = 30.0, expiredUnclaimed = true))
        assertEquals("钱拿不到了，不能被当成好消息", NoticeKind.WARN, n.kind)
        assertTrue("必须说清是作废了", n.text.contains("作废"))
        assertTrue("金额还是要显示出来", n.text.contains("30.00"))
    }

    @Test
    fun `核验失败是暗橙 不能是红色`() {
        val n = PrizeNotice.describe(PrizeService.Outcome.Failed("网络超时"))
        assertEquals(NoticeKind.ERROR, n.kind)
        assertNotEquals(
            "失败跟中奖同色会被扫一眼当成好消息",
            NoticeKind.WIN,
            n.kind
        )
        assertTrue(n.text.contains("核验失败"))
    }

    @Test
    fun `官方还没出结果是中性色 不吓唬人`() {
        // 22:30 查、公告可能 22:35 才出，这是正常情况，会自己重试
        val n = PrizeNotice.describe(PrizeService.Outcome.AwaitingResult("官方还没公布"))
        assertEquals(NoticeKind.NEUTRAL, n.kind)
    }

    @Test
    fun `查不到这一期是黄色 因为要人处理`() {
        val n = PrizeNotice.describe(PrizeService.Outcome.ResultUnavailable("查不到"))
        assertEquals(NoticeKind.WARN, n.kind)
    }

    @Test
    fun `还没到开奖日是中性色`() {
        assertEquals(NoticeKind.NEUTRAL, PrizeNotice.describe(PrizeService.Outcome.NotDue).kind)
    }

    // ---------- 批量汇总 ----------

    @Test
    fun `一批里只要有中奖就是红色`() {
        val n = PrizeNotice.summarize(
            listOf(won(), PrizeService.Outcome.NotWon, PrizeService.Outcome.Failed("x"))
        )
        assertEquals("有奖就该抢眼", NoticeKind.WIN, n.kind)
    }

    @Test
    fun `全都没中才是绿色`() {
        val n = PrizeNotice.summarize(
            listOf(PrizeService.Outcome.NotWon, PrizeService.Outcome.NotWon)
        )
        assertEquals(NoticeKind.LOSE, n.kind)
    }

    @Test
    fun `全是过期作废的中奖算黄色`() {
        val n = PrizeNotice.summarize(listOf(won(expiredUnclaimed = true)))
        assertEquals(NoticeKind.WARN, n.kind)
    }

    @Test
    fun `有失败没中奖时优先报失败`() {
        val n = PrizeNotice.summarize(
            listOf(PrizeService.Outcome.NotWon, PrizeService.Outcome.Failed("timeout"))
        )
        assertEquals(NoticeKind.ERROR, n.kind)
    }

    @Test
    fun `空批次是中性色`() {
        assertEquals(NoticeKind.NEUTRAL, PrizeNotice.summarize(emptyList()).kind)
    }

    @Test
    fun `汇总文字必须和 PrizeService 逐字一致`() {
        // 颜色是 PrizeNotice 加的，文字仍然以 PrizeService.summarize 为唯一来源 ——
        // 两份文案分家就会出现「说的和显示的不是一回事」
        val outcomes = listOf(
            won(),
            PrizeService.Outcome.NotWon,
            PrizeService.Outcome.AwaitingResult("还没出"),
            PrizeService.Outcome.ResultUnavailable("查不到"),
            PrizeService.Outcome.Failed("网络错误")
        )
        assertEquals(PrizeService.summarize(outcomes), PrizeNotice.summarize(outcomes).text)
    }

    @Test
    fun `几个动作提示的颜色`() {
        assertEquals(NoticeKind.LOSE, PrizeNotice.markedNotWon().kind)
        assertEquals(NoticeKind.WIN, PrizeNotice.amountConfirmed(5000.0).kind)
        assertEquals(NoticeKind.WIN, PrizeNotice.markedWonManually(15.0).kind)
        assertEquals(NoticeKind.WIN, PrizeNotice.markedRedeemed().kind)
        assertEquals(NoticeKind.WIN, PrizeNotice.redeemedAll(3, 45.0).kind)
        // 没有待兑的票不是「中了」，别上红色
        assertEquals(NoticeKind.NEUTRAL, PrizeNotice.redeemedAll(0, 0.0).kind)
    }
}
