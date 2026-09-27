package com.bro.lotteryledger.repo

import android.content.Context
import com.bro.lotteryledger.core.*
import com.bro.lotteryledger.db.TicketEntity
import com.bro.lotteryledger.net.DrawResultClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 对奖服务：查开奖 → 判奖 → 回写票状态。
 *
 * ## 状态流转（§13）
 * ```
 * 未到开奖日           → 不动
 * 到了开奖日但查不到    → AWAITING_RESULT（等官方出结果）
 * 查到结果 · 未中       → NOT_WON
 * 查到结果 · 中固定奖    → TO_REDEEM + prize_amount
 * 查到结果 · 中一二等奖  → PRIZE_PENDING + prize_amount 预填官方值
 * ```
 *
 * ## 一二等奖为什么不直接进 TO_REDEEM
 *
 * §15 要求「一、二等奖金额必须人工确认」。但**官方接口本身就公布了这个金额**
 * （实测：双色球返回 `prizegrades[0].typemoney`，大乐透返回 `stakeAmount`）。
 *
 * 折中做法：**把官方金额预填进去，但状态停在 PRIZE_PENDING**。
 * 这样用户不必自己去翻公告，同时又不会被当成已确定奖金 ——
 * 首页统计里 `pendingPrizeCount > 0` 时本来就不给净收支数字（§15 硬要求），
 * 所以不会出现「拿未确认金额冒充盈亏」的问题。
 */
class PrizeService(context: Context) {

    companion object {
        /**
         * 「官方还没出结果」最多等几天。
         *
         * 超过这个天数、接口里还是查不到这一期，就不再认为是「还没公布」，
         * 而是判定为**查不到**（期号读错 / 超出可查范围），
         * 转成 [TicketStatus.RESULT_UNAVAILABLE] 交给人工处理。
         *
         * 为什么是 3 天：正常开奖当晚 21:30 出、公告 21:35 前后，
         * 我们的定时任务 22:30 查、次日 8:30 补查。
         * 留 3 天余量，能容忍网络故障、官方接口临时异常、节假日延迟。
         */
        const val STALE_DAYS = 3L

        /**
         * 开奖时间是否已过。
         *
         * 当天开奖的按 **22:00** 之后才算过点 —— 官方 21:30 开奖、公告通常 21:35 前后出，
         * 但为了避免刚开奖就跑去查拿到空结果，留一点缓冲。
         * 入账时用这个判断「要不要立刻核验」，批量核验也用它。
         *
         * [today] / [now] 可注入，方便单测 —— 这个判断直接决定用户体验
         * （判早了白跑网络，判晚了用户得干等到 22:30），必须有测试钉住。
         */
        fun isDrawTimePassed(
            drawDate: String?,
            today: LocalDate = LocalDate.now(),
            now: java.time.LocalTime = java.time.LocalTime.now()
        ): Boolean {
            val d = RedemptionDeadline.parse(drawDate) ?: return false
            if (d.isBefore(today)) return true
            if (d.isAfter(today)) return false
            return now.hour >= 22
        }

        /**
         * 开奖日已经过去多久（天）。开奖日在未来或读不出来时返回 0。
         */
        fun daysSinceDraw(
            drawDate: String?,
            today: LocalDate = LocalDate.now()
        ): Long {
            val d = RedemptionDeadline.parse(drawDate) ?: return 0L
            return java.time.temporal.ChronoUnit.DAYS.between(d, today).coerceAtLeast(0L)
        }

        /**
         * 把一批核验结果总结成一句话。
         *
         * ⚠️ 这里以前只分「中奖 / 失败 / 都没中」，把「官方还没公布」和
         * 「接口里查不到这一期」都归进了**「都没中」**。
         * 少爷就是因为看到「核验了 3 张，都没中」、但列表还挂着
         * 「等待开奖结果」才发现不对 —— 这两类根本不是「没中」，
         * 一个是还没出、一个是查不到，都得如实说出来。
         */
        fun summarize(outcomes: List<Outcome>): String {
            if (outcomes.isEmpty()) return "没有待核验的票"

            val won = outcomes.count { it is Outcome.Won }
            val notWon = outcomes.count { it is Outcome.NotWon }
            val awaiting = outcomes.count { it is Outcome.AwaitingResult }
            val unavailable = outcomes.count { it is Outcome.ResultUnavailable }
            val notDue = outcomes.count { it is Outcome.NotDue }
            val failed = outcomes.count { it is Outcome.Failed }

            val parts = mutableListOf<String>()
            if (won > 0) parts += "$won 张中奖"
            if (notWon > 0) parts += "$notWon 张未中"
            if (awaiting > 0) parts += "$awaiting 张官方还没公布"
            if (unavailable > 0) parts += "$unavailable 张查不到结果（需手动处理）"
            if (notDue > 0) parts += "$notDue 张还没到开奖日"
            if (failed > 0) parts += "$failed 张查询失败"

            return "核验了 ${outcomes.size} 张：" +
                parts.ifEmpty { listOf("没有变化") }.joinToString("，")
        }
    }

