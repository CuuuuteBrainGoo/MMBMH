package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别结果的「数据清洗」两条规则（少爷 2026-09-27 第 1 条）。
 *
 *  - [IssueNormalizer]：期号剥掉「期」这类**纯装饰字**
 *  - [IdentifierSplitter]：验票码从票号里**归位**
 *
 * 这两件事都发生在**解析层**，也就是模型输出刚变成 [RawTicket] 的那一刻 ——
 * 在校验层之前。位置很重要：清洗完再校验，用户就不会看到本可避免的「必须修正」。
 */
class TicketHygieneTest {

    // ==================== 期号归一化 ====================

    @Test
    fun `已经是纯数字的期号原样返回`() {
        assertEquals("26101", IssueNormalizer.normalize("26101"))
        assertEquals("2026092", IssueNormalizer.normalize("2026092"))
    }

    @Test
    fun `带期字要剥掉 - 这是少爷报的那个`() {
        // 截图里的原文：核对页顶着「必须修正 · 期号格式异常：26101期」
        assertEquals("26101", IssueNormalizer.normalize("26101期"))
    }

    @Test
    fun `各种装饰写法都要认`() {
        assertEquals("26101", IssueNormalizer.normalize("第26101期"))
        assertEquals("26101", IssueNormalizer.normalize(" 26101 期 "))
        assertEquals("26101", IssueNormalizer.normalize("26101期开奖"))
        assertEquals("26101", IssueNormalizer.normalize("No.26101"))
        assertEquals("26101", IssueNormalizer.normalize("第26101期开奖"))
        assertEquals("2026092", IssueNormalizer.normalize("2026092期"))
    }

    @Test
    fun `年份加期号取符合位数的那一段`() {
        assertEquals("26101", IssueNormalizer.normalize("2026-26101"))
        assertEquals("26101", IssueNormalizer.normalize("2026年第26101期"))
    }

    @Test
    fun `数字读错时不许救 - 要交给校验报错`() {
        // "2610l" 末位是字母 l（模型把 1 读成 l），位数也不够 ——
        // 这时**绝不能猜**：猜成 26101 就等于替模型编了个期号，
        // 对奖会查到错误的一期。必须返回 null 让校验拦下来给人看。
        assertNull(IssueNormalizer.normalize("2610l"))
        assertNull(IssueNormalizer.normalize("261"))
        assertNull(IssueNormalizer.normalize("26101111"))
    }

    @Test
    fun `有歧义时也不猜`() {
        // 两段都像期号 → 不知道哪个是，返回 null
        assertNull(IssueNormalizer.normalize("26101-26102"))
    }

    @Test
    fun `空值处理`() {
        assertNull(IssueNormalizer.normalize(null))
        assertNull(IssueNormalizer.normalize(""))
        assertNull(IssueNormalizer.normalize("   "))
        assertNull(IssueNormalizer.normalize("期"))
    }

    @Test
    fun `isValid 与 normalize 一致`() {
        assertTrue(IssueNormalizer.isValid("26101期"))
        assertFalse(IssueNormalizer.isValid("2610l"))
    }

    // ==================== 编号归位 ====================

    @Test
    fun `验票码混在票号末尾要拆出来 - 少爷报的那个`() {
        // 截图里的原文（14:12 那张）：票号 = "110620-291661-111444-102984 054011 p74ddQ"
        val input = TicketIdentifiers(ticketNumber = "110620-291661-111444-102984 054011 p74ddQ")
        val r = IdentifierSplitter.split(input)

        assertTrue("应该发生归位", r.changed)
        assertEquals("p74ddQ", r.identifiers.verificationCode)
        assertEquals(
            "票号里只该剩数字部分（054011 是纯数字，不当验票码）",
            "110620-291661-111444-102984 054011",
            r.identifiers.ticketNumber
        )
    }

    @Test
    fun `整个票号就是验票码时票号置空`() {
        val r = IdentifierSplitter.split(TicketIdentifiers(ticketNumber = "p74ddQ"))
        assertEquals("p74ddQ", r.identifiers.verificationCode)
        assertNull(r.identifiers.ticketNumber)
        assertTrue(r.changed)
    }

    @Test
    fun `纯数字票号不许动 - 中间那段可能是安全码`() {
        // "054011" 是纯数字，可能是彩票密码/安全码 ——
        // 我们不知道它的归属，**宁可留着也不乱塞**
        val input = TicketIdentifiers(ticketNumber = "110620-291661-111444-102984 054011")
        val r = IdentifierSplitter.split(input)
        assertFalse("不该动", r.changed)
        assertEquals(
            "110620-291661-111444-102984 054011",
            r.identifiers.ticketNumber
        )
        assertNull(r.identifiers.verificationCode)
    }

    @Test
    fun `两个候选时不猜`() {
        // 模型读得很乱时该让人看，不该挑一个
        val input = TicketIdentifiers(ticketNumber = "999999 p74ddQ x9k2Lm")
        val r = IdentifierSplitter.split(input)
        assertFalse("两个含字母的候选 → 不猜", r.changed)
        assertEquals("999999 p74ddQ x9k2Lm", r.identifiers.ticketNumber)
    }

    @Test
    fun `已经有验票码时什么都不动`() {
        val input = TicketIdentifiers(
            verificationCode = "p74ddQ",
            ticketNumber = "110620-291661-111444-102984 p74ddQ"
        )
        val r = IdentifierSplitter.split(input)
        assertFalse("模型已经读对了，再动手就是猜", r.changed)
        assertEquals("p74ddQ", r.identifiers.verificationCode)
        assertEquals(
            "110620-291661-111444-102984 p74ddQ",
            r.identifiers.ticketNumber
        )
    }

    @Test
    fun `票号为空或没有时不动`() {
        assertFalse(IdentifierSplitter.split(TicketIdentifiers()).changed)
        assertFalse(IdentifierSplitter.split(TicketIdentifiers(ticketNumber = "")).changed)
        assertFalse(
            IdentifierSplitter.split(TicketIdentifiers(ticketNumber = "110620-291661-111444-102984")).changed
        )
    }

    @Test
    fun `归位不影响其他编号字段`() {
        val input = TicketIdentifiers(
            ticketNumber = "110620-291661-111444-102984 p74ddQ",
            serialNumber = "00057",
            stationNumber = "03-003091-101",
            terminalNumber = "T01",
            barcodeRaw = "BC-RAW"
        )
        val r = IdentifierSplitter.split(input)
        assertEquals("00057", r.identifiers.serialNumber)
        assertEquals("03-003091-101", r.identifiers.stationNumber)
        assertEquals("T01", r.identifiers.terminalNumber)
        assertEquals("BC-RAW", r.identifiers.barcodeRaw)
    }

    @Test
    fun `逗号分隔也能拆`() {
        val r = IdentifierSplitter.split(
            TicketIdentifiers(ticketNumber = "110620-291661-111444-102984,p74ddQ")
        )
        assertEquals("p74ddQ", r.identifiers.verificationCode)
        assertEquals("110620-291661-111444-102984", r.identifiers.ticketNumber)
    }
}
