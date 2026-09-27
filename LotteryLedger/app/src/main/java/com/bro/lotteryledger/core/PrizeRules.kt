package com.bro.lotteryledger.core

/**
 * 对奖引擎（§7.2 / §13）。
 *
 * 纯 Kotlin 纯函数，无副作用，可完整单测。
 *
 * ## 规则来源（2026-09-26 查官方文档确认）
 *
 * **双色球**（中国福彩网《双色球游戏规则》第十六、二十四条）：
 * ```
 * 一等 6+1 浮动 │ 二等 6+0 浮动
 * 三等 5+1 3000 │ 四等 5+0 / 4+1 200
 * 五等 4+0 / 3+1 10 │ 六等 2+1 / 1+1 / 0+1 5
 * ```
 * 另有「福运奖」（中 3+0，5 元）**仅在特别派奖期间**存在，不是常规奖级，本引擎不实现。
 *
 * **大乐透**（中国体彩网《超级大乐透游戏规则》第二十五条，2026-01-31 第 26014 期起新规）：
 * ```
 * 一等 5+2 浮动             │ 二等 5+1 浮动
 * 三等 5+0 或 4+2           │ 四等 4+1
 * 五等 4+0 或 3+2           │ 六等 3+1 或 2+2
 * 七等 3+0 / 2+1 / 1+2 / 0+2
 * ```
 * 新规把奖级从 9 个合并为 7 个（5+0 与 4+2 并为三等，4+0 与 3+2 并为五等），
 * **13 个中奖条件不变**。
 *
 * **奖池 ≥ 8 亿时三至七等奖上调**（新规，涨幅 20%~40%）：
 * ```
 * 三等 5000→6666 │ 四等 300→380 │ 五等 150→200 │ 六等 15→18 │ 七等 5→7
 * ```
 *
 * ## 一个关键性质：命中组合互不重叠
 *
 * 「每注只有一次中奖机会，不能兼中兼得」（两彩种规则都有这条）。
 * 但 (主区命中数, 次区命中数) 这个二元组**天然互斥** ——
 * (5,1) 和 (5,0) 是两个不同的命中情况，一注不可能同时满足。
 * 所以统计时**不需要去重**，每个命中组合恰好落到一个奖级。
 *
 * ## 复式票怎么算
 *
 * 不展开成几十万注单式暴力枚举（大乐透 18+12 会到 56 万注）。
 * 用组合数直接统计「命中 a 个主区、b 个次区」有多少注：
 * ```
 * C(命中的a个) × C(未命中的里补足剩余位) × 次区同理
 * ```
 * 前后区独立选号，所以两者相乘即可。
 */
enum class PrizeRules { ;