    private val repo = LedgerRepository(context)
    private val draws = DrawResultClient()

    /**
     * 该彩种**最近一期**的开奖号码。
     *
     * 给「号码撞上本期大奖」的彩蛋用（[com.bro.lotteryledger.core.JackpotTease]）。
     * 走 [DrawResultClient] 的缓存，所以不会为了彩蛋多跑一次网络。
     *
     * ⚠️ 这里**只取号码，不碰任何票** —— 彩蛋绝不能改票的状态或金额。
     */
    suspend fun latestDraw(type: LotteryType): DrawNumbers? = withContext(Dispatchers.IO) {
        try {
            draws.fetchRecent(type).maxByOrNull { it.drawDate }
        } catch (e: Exception) {
            LedgerLog.w("Tease", "取最近一期开奖失败（不影响任何功能）：${e.message}")
            null
        }
    }

    /**
     * 一次核验的结果。
     */
    sealed interface Outcome {
        /**
         * 中了。
         *
         * @param needManualConfirm 含一二等奖，待用户确认金额
         * @param expiredUnclaimed **兑奖截止日已过**（录入时就已经过期的老票）——
         *        中奖但拿不到钱，直接记「过期未兑账」，不进「待兑奖」
         */
        data class Won(
            val result: PrizeResult,
            val prizeYuan: Double,
            val needManualConfirm: Boolean,
            val officialFloatingUsed: Boolean,
            val expiredUnclaimed: Boolean = false
        ) : Outcome

        data object NotWon : Outcome

        /** 到了开奖日，但官方还没出结果（一般 21:30 开奖、22:30 应该就有了） */
        data class AwaitingResult(val message: String) : Outcome

        /**
         * 开奖日已经过去好几天，官方接口里依然查不到这一期。
         *
         * 跟 [AwaitingResult] 的区别是**要不要继续自动重试**：
         * 这个状态不会再被自动核验（重试也不会变），需要人工介入 ——
         * 多半是期号被读错了。
         */
        data class ResultUnavailable(val message: String) : Outcome

        /** 还没到开奖日 */
        data object NotDue : Outcome

        data class Failed(val message: String) : Outcome
    }

