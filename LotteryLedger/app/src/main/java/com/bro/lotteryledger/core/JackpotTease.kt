package com.bro.lotteryledger.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * 「号码撞上本期大奖」的调侃彩蛋（少爷 2026-09-27 第 4 条 —— **这是他的真实意图**）。
 *
 * ## 先说清楚这个功能**不是**什么
 *
 * 它**不是中奖提示**。少爷的原话：
 *
 * > 我只想提示用户，以前购买过的一个号码（已核验中奖情况）意外和本期的大奖号码一样，
 * > **并不是真的中了本期的大奖**。跟用户开个小玩笑，或者嘲笑一下用户（只有我自己）。
 *
 * 场景是：你三个月前买过一组号码，当时**什么也没中**。
 * 而今天这一期摇出来的一等奖号码，**恰好就是你那组**。
 * 同一组号码，换个日子，命运完全不同 —— 这里要的是**扎心**，不是庆祝。
 *
 * ## 所以它绝不碰账目
 *
 * 本类**只产出一段文案 + 展示用的号码**，不改任何票的状态、不动任何金额。
 * 那张往期的票在账上仍然是「未中奖」（如果它真中了，那是由 [PrizeRules] 判的、
 * 早就记好了）。**别把彩蛋和判奖搅在一起** —— 这是最容易搞混的地方。
 *
 * ## 判据
 *
 * 一注号码 = 本期的一等奖号码，需要：
 *  1. 同一个彩种（双色球的号和大乐透的号不能混比）
 *  2. **主区号码集合相同** 且 **次区号码集合相同**（用集合比，不管书写顺序）
 *  3. 那一注所属的票**不是本期这一期**的 —— 本期自己买的就算真中奖了，
 *     不是"巧合"，那种情况该走 [PrizeRules] + 奖金确认，不该在这里调侃
 *
 * ## 每条评语都有点损，但只损一个人
 *
 * 少爷说「只有我自己」用，所以配文不用客气。但**不许出现让人误以为中奖的表述** ——
 * 那样就变成"假好消息"，比不弹还糟。
 */
object JackpotTease {

    /** 库里的一注，附带它所属那张票的信息。 */
    data class PastBet(
        val ticketId: Long,
        val lotteryType: LotteryType,
        /** 这张票是第几期买的 */
        val issue: String,
        /** 那张票的开奖日 */
        val drawDate: String,
        val betIndex: Int,
        val main: List<String>,
        val second: List<String>
    )

    /** 一次调侃。 */
    data class Tease(
        val lotteryType: LotteryType,
        /** 撞上的那一期（本期） */
        val latestIssue: String,
        val latestDrawDate: String,
        /** 本期大奖号码（= 当期的开奖号码） */
        val main: List<String>,
        val second: List<String>,
        /** 你当初买它的那一期 */
        val pastIssue: String,
        val pastDrawDate: String,
        /** 是第几注 */
        val pastBetIndex: Int,
        /** 隔了多少天 */
        val daysBetween: Long,
        /** 配文 */
        val punchline: String
    ) {
        val numberLine: String
            get() = main.joinToString(" ") + "　+　" + second.joinToString(" ")
    }

