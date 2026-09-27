package com.bro.lotteryledger.core

/**
 * 彩票规则引擎（§7.1 / §7.3 / §7.4）。
 *
 * 纯 Kotlin 纯函数，无副作用，可完整单测。
 * 这里只做「结构合法性 + 注数 + 金额」判定，**不做中奖判断**（对奖是第二阶段的事）。
 */

/**
 * 号码书写规范（§2.3）：必须两位。
 * 注意不做「自动补零」—— 那是篡改模型输出。这里只负责判定合法/非法，
 * 补零由用户在核对界面显式确认，避免把读错的 `0` 悄悄改成别的号。
 */
object NumberFormat {
    private val TWO_DIGIT = Regex("^\\d{2}$")

    fun isTwoDigit(s: String): Boolean = TWO_DIGIT.matches(s)

    /** 数值合法性：必须两位、且落在 [min, max]。 */
    fun isValid(s: String, min: Int, max: Int): Boolean {
        if (!isTwoDigit(s)) return false
        val v = s.toIntOrNull() ?: return false
        return v in min..max
    }
}

/** 单个彩种的结构约束。 */
data class LotterySpec(
    val type: LotteryType,
    val groupName: GroupName,          // 主号码组（红球 / 前区）
    val groupMin: Int,
    val groupMax: Int,
    val groupPickSingle: Int,          // 单式选几个
    val groupPickMax: Int,             // 复式最多选几个
    val secondName: GroupName,         // 次号码组（蓝球 / 后区）
    val secondMin: Int,
    val secondMax: Int,
    val secondPickSingle: Int,
    val secondPickMax: Int,
    val unitPriceYuan: Double = 2.0,   // 单注价格
    val additionalPriceYuan: Double = 1.0 // 大乐透追加单注加价
)

object LotterySpecs {
    val SSQ = LotterySpec(
        type = LotteryType.SSQ,
        groupName = GroupName.RED, groupMin = 1, groupMax = 33,
        groupPickSingle = 6, groupPickMax = 20,
        secondName = GroupName.BLUE, secondMin = 1, secondMax = 16,
        secondPickSingle = 1, secondPickMax = 16,
        unitPriceYuan = 2.0
    )

    val DLT = LotterySpec(
        type = LotteryType.DLT,
        groupName = GroupName.FRONT, groupMin = 1, groupMax = 35,
        groupPickSingle = 5, groupPickMax = 18,
        secondName = GroupName.BACK, secondMin = 1, secondMax = 12,
        secondPickSingle = 2, secondPickMax = 12,
        unitPriceYuan = 2.0,
        additionalPriceYuan = 1.0
    )

    fun of(type: LotteryType): LotterySpec = when (type) {
        LotteryType.SSQ -> SSQ
        LotteryType.DLT -> DLT
    }
}

/** 单个字段的校验问题。 */
data class RuleIssue(
    val field: String,
    val message: String,
    val severity: Severity
) {
    enum class Severity {
        /** 结构非法，必须人工修正后才能入账 */
        ERROR,
        /** 可疑但可入账，需用户确认 */
        WARNING
    }
}

/** 校验结果。 */
data class ValidationResult(
    val issues: List<RuleIssue>,
    val effectiveBetCount: Int
) {
    val errors: List<RuleIssue> get() = issues.filter { it.severity == RuleIssue.Severity.ERROR }
    val warnings: List<RuleIssue> get() = issues.filter { it.severity == RuleIssue.Severity.WARNING }
    val hasError: Boolean get() = errors.isNotEmpty()
    val hasWarning: Boolean get() = warnings.isNotEmpty()
    val ok: Boolean get() = issues.isEmpty()
}

/**
 * 组合数 C(n, k)。n 有上限（≤20），直接算阶乘也不会溢出，
 * 但用乘除交替避免中间值过大。
 */
object Combinatorics {
    fun c(n: Int, k: Int): Long {
        if (k < 0 || n < 0 || k > n) return 0L
        val kk = minOf(k, n - k)
        var result = 1L
        for (i in 0 until kk) {
            result = result * (n - i) / (i + 1)
        }
        return result
    }
}