    /**
     * 核验一张票。
     *
     * @param force true 时无视「还没到开奖日」直接查（手动刷新用）
     */
    suspend fun check(ticketId: Long, force: Boolean = false): Outcome = withContext(Dispatchers.IO) {
        val ticket = repo.ticket(ticketId)
            ?: return@withContext Outcome.Failed("找不到这张票")

        // 已兑奖 / 已过期的票不再核验
        val st = TicketStatus.from(ticket.ticketStatus)
        if (st == TicketStatus.REDEEMED || st == TicketStatus.EXPIRED_UNCLAIMED) {
            return@withContext Outcome.Failed("这张票已经兑过奖或已过期")
        }

        val type = LotteryType.from(ticket.lotteryType)
            ?: return@withContext Outcome.Failed("彩种无法识别")

        if (!force && !isDrawTimePassed(ticket.drawDate)) {
            return@withContext Outcome.NotDue
        }

        LedgerLog.i("Prize", "开始核验：${type.display} 第 ${ticket.issue} 期（票 #$ticketId）")

        val lookup = draws.lookup(type, ticket.issue)

        val fetched = when (lookup) {
            is DrawResultClient.Lookup.RequestFailed -> {
                LedgerLog.e("Prize", "查询开奖失败：${lookup.message}")
                return@withContext Outcome.Failed("查询开奖失败：${lookup.message}")
            }

            is DrawResultClient.Lookup.Found -> lookup.data

            DrawResultClient.Lookup.NotInList -> {
                // 接口通了，但名单里没有这一期。两种可能，必须分开处理 ——
                // 这就是少爷那三张 4 月份票卡住的根因：以前一律当成「还没公布」。
                val days = daysSinceDraw(ticket.drawDate)
                if (days > STALE_DAYS) {
                    val msg = "官方接口里查不到第 ${ticket.issue} 期的记录" +
                        "（票面开奖日 ${ticket.drawDate}，已经过去 $days 天）。"
                    markUnavailable(ticket, msg)
                    LedgerLog.w("Prize", "票 #$ticketId：$msg")
                    return@withContext Outcome.ResultUnavailable(msg)
                }
                // 开奖日刚过 → 官方确实还没挂出来，这个很正常
                val msg = "官方还没公布第 ${ticket.issue} 期结果，稍后会自动重试"
                markAwaiting(ticket, msg)
                LedgerLog.w("Prize", msg)
                return@withContext Outcome.AwaitingResult(msg)
            }
        }

        // 把开奖结果存库（同一期多张票共用）
        repo.saveDrawResult(fetched.numbers, fetched.poolHigh)
        LedgerLog.i(
            "Prize",
            "拿到开奖：${fetched.numbers.main + fetched.numbers.second} " +
                "奖池=${fetched.poolYuan?.let { "%.2f亿".format(it / 1e8) } ?: "未知"} " +
                "升档=${fetched.poolHigh}"
        )

        val raw = repo.loadRaw(ticketId)
        if (raw == null || raw.bets.isEmpty()) {
            return@withContext Outcome.Failed("这张票没有可用的投注号码，无法判奖")
        }

        val result = PrizeRules.judge(
            type = type,
            bets = raw.bets,
            draw = fetched.numbers,
            multiple = raw.multiple,
            additional = raw.additional,
            poolHigh = fetched.poolHigh
        )

        if (!result.won) {
            update(ticket, TicketStatus.NOT_WON, prizeAmount = null)
            LedgerLog.i("Prize", "未中奖（票 #$ticketId）")
            return@withContext Outcome.NotWon
        }

        // 计算奖金：固定奖直接算；浮动奖用官方公布的单注金额 × 注数
        val floatingTotal = result.levels.filter { it.floating }.sumOf { lv ->
            (fetched.officialMoney[lv.level] ?: 0.0) * lv.units
        }
        val total = result.fixedTotalYuan + floatingTotal
        val needConfirm = result.hasFloating

        // ---- 少爷 2026-09-27：录入时**就已经过期**的票要一步到位 ----
        //
        // 场景：今天补录 4 月的票，它的兑奖截止日 6 月就过了。
        // 如果按常规先标「待兑奖」，有两个坏处：
        //  ① 「待兑奖」的语义是"还能去兑" → 用户会拿着一张废票白跑网点
        //  ② 它会被算进「待兑」金额，看着像一笔能拿到的钱
        //
        // 两类过期票的分工：
        //  - 录入时没过期、之后过期 → 由 sweepExpired 处理（它扫的正是 TO_REDEEM）
        //  - 录入时就已经过期（本节）→ 核验当场标过期，不经过 TO_REDEEM
        val alreadyExpired = RedemptionDeadline.compute(ticket.drawDate)?.expired == true

        if (alreadyExpired) {
            landAfterWin(ticket, if (total > 0) total else null)
            LedgerLog.w(
                "Prize",
                "票 #$ticketId 中了「${PrizeRules.levelName(type, result.bestLevel ?: 0)}」" +
                    "（${"%.2f".format(total)} 元），但录进来时兑奖截止日就已经过了 " +
                    "→ 直接标为「已过期未兑奖」"
            )
            return@withContext Outcome.Won(
                result = result,
                prizeYuan = total,
                // 过期了就不必再让人确认金额 —— 确认了也拿不到
                needManualConfirm = false,
                officialFloatingUsed = floatingTotal > 0,
                expiredUnclaimed = true
            )
        }

        val status = if (needConfirm) TicketStatus.PRIZE_PENDING else TicketStatus.TO_REDEEM
        update(
            ticket,
            status,
            prizeAmount = if (total > 0) total else null
        )

        LedgerLog.i(
            "Prize",
            "中奖！最高${PrizeRules.levelName(type, result.bestLevel ?: 0)}，" +
                "共 ${result.units} 注，金额 ${"%.2f".format(total)} 元" +
                (if (needConfirm) "（含浮动奖，用官方公布值预填，待确认）" else "")
        )

        Outcome.Won(
            result = result,
            prizeYuan = total,
            needManualConfirm = needConfirm,
            officialFloatingUsed = floatingTotal > 0
        )
    }