    companion object {

        /** 该票种固定奖的单注金额。level 从 1 起；浮动奖返回 null。 */
        fun fixedAmount(type: LotteryType, level: Int, poolHigh: Boolean = false): Double? =
            when (type) {
                LotteryType.SSQ -> when (level) {
                    3 -> 3000.0
                    4 -> 200.0
                    5 -> 10.0
                    6 -> 5.0
                    else -> null          // 1、2 等奖是浮动奖
                }
                LotteryType.DLT -> when (level) {
                    3 -> if (poolHigh) 6666.0 else 5000.0
                    4 -> if (poolHigh) 380.0 else 300.0
                    5 -> if (poolHigh) 200.0 else 150.0
                    6 -> if (poolHigh) 18.0 else 15.0
                    7 -> if (poolHigh) 7.0 else 5.0
                    else -> null
                }
            }

        /** 奖级名称。 */
        fun levelName(type: LotteryType, level: Int): String = when (level) {
            1 -> "一等奖"; 2 -> "二等奖"; 3 -> "三等奖"; 4 -> "四等奖"
            5 -> "五等奖"; 6 -> "六等奖"; 7 -> "七等奖"
            else -> "${level}等奖"
        }

        /** 该票种一共几个奖级。 */
        fun levelCount(type: LotteryType): Int =
            if (type == LotteryType.SSQ) 6 else 7

        /**
         * 双色球：由命中数（红球 a、蓝球 b）映射到奖级；不中返回 null。
         */
        fun ssqLevel(redHit: Int, blueHit: Int): Int? = when {
            blueHit >= 1 -> when (redHit) {
                6 -> 1
                5 -> 3
                4 -> 4
                3 -> 5
                else -> 6        // 2+1 / 1+1 / 0+1
            }
            else -> when (redHit) {   // 蓝球未中
                6 -> 2
                5 -> 4
                4 -> 5
                else -> null      // 3+0 只有福运奖期间才中
            }
        }

        /**
         * 大乐透（2026 新规 7 奖级）：由命中数（前区 a、后区 b）映射到奖级。
         */
        fun dltLevel(frontHit: Int, backHit: Int): Int? = when {
            frontHit == 5 && backHit == 2 -> 1
            frontHit == 5 && backHit == 1 -> 2
            (frontHit == 5 && backHit == 0) || (frontHit == 4 && backHit == 2) -> 3
            frontHit == 4 && backHit == 1 -> 4
            (frontHit == 4 && backHit == 0) || (frontHit == 3 && backHit == 2) -> 5
            (frontHit == 3 && backHit == 1) || (frontHit == 2 && backHit == 2) -> 6
            (frontHit == 3 && backHit == 0) || (frontHit == 2 && backHit == 1) ||
                (frontHit == 1 && backHit == 2) || (frontHit == 0 && backHit == 2) -> 7
            else -> null
        }

        /**
         * 对一张票判奖。
         *
         * @param bets      票上的所有投注（每项可能是一组复式号码）
         * @param draw      开奖号码
         * @param multiple  倍数（奖金整体相乘）
         * @param additional 是否追加（大乐透；只影响浮动奖，这里只做标注）
         * @param poolHigh  开奖当期奖池是否 ≥ 8 亿（只影响大乐透固定奖金额）
         */
        fun judge(
            type: LotteryType,
            bets: List<Bet>,
            draw: DrawNumbers,
            multiple: Int = 1,
            additional: Boolean = false,
            poolHigh: Boolean = false
        ): PrizeResult {
            val spec = LotterySpecs.of(type)
            val mainDraw = draw.main.toSet()
            val secondDraw = draw.second.toSet()

            // 奖级 -> 累计注数
            val unitsByLevel = mutableMapOf<Int, Long>()

            bets.forEach { bet ->
                val mainPicked = bet.group(spec.groupName)?.numbers.orEmpty()
                val secondPicked = bet.group(spec.secondName)?.numbers.orEmpty()
                if (mainPicked.isEmpty() || secondPicked.isEmpty()) return@forEach

                val n1 = mainPicked.size
                val n2 = secondPicked.size
                val h1 = mainPicked.count { it in mainDraw }
                val h2 = secondPicked.count { it in secondDraw }

                val k1 = spec.groupPickSingle   // 单式主区位数（6 / 5）
                val k2 = spec.secondPickSingle  // 单式次区位数（1 / 2）

                // 枚举所有可能的命中组合，用组合数算注数
                for (a in 0..minOf(h1, k1)) {
                    val miss1 = k1 - a
                    if (miss1 > n1 - h1) continue
                    val c1 = Combinatorics.c(h1, a) * Combinatorics.c(n1 - h1, miss1)

                    for (b in 0..minOf(h2, k2)) {
                        val miss2 = k2 - b
                        if (miss2 > n2 - h2) continue
                        val c2 = Combinatorics.c(h2, b) * Combinatorics.c(n2 - h2, miss2)

                        val count = c1 * c2
                        if (count <= 0L) continue

                        val level = when (type) {
                            LotteryType.SSQ -> ssqLevel(a, b)
                            LotteryType.DLT -> dltLevel(a, b)
                        } ?: continue

                        unitsByLevel[level] = (unitsByLevel[level] ?: 0L) + count
                    }
                }
            }

            val mult = multiple.coerceAtLeast(1).toLong()
            val levels = unitsByLevel.entries
                .sortedBy { it.key }
                .map { (level, rawUnits) ->
                    val units = rawUnits * mult
                    val unit = fixedAmount(type, level, poolHigh)
                    PrizeLevel(
                        level = level,
                        name = levelName(type, level),
                        units = units,
                        unitYuan = unit,
                        floating = unit == null,
                        // 追加只对浮动奖加成 80%
                        additionalBonus = additional && type == LotteryType.DLT && unit == null
                    )
                }

            return PrizeResult(
                levels = levels,
                draw = draw,
                poolHigh = poolHigh,
                multiple = multiple,
                additional = additional
            )
        }
    }
}

/** 一期开奖结果。 */
data class DrawNumbers(
    val type: LotteryType,
    val issue: String,
    /** yyyy-MM-dd */
    val drawDate: String,
    /** 双色球红球 / 大乐透前区，两位字符串 */
    val main: List<String>,
    /** 双色球蓝球 / 大乐透后区 */
    val second: List<String>
)

/** 某个奖级中了几注、单注多少。 */
data class PrizeLevel(
    val level: Int,
    val name: String,
    val units: Long,
    /** null 表示浮动奖（一二等奖），金额要人工填 */
    val unitYuan: Double?,
    val floating: Boolean,
    /** 追加投注对浮动奖额外加 80% */
    val additionalBonus: Boolean = false
) {
    /** 该奖级小计；浮动奖返回 null（未知）。 */
    val subtotal: Double? get() = unitYuan?.let { it * units }
}

/** 一张票的完整对奖结果。 */
data class PrizeResult(
    val levels: List<PrizeLevel>,
    val draw: DrawNumbers,
    val poolHigh: Boolean,
    val multiple: Int,
    val additional: Boolean
) {
    val won: Boolean get() = levels.isNotEmpty()

    /** 中了多少注（不含倍数） */
    val units: Long get() = levels.sumOf { it.units }

    /** 是否含浮动奖（一二等奖），含则需要用户手工填金额 */
    val hasFloating: Boolean get() = levels.any { it.floating }

    /** 固定奖合计金额。浮动奖不计入 —— 绝不用 0 冒充。 */
    val fixedTotalYuan: Double get() = levels.sumOf { it.subtotal ?: 0.0 }

    /** 最高奖级（1 最好）。没中返回 null。 */
    val bestLevel: Int? get() = levels.minOfOrNull { it.level }
}
