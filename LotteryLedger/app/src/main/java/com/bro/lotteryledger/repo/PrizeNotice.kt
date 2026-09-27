package com.bro.lotteryledger.repo

import com.bro.lotteryledger.core.Notice
import com.bro.lotteryledger.core.NoticeKind

/**
 * 把核验结果翻译成「给用户看的一句话 + 该用什么颜色」。
 *
 * ## 为什么从 MainViewModel 搬到这里
 *
 * 原来这段文案是 `MainViewModel` 里的一个 private 方法，改错了没人拦得住 ——
 * 事实上就错过一次：把「官方还没公布」和「查不到这一期」一起说成了**「都没中」**，
 * 少爷看到「3 张都没中」但列表还挂着「等待开奖结果」，才发现不对。
 *
 * 搬成纯函数（没有 Android 依赖、没有 ViewModel 状态）之后就能单测钉住：
 * **什么情况该说什么话、该上什么色**。
 */
object PrizeNotice {

    /** 单张票的核验结果 → 一条提示。 */
    fun describe(o: PrizeService.Outcome): Notice = when (o) {
        is PrizeService.Outcome.Won -> when {
            // 录入时就已经过了兑奖期 —— 必须说清「中了但拿不到」，
            // 否则用户会以为还有一笔钱可以去网点兑。黄色：要你知道，但已成事实。
            o.expiredUnclaimed -> Notice(
                "中了 ${o.result.units} 注（¥${"%.2f".format(o.prizeYuan)}），" +
                    "但录进来时兑奖期就已经过了 —— 这笔钱作废了",
                NoticeKind.WARN
            )
            o.needManualConfirm -> Notice(
                "中了 ${o.result.units} 注！官方公布奖金 ¥${"%.2f".format(o.prizeYuan)}，待你确认",
                NoticeKind.WIN
            )
            else -> Notice(
                "中了 ${o.result.units} 注，奖金 ¥${"%.2f".format(o.prizeYuan)}",
                NoticeKind.WIN
            )
        }

        PrizeService.Outcome.NotWon -> Notice("这张票没中奖", NoticeKind.LOSE)

        // 「官方还没出结果」是正常情况（22:30 查、公告可能 22:35 才出），
        // 会自己重试，不该吓唬用户 —— 中性色。
        is PrizeService.Outcome.AwaitingResult -> Notice(o.message)

        // 「查不到这一期」不会自己好了，要人处理 —— 黄色。
        is PrizeService.Outcome.ResultUnavailable -> Notice(o.message, NoticeKind.WARN)

        PrizeService.Outcome.NotDue -> Notice("还没到开奖日，等到开奖后再来")

        // 失败用暗橙，**不用红** —— 红在这套配色里专门表示「中奖」，
        // 「核验失败」用红会被扫一眼当成好消息。
        is PrizeService.Outcome.Failed -> Notice("核验失败：${o.message}", NoticeKind.ERROR)
    }

    /**
     * 一批核验结果 → 一条汇总提示（文字来自 [PrizeService.summarize]，这里只负责定色）。
     *
     * 定色优先级：**中奖 > 过期作废 > 失败 > 全没中 > 查不到 > 其它**。
     * 一批里只要有一张中了，整条就该是红的 —— 那是最该被看见的信息。
     */
    fun summarize(outcomes: List<PrizeService.Outcome>): Notice {
        val text = PrizeService.summarize(outcomes)
        val kind = when {
            outcomes.isEmpty() -> NoticeKind.NEUTRAL
            outcomes.any { it is PrizeService.Outcome.Won && !it.expiredUnclaimed } -> NoticeKind.WIN
            outcomes.any { it is PrizeService.Outcome.Won } -> NoticeKind.WARN
            outcomes.any { it is PrizeService.Outcome.Failed } -> NoticeKind.ERROR
            outcomes.all { it is PrizeService.Outcome.NotWon } -> NoticeKind.LOSE
            outcomes.any { it is PrizeService.Outcome.ResultUnavailable } -> NoticeKind.WARN
            else -> NoticeKind.NEUTRAL
        }
        return Notice(text, kind)
    }

    /** 手动把票标成未中奖。 */
    fun markedNotWon(): Notice = Notice("已标记为未中奖", NoticeKind.LOSE)

    /** 确认一、二等奖金额。 */
    fun amountConfirmed(amountYuan: Double): Notice =
        Notice("已确认奖金 ¥${"%.2f".format(amountYuan)}", NoticeKind.WIN)

    /** 手动认定中奖。 */
    fun markedWonManually(amountYuan: Double): Notice =
        Notice("已记为中奖 ¥${"%.2f".format(amountYuan)}", NoticeKind.WIN)

    /** 标记已兑奖。 */
    fun markedRedeemed(): Notice =
        Notice("已标记兑奖，记入已兑金额", NoticeKind.WIN)

    /** 一键兑奖的结果。 */
    fun redeemedAll(count: Int, amountYuan: Double): Notice = if (count == 0) {
        Notice("没有待兑奖的票")
    } else {
        Notice("已兑 $count 张，合计 ¥${"%.2f".format(amountYuan)}", NoticeKind.WIN)
    }
}