    /**
     * 批量核验前的准备：清掉开奖查询缓存，保证拿到的是最新公告。
     */
    suspend fun checkAllPending(): List<Pair<Long, Outcome>> = withContext(Dispatchers.IO) {
        draws.clearCache()
        // needsCheck 的语义就是「已过开奖日期、等待获取开奖结果」
        val pending = repo.needsCheck()
        LedgerLog.i("Prize", "待核验 ${pending.size} 张")
        pending.map { t -> t.id to check(t.id) }
    }

    /**
     * 手动把一张票标记为「未中奖」。
     *
     * 给 [TicketStatus.RESULT_UNAVAILABLE] 的票一个出口 ——
     * 接口里查不到这一期时，用户对着票和官方公告自己确认没中，
     * 就应该能把它从「悬而未决」里拿出来，而不是永远挂着。
     */
    suspend fun markNotWon(ticketId: Long) = withContext(Dispatchers.IO) {
        val t = repo.ticket(ticketId) ?: return@withContext
        update(t, TicketStatus.NOT_WON, prizeAmount = null)
        LedgerLog.i("Prize", "用户手动把票 #$ticketId 标记为未中奖")
    }

    /**
     * 手动认定中奖并填金额（查不到结果但实为中奖时用）。
     */
    suspend fun markWonManually(ticketId: Long, amountYuan: Double) = withContext(Dispatchers.IO) {
        val t = repo.ticket(ticketId) ?: return@withContext
        // 走统一的落点判定：手动记中奖时若兑奖期已过，同样直接进「过期未兑」
        landAfterWin(t, amountYuan)
        LedgerLog.i("Prize", "用户手动把票 #$ticketId 记为中奖 $amountYuan 元")
    }

    /** 一键兑奖的结果。 */
    data class RedeemSummary(val count: Int, val amount: Double)

    /**
     * 一键兑奖：把**所有「已中奖待兑」的票**一次性标记为已兑奖。
     *
     * 少爷 2026-09-27 要求。对应真实场景：去网点时兜里揣着一叠票，
     * 一次全兑了，回来不想一张张点。
     *
     * ## 只处理 TO_REDEEM，绝不扩大范围
     *
     * - `PRIZE_PENDING`（一、二等奖待确认金额）**不碰** ——
     *   §15 要求金额必须人工确认，自动兑掉等于绕过这条硬要求，
     *   而且一旦标成"已兑"金额就定死了。
     * - `EXPIRED_UNCLAIMED`（已作废）**不碰** —— 那些钱已经没了。
     * - 未中奖的票**不碰**。
     *
     * 金额用票上的 `prizeAmount`（应得），和逐张兑奖的口径一致。
     * 单张的实得金额用户之后仍可在详情页改。
     */
    suspend fun redeemAll(): RedeemSummary = withContext(Dispatchers.IO) {
        val list = repo.ticketsByStatus(TicketStatus.TO_REDEEM.name)
        var total = 0.0
        val now = System.currentTimeMillis()
        list.forEach { t ->
            val amount = t.prizeAmount ?: 0.0
            repo.updateTicket(
                t.copy(
                    ticketStatus = TicketStatus.REDEEMED.name,
                    redeemedAmount = amount,
                    redeemedAt = now,
                    updatedAt = now
                )
            )
            total += amount
        }
        LedgerLog.i(
            "Prize",
            "一键兑奖：${list.size} 张，合计 ${"%.2f".format(total)} 元"
        )
        RedeemSummary(list.size, total)
    }