/**
 * 投注注的构造与维护工具。
 *
 * 存在的意义：**组名必须跟着彩种走** ——
 * 双色球用 红球/蓝球，大乐透用 前区/后区。
 * 手写的地方多了必然有人漏，所以统一从这里造。
 */
object Bets {

    /** 一注空的（号码全空），用于手工录入的起点和「添加一注」。 */
    fun blank(type: LotteryType, index: Int): Bet {
        val s = LotterySpecs.of(type)
        return Bet(
            index = index,
            groups = listOf(
                NumberGroup(s.groupName, emptyList()),
                NumberGroup(s.secondName, emptyList())
            )
        )
    }

    /**
     * 重排注号，让「第 N 注」始终连续。
     *
     * 删除中间一注后必须调它，否则界面上会出现「第 1 注、第 3 注」这种跳号。
     */
    fun reindex(list: List<Bet>): List<Bet> =
        list.mapIndexed { i, b -> b.copy(index = i + 1) }

    /**
     * 按号码数量推断单式 / 复式（§7）。
     *
     * **只做「确凿的升级」**：任何一个号码组选得比单式所需多 → 必然是复式。
     * 选了 7 个红球不可能是单式，这是硬事实，自动改正不会错。
     *
     * **反向不自动降级**：复式改成单式不做。因为那可能是模型漏读了一个号码 ——
     * 「票面真印的单式」还是「模型看漏了」，只有人能判断。
     * 这个取舍很重要：自动降级会把「模型漏读」这个真问题悄悄掩盖掉。
     */
    fun inferType(
        type: LotteryType,
        bets: List<Bet>,
        current: BetType = BetType.SINGLE
    ): BetType = if (hasMultiBet(type, bets)) BetType.MULTIPLE else current

    /**
     * 这张票里**有没有哪一注本身是复式形态**（号码个数超过单式所需）。
     *
     * ⚠️ 这是「单式多注」和「复式」的唯一判据，别再用「总注数 > 1」代替。
     * 具体判法在 [BetForm.isMultiForm]（那里也解释了为什么不能看总注数）。
     *
     * 少爷 2026-09-27 报的误报就是这么来的：
     * 一张 5 注单式的票（每注 6 红 + 1 蓝），总注数是 5，
     * 但**每一注都只选了单式要求的个数** —— 它是彻头彻尾的「单式多注」，
     * 完全正常。用总注数判会把这种票误报成复式，逼用户去点一遍没必要的确认。
     *
     * 大乐透同理：单票 10 注单式（每注 5 前 + 2 后）也是正常的单式票。
     */
    fun hasMultiBet(type: LotteryType, bets: List<Bet>): Boolean =
        firstMultiBetDetail(type, bets) != null

    /**
     * 描述**第一处**复式形态，用于提示文案。
     *
     * 例：`第 2 注 前区选了 6 个（单式只需 5 个）`
     *
     * 为什么要说这么细：用户看到「请确认投注方式」时，得知道**到底哪一注、哪个号码组**
     * 有问题，否则只能对着整张票瞎找。没有复式形态时返回 null。
     */
    fun firstMultiBetDetail(type: LotteryType, bets: List<Bet>): String? {
        val s = LotterySpecs.of(type)
        bets.forEach { b ->
            val mainN = b.group(s.groupName)?.numbers?.size ?: 0
            val secondN = b.group(s.secondName)?.numbers?.size ?: 0
            if (!BetForm.isMultiForm(s, mainN, secondN)) return@forEach
            // 两个区都超量时说主区（先看到哪个说哪个，用户照着一处改就行）
            return if (mainN > s.groupPickSingle) {
                "第 ${b.index} 注 ${s.groupName.display}选了 $mainN 个" +
                    "（单式只需 ${s.groupPickSingle} 个）"
            } else {
                "第 ${b.index} 注 ${s.secondName.display}选了 $secondN 个" +
                    "（单式只需 ${s.secondPickSingle} 个）"
            }
        }
        return null
    }
}

object BetRules {

