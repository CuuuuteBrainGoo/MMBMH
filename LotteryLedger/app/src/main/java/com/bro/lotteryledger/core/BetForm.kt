package com.bro.lotteryledger.core

/**
 * 票面的**投注构成**（比 [BetType] 细一层）。
 *
 * ## 为什么要多这一层
 *
 * [BetType] 只有「单式 / 复式」两个值，回答的是「这张票里**有没有**复式投注」——
 * 这对校验是够的（判据、注数算法都只需要它）。
 *
 * 但**不够描述真实票面**。少爷 2026-09-27 指出：
 *
 * > 大乐透存在组合型票面，既有多注单式，还有复式。
 * > 大乐透单票可打出 10 注或以上的单式票。
 *
 * 用 [BetType] 直接渲染界面的后果：一张「3 注单式 + 1 注复式」的票
 * 只显示「**复式**」两个字 —— 用户看不出它其实是混合的，
 * 也没法核对那 3 注单式到底在不在。
 *
 * [BetForm] 把票面拆开数清楚：**几注单式、几注复式、展开后共几注**。
 *
 * ## 和官方规则的关系
 *
 * 大乐透（体彩官方投注规则）：
 * ```
 * 基本投注  前区 5 个 + 后区 2 个
 * 前区复式  前区 ≥6 个 + 后区 正好 2 个
 * 后区复式  前区 正好 5 个 + 后区 ≥3 个
 * 双区复式  前区 ≥6 个 + 后区 ≥3 个
 * ```
 * 三种复式形式都满足同一个判据：**某一个区选得比基本投注多** ——
 * 所以判据只要看「哪个区超量」，不必枚举三种形式。
 *
 * 单张彩票金额上限 20000 元（基本）/ 30000 元（含追加），
 * 即最多约 10000 注 —— **注数没有比这更小的天花板**，
 * 所以任何地方都不能写「最多 N 注」的假设。
 */
data class BetForm(
    /** 单式形态的注数（两个区都正好是单式所需个数） */
    val singleBets: Int,
    /** 复式形态的注数（至少一个区超量） */
    val multiBets: Int,
    /** 号码还没填全的注（手工录入过程中的中间态） */
    val emptyBets: Int,
    /** 展开后的有效注数（各注组合数之和） */
    val totalUnits: Int
) {

    /** 有号码的注数（不含没填的）。 */
    val totalBets: Int get() = singleBets + multiBets

    /** 有没有任何注（含没填的）。 */
    val hasAny: Boolean get() = totalBets > 0 || emptyBets > 0

    /**
     * 票级形态 —— 语义与 [BetType] 保持一致。
     *
     * 只要有一注是复式，整张票就是「复式投注」。
     * 组合票因此也归 [BetType.MULTIPLE]（这个归属是对的：
     * 它确实含复式投注，注数也要按组合数算）。
     */
    val type: BetType get() = if (multiBets > 0) BetType.MULTIPLE else BetType.SINGLE

    /** 是否「既有单式注、又有复式注」—— 少爷说的组合型票面。 */
    val mixed: Boolean get() = singleBets > 0 && multiBets > 0

    /** 完整描述，给详情页那种有整行空间的场合。 */
    fun label(): String = when {
        !hasAny -> "没有投注内容"
        totalBets == 0 -> "号码还没填完"
        mixed -> "组合票 $totalBets 注（单式 $singleBets + 复式 $multiBets），展开 $totalUnits 注"
        multiBets > 0 -> "复式 $multiBets 注，展开 $totalUnits 注"
        else -> "单式 $singleBets 注"
    } + emptySuffix()

    /** 简短描述，给「要和注数/金额拼在一行」的场合。 */
    fun labelCompact(): String = when {
        !hasAny -> "没有投注内容"
        totalBets == 0 -> "号码还没填完"
        mixed -> "组合票 $totalBets 注（单式 $singleBets + 复式 $multiBets）"
        multiBets > 0 -> "复式 $multiBets 注"
        else -> "单式 $singleBets 注"
    } + emptySuffix()

    private fun emptySuffix(): String =
        if (emptyBets > 0 && totalBets > 0) "，另有 $emptyBets 注号码未填" else ""

    companion object {

        /**
         * 单注是不是**复式形态**（至少一个区的号码个数超过单式所需）。
         *
         * 这是「单式多注」和「复式」的**唯一判据**。
         *
         * 反例（会误判的旧写法）：用「总注数 > 1」判。一张 10 注单式的大乐透票
         * 总注数是 10，但每一注都只选了 5+2 —— 它是单式票，不是复式。
         */
        fun isMultiForm(spec: LotterySpec, mainCount: Int, secondCount: Int): Boolean =
            mainCount > spec.groupPickSingle || secondCount > spec.secondPickSingle

        /** 从投注列表统计。 */
        fun of(type: LotteryType, bets: List<Bet>): BetForm {
            val s = LotterySpecs.of(type)
            var single = 0
            var multi = 0
            var empty = 0
            var units = 0L

            bets.forEach { b ->
                val n1 = b.group(s.groupName)?.numbers?.size ?: 0
                val n2 = b.group(s.secondName)?.numbers?.size ?: 0

                // 号码不全的注不参与统计 —— 手工录入途中的中间态，
                // 算进「单式 N 注」会让用户以为已经录好了
                if (n1 == 0 || n2 == 0) {
                    empty++
                    return@forEach
                }

                units += Combinatorics.c(n1, s.groupPickSingle) *
                    Combinatorics.c(n2, s.secondPickSingle)

                if (isMultiForm(s, n1, n2)) multi++ else single++
            }

            return BetForm(
                singleBets = single,
                multiBets = multi,
                emptyBets = empty,
                // 组合数最大量级约 62 万（双色球 20 红 + 16 蓝），远不到 Int 上限；
                // 这里再兜一次，避免异常数据把界面搞出一串负数
                totalUnits = units.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            )
        }

        /**
         * 从「每注的组合数」统计。
         *
         * 详情页走这条 —— 库里 `ticket_bets.units` 已经存了每注组合数，
         * 不必把号码重新解析一遍（也能避免「存的号和新解析的号不一致」）。
         *
         * `units == 1` → 单式；`units > 1` → 复式；`units <= 0` → 号码没填全。
         */
        fun fromPerBetUnits(units: List<Int>): BetForm {
            var single = 0
            var multi = 0
            var empty = 0
            var total = 0L

            units.forEach { u ->
                when {
                    u > 1 -> { multi++; total += u }
                    u == 1 -> { single++; total += 1 }
                    else -> empty++
                }
            }

            return BetForm(
                singleBets = single,
                multiBets = multi,
                emptyBets = empty,
                totalUnits = total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            )
        }
    }
}
