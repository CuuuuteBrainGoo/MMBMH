package com.bro.lotteryledger.core

/**
 * 批量导入的分诊（§8）。
 *
 * 交接稿的批量完成页长这样：
 * ```
 * 11 张识别正常 / 1 张疑似重复 / 1 张有字段需要确认
 * ```
 * 然后「正常票直接入账 / 重复票跳过 / 异常票单独处理」。
 *
 * 这个判定决定了**哪些票不用人看就入账** —— 判宽了会把读错的票悄悄记进账，
 * 判严了则每张都要点一遍、白做一版别人的功能。
 * 所以每一条边界都写了测试。
 */
enum class DraftTriage {
    /** 结构完整、无警告、不是重复 —— 可以直接入账 */
    CLEAN,

    /** 有错、有警告、有冲突、或疑似重复 —— 必须人看一眼 */
    NEEDS_REVIEW,

    /** 已经入账过的同一张实体票 —— 直接跳过 */
    DUPLICATE
}

object BatchTriage {

    /**
     * 分诊。
     *
     * 优先级（从上到下）：
     *  1. **完全重复** → 跳过。这一条最先判 —— 重复的票哪怕结构再完美也不该入账。
     *  2. **疑似重复** → 人工。可能重复必须人来分辨「是不是另一张实体票」。
     *  3. **校验不过或字段冲突** → 人工。
     *  4. 其余 → 可直接入账。
     *
     * 注意 `validation.ok` 的语义是「**连警告都没有**」——
     * 有 WARNING（比如票面金额与推算对不上）也算需要人看。
     * 金额对不上是个真问题，自动入账会把这个错误固化进账目。
     */
    fun classify(
        validation: ValidationResult,
        conflicts: List<String>,
        duplicate: DuplicateStatus
    ): DraftTriage = when {
        duplicate == DuplicateStatus.EXACT_DUPLICATE -> DraftTriage.DUPLICATE
        duplicate == DuplicateStatus.POSSIBLE_DUPLICATE -> DraftTriage.NEEDS_REVIEW
        !validation.ok || conflicts.isNotEmpty() -> DraftTriage.NEEDS_REVIEW
        else -> DraftTriage.CLEAN
    }

    /** 一批草稿的分诊统计，给批量完成页显示。 */
    data class Summary(
        val clean: Int,
        val review: Int,
        val duplicate: Int
    ) {
        val total: Int get() = clean + review + duplicate
        val allClean: Boolean get() = review == 0 && duplicate == 0 && clean > 0
    }

    /**
     * 从识别失败的报错里判断：**这张图是不是压根不是彩票？**
     *
     * 模型读不出彩种有两种原因：
     *  ① 票面拍糊了 / 反光 / 缺角 —— 该重拍；
     *  ② 这根本不是票（桌面、地面、人像）—— 该换一张，重拍也没用。
     *
     * 对用户来说这两种要分开说，否则他会对着桌面照片反复重拍。
     * 判据只能靠文案（解析层的 fatal issue 文本），所以用关键字匹配 ——
     * 保守优先：判不出来就当普通失败处理，绝不会误吞真票。
     */
    fun looksLikeNotTicket(error: String?): Boolean {
        val e = error?.lowercase().orEmpty()
        if (e.isEmpty()) return false
        return e.contains("彩种未识别") ||
            e.contains("不是支持的彩种") ||
            e.contains("lottery_type") ||
            // 模型没按 JSON 回答，通常是它直接回了「这不是彩票」之类的话
            e.contains("没有找到 json")
    }
}