    /**
     * 校验一张识别结果是否结构合法，并算出有效注数（§7.4）。
     *
     * 复式不展开成大量单式，只算组合数：
     *   大乐透 前区6 后区2 → C(6,5) × C(2,2) = 6 注
     *   大乐透 前区6 后区3 → C(6,5) × C(3,2) = 18 注
     */
    fun validate(raw: RawTicket): ValidationResult {
        val issues = mutableListOf<RuleIssue>()
        val spec = raw.lotteryType?.let { LotterySpecs.of(it) }

        if (raw.lotteryType == null) {
            issues += RuleIssue("lottery_type", "彩种未识别", RuleIssue.Severity.ERROR)
            return ValidationResult(issues, 0)
        }
        val s = spec!!

        if (raw.issue.isNullOrBlank()) {
            issues += RuleIssue("issue", "期号缺失", RuleIssue.Severity.ERROR)
        } else if (!Regex("^\\d{5,7}$").matches(raw.issue)) {
            issues += RuleIssue("issue", "期号格式异常：${raw.issue}", RuleIssue.Severity.ERROR)
        }

        if (raw.drawDate.isNullOrBlank()) {
            issues += RuleIssue("draw_date", "票面开奖日期缺失（对奖必须依赖它）", RuleIssue.Severity.ERROR)
        } else if (!DateTimes.isValidDate(raw.drawDate)) {
            issues += RuleIssue("draw_date", "开奖日期格式异常：${raw.drawDate}", RuleIssue.Severity.ERROR)
        }

        if (raw.bets.isEmpty()) {
            issues += RuleIssue("bets", "没有任何投注内容", RuleIssue.Severity.ERROR)
        }

        var totalUnits = 0L
        var anyAmbiguous = false

        raw.bets.forEach { bet ->
            if (bet.isAmbiguous) {
                anyAmbiguous = true
                issues += RuleIssue(
                    "bet[${bet.index}]",
                    "第 ${bet.index} 注存在多个候选号码，需人工确认",
                    RuleIssue.Severity.ERROR
                )
            }
            val unit = validateBet(bet, s, issues) ?: return@forEach
            totalUnits += unit
        }

        if (totalUnits == 0L && raw.bets.isNotEmpty() && !anyAmbiguous) {
            issues += RuleIssue("bets", "有效注数为 0", RuleIssue.Severity.ERROR)
        }

        // 投注方式与注数一致性（§7）
        //
        // ⚠️ 2026-09-27 少爷报的误报：原来这里判的是 `totalUnits > 1`，
        // 于是「5 注单式」这种完全合法的票被一律报成「标为单式但存在多注」。
        // 单式本来就可以多注 —— 每注一组号码，5 注就是 5 注。
        // 换 [Bets.hasMultiBet] 判：**只有当某一注自己的号码数超过单式时**才是复式误标。
        val declaredMultiple = raw.betType == BetType.MULTIPLE
        if (!declaredMultiple) {
            Bets.firstMultiBetDetail(raw.lotteryType, raw.bets)?.let { detail ->
                issues += RuleIssue(
                    "bet_type",
                    "标为单式，但 $detail，像是复式 —— 请确认投注方式",
                    RuleIssue.Severity.WARNING
                )
            }
        }

        if (raw.multiple < 1) {
            issues += RuleIssue("multiple", "倍数非法：${raw.multiple}", RuleIssue.Severity.ERROR)
        }

        // 大乐透之外不存在追加
        if (raw.additional && raw.lotteryType != LotteryType.DLT) {
            issues += RuleIssue(
                "additional", "双色球没有追加投注", RuleIssue.Severity.ERROR
            )
        }

        // 金额核对（§7.3）：有效注数 × 单注价格 × 倍数 (+ 追加)
        if (raw.amountYuan != null && totalUnits > 0 && raw.multiple >= 1) {
            val expected = expectedAmount(raw.lotteryType, totalUnits.toInt(), raw.multiple, raw.additional)
            if (kotlin.math.abs(expected - raw.amountYuan) > 0.005) {
                issues += RuleIssue(
                    "amount_yuan",
                    "金额与推算不符：票面 ${fmt(raw.amountYuan)} 元，按 $totalUnits 注 × ${raw.multiple} 倍" +
                        (if (raw.additional) " + 追加" else "") + " 应为 ${fmt(expected)} 元",
                    RuleIssue.Severity.WARNING
                )
            }
        } else if (raw.amountYuan == null) {
            issues += RuleIssue("amount_yuan", "金额缺失", RuleIssue.Severity.WARNING)
        }

        return ValidationResult(issues, totalUnits.toInt())
    }

