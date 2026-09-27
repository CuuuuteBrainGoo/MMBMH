package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量导入的分诊。
 *
 * ## 为什么每条边界都要测
 *
 * 这个判定决定**哪些票不用人看就直接入账**：
 *  - 判宽了 → 把读错的票悄悄记进账目，用户根本不知道
 *  - 判严了 → 每张都要点一遍，等于白做别人的功能
 *
 * 两个方向都是实质损失，所以宁可把边界写死。
 */
class BatchTriageTest {

    private fun okValidation() = ValidationResult(issues = emptyList(), effectiveBetCount = 5)

    private fun errorValidation() = ValidationResult(
        issues = listOf(RuleIssue("issue", "期号缺失", RuleIssue.Severity.ERROR)),
        effectiveBetCount = 0
    )

    private fun warningValidation() = ValidationResult(
        issues = listOf(
            RuleIssue(
                "amount_yuan",
                "金额与推算不符：票面 10 元，按 5 注应为 10 元",
                RuleIssue.Severity.WARNING
            )
        ),
        effectiveBetCount = 5
    )

    private fun classify(
        v: ValidationResult = okValidation(),
        conflicts: List<String> = emptyList(),
        dup: DuplicateStatus = DuplicateStatus.UNIQUE
    ) = BatchTriage.classify(v, conflicts, dup)

    // ---------------- 可以直接入账 ----------------

    @Test
    fun `结构完整无警告不重复-可直接入账`() {
        assertEquals(DraftTriage.CLEAN, classify())
    }

    // ---------------- 必须人看 ----------------

    @Test
    fun `有ERROR-要人看`() {
        assertEquals(DraftTriage.NEEDS_REVIEW, classify(v = errorValidation()))
    }

    @Test
    fun `有WARNING-也要人看`() {
        // 金额对不上是个真问题，自动入账等于把这个错误固化进账目
        assertEquals(DraftTriage.NEEDS_REVIEW, classify(v = warningValidation()))
    }

    @Test
    fun `有字段冲突-要人看`() {
        assertEquals(
            DraftTriage.NEEDS_REVIEW,
            classify(conflicts = listOf("drawDate: 两个模型结果不一致"))
        )
    }

    @Test
    fun `疑似重复-要人看`() {
        // 「可能重复」必须人来分辨是不是另一张实体票，不能自动入账也不能自动跳过
        assertEquals(
            DraftTriage.NEEDS_REVIEW,
            classify(dup = DuplicateStatus.POSSIBLE_DUPLICATE)
        )
    }

    // ---------------- 跳过 ----------------

    @Test
    fun `完全重复-跳过`() {
        assertEquals(DraftTriage.DUPLICATE, classify(dup = DuplicateStatus.EXACT_DUPLICATE))
    }

    @Test
    fun `完全重复的优先级最高-即使结构也完美`() {
        // 重复的票哪怕校验全过也不该入账。顺序判错会把同一张票记两遍。
        assertEquals(
            DraftTriage.DUPLICATE,
            classify(v = okValidation(), conflicts = emptyList(), dup = DuplicateStatus.EXACT_DUPLICATE)
        )
    }

    @Test
    fun `完全重复优先于疑似重复的判断之外-也优先于有错`() {
        // 又重复又读错了 —— 结果一样是跳过（这张票本就不该再进来）
        assertEquals(
            DraftTriage.DUPLICATE,
            classify(v = errorValidation(), conflicts = listOf("x"), dup = DuplicateStatus.EXACT_DUPLICATE)
        )
    }

    // ---------------- 各种「都占一点」的组合 ----------------

    @Test
    fun `多重问题叠加-仍然只是要人看`() {
        val r = classify(
            v = warningValidation(),
            conflicts = listOf("a", "b"),
            dup = DuplicateStatus.POSSIBLE_DUPLICATE
        )
        assertEquals(DraftTriage.NEEDS_REVIEW, r)
    }

    @Test
    fun `用户已确认为另一张票-不算重复`() {
        // 少爷在核对页点过「这是另一张票，继续入账」之后，不该再被当成重复
        assertEquals(
            DraftTriage.CLEAN,
            classify(dup = DuplicateStatus.CONFIRMED_DISTINCT)
        )
    }

    // ---------------- 汇总 ----------------

    @Test
    fun `汇总-全都没问题时 allClean 为真`() {
        val s = BatchTriage.Summary(clean = 11, review = 0, duplicate = 0)
        assertTrue(s.allClean)
        assertEquals(11, s.total)
    }

    @Test
    fun `汇总-有需要人看的就不是 allClean`() {
        assertFalse(BatchTriage.Summary(11, 1, 0).allClean)
        assertFalse(BatchTriage.Summary(11, 0, 1).allClean)
    }

    @Test
    fun `汇总-一张都没有时不算 allClean`() {
        // 否则界面上会显示「全部正常，可以直接入账」而实际一张都没有
        assertFalse(BatchTriage.Summary(0, 0, 0).allClean)
    }

    @Test
    fun `汇总-总数是三者之和`() {
        assertEquals(13, BatchTriage.Summary(11, 1, 1).total)
    }

    // ---------------- 「这张图压根不是彩票」的识别 ----------------
    // 少爷 2026-09-27 问：误拍桌面/地面，能不能在批量里剔除。
    // 本地能拦掉一部分（见 ImageGuardTest），剩下的靠模型报错里的关键字判断。

    @Test
    fun `解析层说彩种未识别-判定为不像彩票`() {
        assertTrue(BatchTriage.looksLikeNotTicket("彩种未识别（可能票面不清晰或不是支持的彩种）"))
    }

    @Test
    fun `模型没按 JSON 回答-判定为不像彩票`() {
        // 模型直接回「这不是一张彩票」这类自然语言时，就是这条
        assertTrue(BatchTriage.looksLikeNotTicket("模型输出中没有找到 JSON 对象"))
    }

    @Test
    fun `网络错误不能被当成不像彩票`() {
        // 判错的后果是「票被静默跳过」，绝对不能把技术故障也算进去
        assertFalse(BatchTriage.looksLikeNotTicket("HTTP 401：API Key 无效"))
        assertFalse(BatchTriage.looksLikeNotTicket("SocketTimeoutException: timeout"))
        assertFalse(BatchTriage.looksLikeNotTicket("请求超时（等了 240 秒没等到响应）"))
    }

    @Test
    fun `普通识别失败不能当成不像彩票`() {
        assertFalse(BatchTriage.looksLikeNotTicket("响应里没有可用的文本内容"))
        assertFalse(BatchTriage.looksLikeNotTicket("识别结果无法解析"))
    }

    @Test
    fun `空报错保守处理为普通失败`() {
        assertFalse(BatchTriage.looksLikeNotTicket(null))
        assertFalse(BatchTriage.looksLikeNotTicket(""))
    }
}
