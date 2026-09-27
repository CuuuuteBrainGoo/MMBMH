package com.bro.lotteryledger.core

/**
 * 票面编号「归位」—— 把混进票号里的**验票码**挪回它自己的字段。
 *
 * ## 起因（少爷 2026-09-27 提的第 1 条）
 *
 * 视觉模型有时会把票面下方的编号区读成一整串，把验票码并到票号末尾，例如：
 * ```
 * ticket_number    = "110620-291661-111444-102984 p74ddQ"
 * verification_code = null            ← 本该是 "p74ddQ"
 * ```
 *
 * 后果是**双重**的：
 *  - 票号本身被污染（`Fingerprints` 拿它当强指纹，两张不同的票会因为多带的尾巴而"不相等"）
 *  - 验票码这个**最强唯一编号**白白丢了
 *
 * ## 判据：含字母 = 不是票号
 *
 * 两张票的票号**都是纯数字**（体彩是 4 段 × 6 位数字用 `-` 连，福彩同样是数字串），
 * 而验票码是**字母数字混合的短码**（如 `p74ddQ`）。所以：
 *
 * ```
 * 票号字段里出现「含字母的短串」 → 那一定是验票码，不是票号
 * ```
 *
 * ## 为什么只动 ticketNumber，且只在 verificationCode 为空时动
 *
 * 这条规则的价值在"能不能安全地自动修"。所以刻意收窄：
 *  - **只拆 `ticketNumber`** —— 少爷报的就是这个字段。站号（`03-003091-101`）
 *    格式固定、条码值另有含义，都不碰
 *  - **只在 `verificationCode` 为空时拆** —— 已经有值说明模型读对了，
 *    再动手就是**猜**，猜错反而把对的改坏
 *  - **候选必须唯一** —— 拆出两个含字母的串说明模型读得很乱，
 *    这时该让人看，不该挑一个
 *  - **绝不丢字符**：拆出来的东西全都有归处，没归处的原样留在票号里
 */
object IdentifierSplitter {

    /** 拆分结果。 */
    data class Result(
        val identifiers: TicketIdentifiers,
        /** 做了什么调整（给人看的说明），为空表示没动 */
        val notes: List<String> = emptyList()
    ) {
        val changed: Boolean get() = notes.isNotEmpty()
    }

    /** 含字母的短串 —— 验票码的形态。纯数字的 6 位串（安全码/流水号）不算。 */
    private val ALNUM_CODE = Regex("""^[A-Za-z0-9]{4,10}$""")

    /** 至少含一个字母。 */
    private val HAS_LETTER = Regex("""[A-Za-z]""")

    /** 切分票号用的分隔符：空白（含全角）、逗号、分号、竖线。 */
    private val SEPARATORS = Regex("""[\s\u3000,;|]+""")

    /**
     * 归位。
     *
     * 行为举例：
     * ```
     * 票号="110620-291661-111444-102984 p74ddQ", 验票码=null
     *   → 票号="110620-291661-111444-102984", 验票码="p74ddQ"
     *
     * 票号="p74ddQ", 验票码=null
     *   → 票号=null, 验票码="p74ddQ"
     *
     * 票号="110620-291661-111444-102984 054011", 验票码=null
     *   → 不动（054011 是纯数字，可能是安全码，不该当验票码）
     *
     * 票号="... p74ddQ x9k2Lm", 验票码=null
     *   → 不动（两个候选，读得太乱，交给人看）
     *
     * 票号="... p74ddQ", 验票码="p74ddQ"
     *   → 不动（已经归位）
     * ```
     */
    fun split(input: TicketIdentifiers): Result {
        // 已经有验票码 → 模型读对了，不猜
        if (!input.verificationCode.isNullOrBlank()) return Result(input)

        val ticketNo = input.ticketNumber?.trim().orEmpty()
        if (ticketNo.isEmpty()) return Result(input)

        // 整串就是验票码（模型的另一种偷懒读法）
        if (ALNUM_CODE.matches(ticketNo) && HAS_LETTER.containsMatchIn(ticketNo)) {
            return Result(
                input.copy(ticketNumber = null, verificationCode = ticketNo),
                listOf("验票码「$ticketNo」原被当作票号，已归位")
            )
        }

        val tokens = ticketNo.split(SEPARATORS).filter { it.isNotBlank() }
        if (tokens.size < 2) return Result(input)

        val codeCandidates = tokens.filter {
            ALNUM_CODE.matches(it) && HAS_LETTER.containsMatchIn(it)
        }
        // 0 个：没得拆；≥2 个：读得太乱，不猜
        if (codeCandidates.size != 1) return Result(input)

        val code = codeCandidates.first()
        val rest = tokens.filter { it != code }
        // 剩下一个都没有 → 说明整串本来就是那个验票码，票号置空
        val cleaned = rest.joinToString(" ").ifBlank { null }

        return Result(
            input.copy(ticketNumber = cleaned, verificationCode = code),
            listOf("验票码「$code」原混在票号里，已从票号中拆出")
        )
    }
}