    /** 校验单注，返回该注的有效注数；非法返回 null。 */
    private fun validateBet(bet: Bet, s: LotterySpec, issues: MutableList<RuleIssue>): Long? {
        val main = bet.group(s.groupName)
        val second = bet.group(s.secondName)

        if (main == null) {
            issues += RuleIssue("bet[${bet.index}].${s.groupName.id}", "缺少${s.groupName.display}", RuleIssue.Severity.ERROR)
            return null
        }
        if (second == null) {
            issues += RuleIssue("bet[${bet.index}].${s.secondName.id}", "缺少${s.secondName.display}", RuleIssue.Severity.ERROR)
            return null
        }

        val mainOk = checkGroup(main, s.groupMin, s.groupMax, s.groupPickSingle, s.groupPickMax, bet.index, issues)
        val secondOk = checkGroup(second, s.secondMin, s.secondMax, s.secondPickSingle, s.secondPickMax, bet.index, issues)
        if (!mainOk || !secondOk) return null

        return Combinatorics.c(main.numbers.size, s.groupPickSingle) *
            Combinatorics.c(second.numbers.size, s.secondPickSingle)
    }

    private fun checkGroup(
        g: NumberGroup, min: Int, max: Int, pickSingle: Int, pickMax: Int,
        betIndex: Int, issues: MutableList<RuleIssue>
    ): Boolean {
        if (g.numbers.isEmpty()) {
            issues += RuleIssue("bet[$betIndex].${g.name.id}", "${g.name.display}为空", RuleIssue.Severity.ERROR)
            return false
        }
        if (g.numbers.size > pickMax) {
            issues += RuleIssue(
                "bet[$betIndex].${g.name.id}",
                "${g.name.display}选了 ${g.numbers.size} 个，超过复式上限 $pickMax",
                RuleIssue.Severity.ERROR
            )
            return false
        }

        // 逐个校验格式与范围
        var bad = false
        g.numbers.forEach { n ->
            if (!NumberFormat.isValid(n, min, max)) {
                issues += RuleIssue(
                    "bet[$betIndex].${g.name.id}",
                    "${g.name.display}号码非法：'$n'（应形如 %02d，范围 $min-$max）".format(n.toIntOrNull() ?: 0),
                    RuleIssue.Severity.ERROR
                )
                bad = true
            }
        }
        if (bad) return false

        // 去重
        if (g.numbers.toSet().size != g.numbers.size) {
            issues += RuleIssue(
                "bet[$betIndex].${g.name.id}",
                "${g.name.display}存在重复号码",
                RuleIssue.Severity.ERROR
            )
            return false
        }

        // 复式必须凑得出至少一注单式
        if (g.numbers.size < pickSingle) {
            issues += RuleIssue(
                "bet[$betIndex].${g.name.id}",
                "${g.name.display}只有 ${g.numbers.size} 个，不足单式所需的 $pickSingle 个",
                RuleIssue.Severity.ERROR
            )
            return false
        }
        return true
    }

    /**
     * 应付金额（§7.3）：
     * 有效投注注数 × 单注价格 × 倍数 （+ 追加投注对应金额）
     */
    fun expectedAmount(
        type: LotteryType, units: Int, multiple: Int, additional: Boolean
    ): Double {
        val s = LotterySpecs.of(type)
        var per = s.unitPriceYuan
        if (additional && type == LotteryType.DLT) per += s.additionalPriceYuan
        return per * units * multiple
    }

    private fun fmt(d: Double): String =
        if (d == kotlin.math.floor(d)) d.toLong().toString() else String.format("%.2f", d)
}
