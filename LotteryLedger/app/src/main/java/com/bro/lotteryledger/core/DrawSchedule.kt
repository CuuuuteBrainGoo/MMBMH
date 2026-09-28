package com.bro.lotteryledger.core

import java.time.LocalDate
import java.time.LocalTime

/**
 * 开奖时刻的判定（纯函数、零依赖）。
 *
 * ## 为什么必须单独抽成一个对象
 *
 * 「这张票现在算不算已经开奖了」这件事，有**至少三个消费者**：
 *
 *  1. [com.bro.lotteryledger.repo.PrizeService.isDrawTimePassed] —— 核验前问「能不能去查了」
 *  2. [com.bro.lotteryledger.repo.LedgerRepository.needsCheck] —— 首页问「哪些票该进待核验队列」
 *  3. 列表 / 详情页的状态徽章 —— 展示「等待开奖结果」还是「未开奖」
 *
 * 只要它们各写一份判断，就一定会漂移。**2026-09-28 就是这么翻车的**：
 * 核验层早就知道「当天要 22:00 之后才算过点」，但查询层只比了日期字符串
 * （`draw_date <= today`）→ 大乐透开奖日当天下午 18:12，首页就弹
 * 「有 1 张彩票已过开奖日期」—— 而当晚 21:30 才开奖。
 *
 * 所以：**口径在本对象里定义一次，所有地方都来调它。**
 */
object DrawSchedule {

    /**
     * 开奖日当天的「算过点」小时。
     *
     * 官方 21:30 开奖、公告通常 21:35 前后出。留一点缓冲到 **22 点** ——
     * 刚开奖就跑去查大概率拿到空结果，反而让用户以为出问题了。
     */
    const val DRAW_HOUR = 22

    /**
     * 开奖时间是否已过。
     *
     * - 开奖日在今天之前 → 已过
     * - 开奖日在今天之后 → 未过
     * - 开奖日就是今天   → **22:00 之后才算过**
     *
     * 开奖日读不出来时返回 `false`（保守）—— 不能因为日期没读到就去打网络。
     *
     * [today] / [now] 可注入，方便单测把边界钉死。
     */
    fun drawTimePassed(
        drawDate: String?,
        today: LocalDate = LocalDate.now(),
        now: LocalTime = LocalTime.now()
    ): Boolean {
        val d = RedemptionDeadline.parse(drawDate) ?: return false
        if (d.isBefore(today)) return true
        if (d.isAfter(today)) return false
        return now.hour >= DRAW_HOUR
    }

    /**
     * 待核验队列的**截止日**（给 SQL 的 `draw_date <= :cutoff` 用）。
     *
     * 语义就是「有哪些日期的票已经真的过了开奖时间」：
     *
     * - 已经过了当天 22:00 → 截止日 = **今天**（今天开奖的票可以查了）
     * - 还没到当天 22:00   → 截止日 = **昨天**（今天开奖的票先不算，等过了 22:00 再说）
     *
     * 这样 `draw_date <= cutoff` 就天然等价于「[drawTimePassed] 为 true」，
     * 不需要在 SQL 里拼时间，也不会漏掉跨月 / 跨年（[LocalDate.minusDays] 自己会算）。
     */
    fun checkCutoff(
        today: LocalDate = LocalDate.now(),
        now: LocalTime = LocalTime.now()
    ): String = (if (now.hour >= DRAW_HOUR) today else today.minusDays(1)).toString()

    /**
     * 展示用的「有效状态」。
     *
     * 库里存的 `AWAITING_RESULT` 只代表**录入当时**已经过了开奖时间；
     * 票一旦入库，状态不会因为时间流逝自己变。于是会出现：
     * 开奖日当天下午录入的票（当时还没到 22:00，但老代码按日期判成了
     * `AWAITING_RESULT`）**一直挂着「等待开奖结果」，直到被核验覆盖**。
     *
     * 这里在**展示时**把它修正回「未开奖」—— 纯计算，不写库。
     * 只影响 `AWAITING_RESULT` 一个状态，其它状态（已中奖 / 已兑奖 / 查不到……）
     * 都是真实结果留下的，绝不能动。
     */
    fun effectiveStatus(
        stored: TicketStatus,
        drawDate: String?,
        today: LocalDate = LocalDate.now(),
        now: LocalTime = LocalTime.now()
    ): TicketStatus =
        if (stored == TicketStatus.AWAITING_RESULT && !drawTimePassed(drawDate, today, now)) {
            TicketStatus.PENDING_DRAW
        } else {
            stored
        }
}
