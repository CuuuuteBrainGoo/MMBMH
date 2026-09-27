package com.bro.lotteryledger.core

/**
 * 期号规范化 —— 把模型可能多读出来的**装饰字**剥掉，只留纯数字。
 *
 * ## 起因（少爷 2026-09-27 提的第 1 条）
 *
 * 模型把票面的「26101期」原样读了回来，而校验层要求纯数字（`^\d{5,7}$`），
 * 于是核对页顶着一条**「必须修正 · 期号格式异常：26101期」**，
 * 用户得手工把「期」字删掉才能入账。
 *
 * 这个「必须修正」是**误伤** —— 「26101期」的语义毫无歧义，
 * 它只是多带了个单位字。真正需要人看的期号问题（比如读成 `2610l`、位数不对）
 * 才该拦。
 *
 * ## 与「不做纠错」原则的关系
 *
 * [TicketJsonParser] 的既定原则是**不补零、不排序、不纠错** ——
 * 但那说的是**号码**（`3` 补成 `03` 会掩盖模型读错，号码错了对奖就错了）。
 *
 * 期号上多一个「期」字是**纯装饰**，剥掉它不改变任何语义，
 * 也不可能掩盖错误（数字本身没变）。所以这里归一化是安全的。
 *
 * ⚠️ 反过来，「数字读错」绝不在这里救 —— 归一化后仍不是 5~7 位纯数字就**原样返回**，
 * 交给 [BetRules] 报 ERROR 让人看。
 */
object IssueNormalizer {

    /** 合法的期号形态：5~7 位纯数字（双色球 7 位、大乐透 5 位）。 */
    private val PLAIN = Regex("""^\d{5,7}$""")

    /**
     * 装饰字与分隔符。
     *
     * 「期 / 第 / 开奖 / 周 / 号」是中文单位字；空格和全角空格是排版噪声；
     * `No.` `#` 是模型有时会带上的小标题。
     */
    private val NOISE = Regex("""[\s\u3000第期开奖周编号No.#号]+""", RegexOption.IGNORE_CASE)

    /** 一段连续数字。 */
    private val DIGITS = Regex("""\d+""")

    /**
     * 归一化。
     *
     * @return 纯数字期号；**认不出来时返回 null**（调用方据此报错让人看）
     *
     * 行为举例：
     * ```
     * "26101"        → "26101"      原样
     * "26101期"      → "26101"      剥单位字
     * "第26101期"    → "26101"
     * " 26101 期 "   → "26101"
     * "26101期开奖"  → "26101"
     * "2026-26101"   → "26101"      取符合位数的那一段
     * "2610l"        → null         位数不够且含字母 → 不救，交给校验报错
     * ""             → null
     * ```
     */
    fun normalize(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null

        // 最快路径：已经就是合法期号（绝大多数情况走这里，零成本）
        if (PLAIN.matches(s)) return s

        // 剥掉装饰字后看看
        val stripped = NOISE.replace(s, "").trim()
        if (PLAIN.matches(stripped)) return stripped

        // 还认不出：可能是 "2026-26101" 这种「年份 + 期号」的组合，
        // 取**位数符合**的那一段。只有一段符合才敢用 —— 多段符合就说明有歧义，
        // 宁可报错让人看（比如 "26101-26102" 到底哪个是期号？）
        val candidates = DIGITS.findAll(s).map { it.value }.filter { PLAIN.matches(it) }.toList()
        return candidates.singleOrNull()
    }

    /** 归一化后是否仍是合法期号（给调用方做快速判断用）。 */
    fun isValid(raw: String?): Boolean = normalize(raw) != null
}