    /**
     * 配文池。随机选一条 —— 同一个玩笑重复太多次就不叫玩笑了，
     * 而且这一期不会再弹第二次（去重在调用方，见 `MainViewModel`）。
     *
     * 少爷 2026-09-27 第 4 条要求「更夸张、玩笑更大、多设计几套」，
     * 所以这里从 5 条扩到 20 条，并按「损法」分成几路：
     *  - 就差一期 / 差一点类
     *  - 自嘲「手气」类
     *  - 「命运」哲学类
     *  - 玄学 / 建议类
     *
     * ⚠️ **红线**：一条都不许出现「中了 / 恭喜 / 中奖 / 发财」这类词。
     * 这是玩笑，不是报喜 —— 出现「中了」会变成假好消息，比不弹还糟。
     * 单测 `JackpotTeaseTest` 会扫全池，出现违禁词直接挂。
     */
    private val PUNCHLINES = listOf(
        // —— 就差一期 ——
        "就差一期。真的就差一期。",
        "一期之差，隔着半辈子。",
        "你和头奖的距离，是 7 天零 2 小时。",
        "号码没错，就是日子选错了。",
        "同一个号，晚买了一点点。这就是时差。",

        // —— 自嘲手气 ——
        "这组号码你以前买过。当时它什么也不是。",
        "你的号码是有灵性的：专挑开奖之后才发光。",
        "买的时候它睡了，开奖的时候它醒了。",
        "别人买不中是因为没选对，你是因为买早了。",
        "这手气，建议原地罚站三分钟反省一下。",
        "号码是好号码，就是跟错了主人。",
        "它在你手里是废号，在别人手里是头奖。",

        // —— 命运哲学 ——
        "同一组号码，换个日子，命运完全不同。",
        "号码没变，变的是世界。",
        "这不是错过，这是这组号码跟你缘分未到。",
        "你替别人试了号，人家收好就行。",

        // —— 玄学 / 建议 ——
        "下次开奖之后再来问我该买什么 —— 我保证这次是对的。",
        "建议下次照抄自己，但换一期。",
        "从今天起，这组号你留着别买，专门等它开。",
        "记住了：真正开出来的号码，永远是你上次写过、这次没买的那组。"
    )

    /**
     * 找有没有「往期号码撞上本期大奖」的巧合。
     *
     * ## 对比范围：本机全部记录，**不限官方能查到多少期**（少爷 2026-09-27 第 4 条明确）
     *
     * 少爷的原话：
     * > 只用最新的一期号码跟本机号码做对比筛选就可以了，没必要看官方 200 期。
     * > 用一组已知号码查本机记录多少期都可以。
     *
     * 也就是说：
     *  - **官方那边**只要**最新一期**那一组号码（[latest]）；
     *  - **本机这边**是[库里所有的注][pastBets]，**有多少期就比多少期**，
     *    本机记录存了三年就比三年，跟官方接口只返回 200 期这件事**没有任何关系**。
     *
     * ⚠️ 之前有人在验收清单里写过「官方只给 200 期，所以超出范围撞不上」——
     * **那句话是错的**。200 期限制的是「官方能给多少期开奖结果」，
     * 而这里拿来做对比的是**本机自己的历史**，本机历史没有上限。
     * 唯一要用到官方的地方，就是「本期的号码是什么」——那永远是最新一期，必然在 200 期内。
     *
     * @param latest   该彩种**最新一期**的开奖号码
     * @param pastBets 库里所有注（不限彩种与期数，函数内部会按彩种筛）
     * @param rng      随机源，注入以便单测确定化
     * @return 撞上了返回 [Tease]，否则 null（绝大多数情况）
     */
    fun find(
        latest: DrawNumbers,
        pastBets: List<PastBet>,
        rng: Random = Random.Default
    ): Tease? {
        val target = pastBets.firstOrNull { bet ->
            bet.lotteryType == latest.type &&
                // 本期自己买的票不算巧合 —— 那是真中奖，归 PrizeRules 管
                bet.issue != latest.issue &&
                bet.main.isNotEmpty() && bet.second.isNotEmpty() &&
                bet.main.toSet() == latest.main.toSet() &&
                bet.second.toSet() == latest.second.toSet()
        } ?: return null

        return Tease(
            lotteryType = latest.type,
            latestIssue = latest.issue,
            latestDrawDate = latest.drawDate,
            main = latest.main,
            second = latest.second,
            pastIssue = target.issue,
            pastDrawDate = target.drawDate,
            pastBetIndex = target.betIndex,
            daysBetween = daysBetween(target.drawDate, latest.drawDate),
            punchline = PUNCHLINES[rng.nextInt(PUNCHLINES.size)]
        )
    }

    /** 相隔天数（读不出日期返回 0）。 */
    private fun daysBetween(from: String?, to: String?): Long {
        val a = DateTimes.normalizeDate(from) ?: return 0L
        val b = DateTimes.normalizeDate(to) ?: return 0L
        return ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b)).coerceAtLeast(0L)
    }
}
