package com.bro.lotteryledger.core

/**
 * 用票面金额交叉验证注数（少爷 2026-09-27 提的核心诉求）。
 *
 * ## 要解决的痛点
 *
 * 核对页老是弹「注数 / 投注内容 需要你确认」—— 因为两个模型独立读数，
 * 对「这张票有几注」判断不一致时就报冲突。
 * 少爷的原话是：**「我觉得只有出现演算异常时才应该确认」**。
 *
 * 他是对的：票面上印着**购买金额**，而金额和注数是死关系 ——
 *
 * ```
 * 票面金额 = 有效注数 × 单注价 × 倍数
 * 单注价 = 2 元（双色球 / 大乐透基本），大乐透追加 +1 元
 * ```
 *
 * 所以金额可以**倒过来算出注数**。算出来的注数和某个模型读出的注数一致，
 * 说明那两个数（金额、注数）互相印证了 —— 没必要拿这种问题去烦用户。
 *
 * 例：票面 20 元、2 倍、无追加 → 20 ÷ (2 × 2) = **5 注**。
 *
 * ## 为什么这是真交叉验证
 *
 * 「金额」和「注数」是两个**独立**读出来的数（模型不会拿一个去凑另一个），
 * 两者恰好满足整数关系，说明两个数都读对了。不是自我印证。
 *
 * ## 单价从规则引擎读，不在这里硬编码
 *
 * [BetRules.expectedAmount] 已经是"注数 → 金额"的权威实现，
 * 这里只做反向。单价写两份迟早不一致。
 */
object TicketCrossCheck {

    /** 两个模型对注数判断的仲裁结果。 */
    enum class Verdict {
        /** 两边注数一致 —— 不需要仲裁 */
        AGREE,

        /** 只有主识别的注数能被金额验证 → 采信主识别，**不要打扰用户** */
        PRIMARY_CONSISTENT,

        /** 只有复核的注数能被金额验证 → 主识别错了，必须让用户看 */
        REVIEW_CONSISTENT,

        /** 两边都能被金额验证（金额为 0 之类的退化情况） */
        BOTH_CONSISTENT,

        /** 两边都对不上 → 金额或注数至少读错一个，必须让用户看 */
        NEITHER
    }

    /** 单注实际价格（含追加）。规则从 [BetRules.expectedAmount] 保持一致。 */
    fun unitPrice(type: LotteryType?, additional: Boolean): Double {
        val t = type ?: return 2.0
        val s = LotterySpecs.of(t)
        var per = s.unitPriceYuan
        if (additional && t == LotteryType.DLT) per += s.additionalPriceYuan
        return per
    }

    /**
     * 由票面金额反推有效注数。
     *
     * @return 注数；算不出整数（金额为空 / 非正 / 除不尽）时返回 null
     */
    fun estimateUnits(
        type: LotteryType?,
        amountYuan: Double?,
        multiple: Int,
        additional: Boolean
    ): Int? {
        val amount = amountYuan ?: return null
        if (amount <= 0.0) return null
        val mult = if (multiple <= 0) 1 else multiple
        val denom = unitPrice(type, additional) * mult
        if (denom <= 0.0) return null

        val raw = amount / denom
        val rounded = Math.round(raw)
        if (rounded <= 0L || rounded > Int.MAX_VALUE) return null

        // 容差 0.001 注：票面金额精确到分，除以单注价后正常必然整除；
        // 留这点容差是为了挡浮点误差（19.999999 这种）
        val exact = kotlin.math.abs(raw - rounded) < 0.001
        return if (exact) rounded.toInt() else null
    }

    /**
     * 仲裁两个模型给出的**有效注数**。
     *
     * @param recognizedUnits 主识别得出的有效注数
     * @param reviewUnits 复核得出的有效注数
     */
    fun arbitrate(
        type: LotteryType?,
        amountYuan: Double?,
        multiple: Int,
        additional: Boolean,
        recognizedUnits: Int,
        reviewUnits: Int
    ): Verdict {
        if (recognizedUnits == reviewUnits) return Verdict.AGREE

        // 金额本身就算不出整数注数 → 没有仲裁依据，交给用户
        val fromAmount = estimateUnits(type, amountYuan, multiple, additional)
            ?: return Verdict.NEITHER

        val primaryOk = recognizedUnits == fromAmount
        val reviewOk = reviewUnits == fromAmount

        return when {
            primaryOk && reviewOk -> Verdict.BOTH_CONSISTENT
            primaryOk -> Verdict.PRIMARY_CONSISTENT
            reviewOk -> Verdict.REVIEW_CONSISTENT
            else -> Verdict.NEITHER
        }
    }

    /**
     * 这次仲裁能不能**不打扰用户**。
     *
     * 只有「主识别与票面金额吻合」才返回 true。
     *
     * 为什么复核吻合却仍要打扰：那说明**主识别的注数是错的**，
     * 而界面上显示、即将入账的正是主识别的结果 —— 必须让人看一眼再改。
     */
    fun canSuppressConflict(verdict: Verdict): Boolean =
        verdict == Verdict.PRIMARY_CONSISTENT

    /**
     * 给冲突项补一句依据，让用户看得懂为什么被要求确认。
     *
     * 例：「5 注（与票面金额吻合）」/「4 注（票面金额对应 5 注）」
     */
    fun annotate(
        type: LotteryType?,
        units: Int,
        amountYuan: Double?,
        multiple: Int,
        additional: Boolean
    ): String {
        val fromAmount = estimateUnits(type, amountYuan, multiple, additional)
            ?: return "$units 注"
        return if (fromAmount == units) {
            "$units 注（与票面金额吻合）"
        } else {
            "$units 注（票面金额对应 $fromAmount 注）"
        }
    }
}