    /**
     * 中奖之后的**落点**：兑奖期没过 → 「待兑奖」；已经过了 → 「过期未兑奖」。
     *
     * 三条路都要走这里，避免各写一份：
     *  - 自动核验判定中奖
     *  - 用户确认一二等奖金额
     *  - 用户手动记中奖（查不到结果时）
     *
     * 分错的后果很实在：「待兑奖」的语义是"还能去兑"，
     * 一张已经过期的票挂在里面，用户会白跑一趟网点，
     * 而且「待兑」金额看着像一笔能拿到的钱。
     */
    private suspend fun landAfterWin(t: TicketEntity, amountYuan: Double?) {
        val expired = RedemptionDeadline.compute(t.drawDate)?.expired == true
        if (expired) {
            repo.updateTicket(
                t.copy(
                    ticketStatus = TicketStatus.EXPIRED_UNCLAIMED.name,
                    // prize_amount 保留：界面要显示"作废了多少钱"
                    // （统计里已排除它，见 TicketDao.stats 的注释）
                    prizeAmount = amountYuan,
                    expiredUnclaimedAmount = amountYuan,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            update(t, TicketStatus.TO_REDEEM, prizeAmount = amountYuan)
        }
    }

    /** 用户确认一二等奖金额（确认后才算「已确定」）。 */
    suspend fun confirmAmount(ticketId: Long, amountYuan: Double) = withContext(Dispatchers.IO) {
        val t = repo.ticket(ticketId) ?: return@withContext
        landAfterWin(t, amountYuan)
        LedgerLog.i("Prize", "用户确认票 #$ticketId 中奖金额为 $amountYuan 元")
    }

    /**
     * 标记「已兑奖」（用户拿着票去网点兑完了）。
     *
     * 为什么不覆盖 `prizeAmount`：
     * 统计 SQL 里 `prizeConfirmed` 靠 `prize_amount` 算「应得」，
     * `redeemed` 靠 `redeemed_amount` 算「实际到手」。两个口径分开记，
     * 将来若发现应得和到手对不上（税、被网点打折之类）也能一眼看出来。
     */
    suspend fun markRedeemed(ticketId: Long, amountYuan: Double? = null) =
        withContext(Dispatchers.IO) {
            val t = repo.ticket(ticketId) ?: return@withContext
            val amount = amountYuan ?: t.prizeAmount
            repo.updateTicket(
                t.copy(
                    ticketStatus = TicketStatus.REDEEMED.name,
                    redeemedAmount = amount,
                    redeemedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            LedgerLog.i("Prize", "票 #$ticketId 标记已兑奖，到手 ${amount ?: 0.0} 元")
        }

    /**
     * 把已过兑奖截止日、还没兑的票改成「已过期未兑奖」。
     *
     * 只在启动/核验后顺手扫一遍，不做精确定时——过期是个**事实**不是事件，
     * 早一小时晚一小时标出来都不影响用户判断。
     */
    suspend fun sweepExpired(): Int = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        var n = 0
        // needsCheck 之外的票也要看，所以直接扫待兑奖的
        repo.ticketsByStatus(TicketStatus.TO_REDEEM.name).forEach { t ->
            val info = RedemptionDeadline.compute(t.drawDate, today) ?: return@forEach
            if (info.expired) {
                repo.updateTicket(
                    t.copy(
                        ticketStatus = TicketStatus.EXPIRED_UNCLAIMED.name,
                        expiredUnclaimedAmount = t.prizeAmount,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                n++
                LedgerLog.w("Prize", "票 #${t.id} 已过兑奖截止日 ${info.deadline}，标记为过期未兑奖")
            }
        }
        n
    }

    // ---------------- 内部 ----------------

    private suspend fun markAwaiting(t: TicketEntity, _msg: String) {
        if (TicketStatus.from(t.ticketStatus) == TicketStatus.AWAITING_RESULT) return
        update(t, TicketStatus.AWAITING_RESULT, t.prizeAmount)
    }

    /**
     * 标记「查不到开奖结果」。
     *
     * 这个状态**不在待核验队列里**（`needingCheck` 的 SQL 只捞
     * PENDING_DRAW / AWAITING_RESULT），所以它不会被反复自动重试 ——
     * 否则就是之前那个死循环：查不到 → 标等待 → 又被捞出来查 → 又查不到。
     */
    private suspend fun markUnavailable(t: TicketEntity, _msg: String) {
        if (TicketStatus.from(t.ticketStatus) == TicketStatus.RESULT_UNAVAILABLE) return
        update(t, TicketStatus.RESULT_UNAVAILABLE, t.prizeAmount)
    }

    private suspend fun update(t: TicketEntity, status: TicketStatus, prizeAmount: Double?) {
        val deadline = RedemptionDeadline.compute(t.drawDate)
        repo.updateTicket(
            t.copy(
                ticketStatus = status.name,
                prizeAmount = prizeAmount,
                claimDeadline = deadline?.let { RedemptionDeadline.format(it.deadline) },
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}
