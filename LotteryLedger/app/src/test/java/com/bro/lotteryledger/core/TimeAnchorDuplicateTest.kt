package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 判重：**购彩时间锚点**（少爷 2026-09-27 第 1 条）。
 *
 * 少爷的原话：
 * > 交叉验证需设定信息优先级：例如以购彩时间为最高优先级
 * > （不应存在两张同一时间购买的彩票；时间完全一致时优先判定为用户重复提交的失误，
 * > 此时降低其他信息的交叉验证权重）。
 *
 * 方向上照做了，但实现比「时间一致就判重复」更稳一档 ——
 * **再看一眼投注内容**：
 *
 * | 情况 | 结论 |
 * |---|---|
 * | 同秒 + 同网点 + **内容也一致** | 阻止入账（只可能是同一张票） |
 * | 同秒 + 同网点，但**内容不同** | 只提示（他可能真在同一台机器上连打了两张） |
 * | 同秒，网点不同/读不出 | 只提示 |
 *
 * 为什么不敢一步到位：**误杀的代价是「一张真票入不了账」**，
 * 比多记一笔更让人恼火。而同一秒在同一台机器上连着出两张票，现实中是可能的。
 */
class TimeAnchorDuplicateTest {

    private fun bet(index: Int, reds: List<String>, blue: String) = Bet(
        index,
        listOf(
            NumberGroup(GroupName.RED, reds),
            NumberGroup(GroupName.BLUE, listOf(blue))
        )
    )

    private fun ticket(
        time: String? = "2026-09-03 17:32:02",
        station: String? = "03-003091-101",
        serial: String? = null,
        verification: String? = null,
        ticketNo: String? = null,
        amount: Double? = 10.0,
        issue: String = "2026102",
        /** 换个 seed 就换一组号码 */
        seed: Int = 0
    ) = RawTicket(
        lotteryType = LotteryType.SSQ,
        issue = issue,
        drawDate = "2026-09-03",
        purchaseTime = time,
        amountYuan = amount,
        bets = listOf(
            bet(
                1,
                listOf(
                    "%02d".format(seed % 20 + 1),
                    "%02d".format(seed % 20 + 3),
                    "%02d".format(seed % 20 + 5),
                    "%02d".format(seed % 20 + 9),
                    "%02d".format(seed % 20 + 12),
                    "%02d".format(seed % 20 + 15)
                ),
                "%02d".format(seed % 12 + 1)
            )
        ),
        identifiers = TicketIdentifiers(
            verificationCode = verification,
            ticketNumber = ticketNo,
            serialNumber = serial,
            stationNumber = station
        )
    )

    /** 把一张票变成「库里已有的那一行」。 */
    private fun row(id: Long, from: RawTicket): Fingerprints.IdentityRow {
        val idf = Fingerprints.identityFingerprint(from)
        return Fingerprints.IdentityRow(
            id = id,
            identityHash = idf?.hash,
            identityLevel = idf?.level?.name,
            contentHash = Fingerprints.contentFingerprint(from),
            purchaseTime = from.purchaseTime,
            stationNumber = from.identifiers.stationNumber
        )
    }

    // ---------------- 第 1 层（原有行为，不能回归）----------------

    @Test
    fun `验票码一致仍然阻止入账`() {
        val a = ticket(verification = "p74ddQ", seed = 0)
        val b = ticket(verification = "p74ddQ", seed = 9)   // 内容故意不同
        val v = Fingerprints.judge(a, listOf(row(7, b)))
        assertEquals(DuplicateStatus.EXACT_DUPLICATE, v.status)
        assertEquals(7L, v.matchedTicketId)
    }

    @Test
    fun `票号一致也阻止入账`() {
        val a = ticket(ticketNo = "110620-291661-111444-102984", seed = 0)
        val b = ticket(ticketNo = "110620-291661-111444-102984", seed = 9)
        assertEquals(
            DuplicateStatus.EXACT_DUPLICATE,
            Fingerprints.judge(a, listOf(row(3, b))).status
        )
    }

    // ---------------- 第 2 层：时间锚点（本轮新增）----------------

    @Test
    fun `时间加网点加内容全一致 - 阻止入账`() {
        // 同秒、同店、同一组号码 —— 只可能是同一张票被录了两遍。
        // 注意这条在改动前是判 UNIQUE 的（因为两张票都读不出验票码），
        // 所以它是"时间提到最高优先级"带来的真实增益。
        val a = ticket(seed = 5)
        val b = ticket(seed = 5)
        val v = Fingerprints.judge(a, listOf(row(42, b)))
        assertEquals(DuplicateStatus.EXACT_DUPLICATE, v.status)
        assertEquals(42L, v.matchedTicketId)
    }

    @Test
    fun `时间加网点一致但内容不同 - 只提示 不阻止`() {
        // 同一秒在同一台机器上连着打两张不同号码的票，现实里是可能的。
        // 这种情况误杀的话，一张真票就入不了账了 —— 所以只提示。
        val a = ticket(seed = 1)
        val b = ticket(seed = 8)
        val v = Fingerprints.judge(a, listOf(row(42, b)))
        assertEquals(DuplicateStatus.POSSIBLE_DUPLICATE, v.status)
        assertNotEquals("不能阻止入账", DuplicateStatus.EXACT_DUPLICATE, v.status)
    }

    @Test
    fun `内容不同的那条提示要说清两种可能`() {
        val v = Fingerprints.judge(ticket(seed = 1), listOf(row(42, ticket(seed = 8))))
        assertEquals(DuplicateStatus.POSSIBLE_DUPLICATE, v.status)
        // 用户得知道「放弃」和「继续」各自适用于什么情况
        assertEquals(true, v.reason.contains("重复拍"))
        assertEquals(true, v.reason.contains("两张"))
    }

    @Test
    fun `只有时间一致 网点不同 - 提示但不阻止`() {
        val a = ticket(station = "03-003091-101", seed = 1)
        val b = ticket(station = "01-000123-202", seed = 8)
        val v = Fingerprints.judge(a, listOf(row(9, b)))
        assertEquals(DuplicateStatus.POSSIBLE_DUPLICATE, v.status)
        assertNotEquals(DuplicateStatus.EXACT_DUPLICATE, v.status)
    }

    @Test
    fun `时间一致但库里那张读不出网点 - 也提示`() {
        val a = ticket(station = "03-003091-101", seed = 1)
        val b = ticket(station = null, seed = 8)
        val v = Fingerprints.judge(a, listOf(row(9, b)))
        assertEquals(DuplicateStatus.POSSIBLE_DUPLICATE, v.status)
    }

    @Test
    fun `时间不同 内容也不同 - 放行`() {
        val a = ticket(time = "2026-09-03 17:32:02", seed = 1)
        val b = ticket(time = "2026-09-03 18:05:41", seed = 8)
        val v = Fingerprints.judge(a, listOf(row(9, b)))
        assertEquals(DuplicateStatus.UNIQUE, v.status)
        assertEquals(null, v.matchedTicketId)
    }

    @Test
    fun `同一期同一天但差几秒的两张票是正常的 - 不许拦`() {
        // 少爷说的「同一期多次购买」就是这种。它们时间不同、内容不同，
        // 必须干干净净地放行 —— 这是这个功能最容易误伤的场景。
        val a = ticket(time = "2026-09-03 17:32:02", seed = 1)
        val b = ticket(time = "2026-09-03 17:32:09", seed = 8)
        assertEquals(DuplicateStatus.UNIQUE, Fingerprints.judge(a, listOf(row(9, b))).status)
    }

    @Test
    fun `时间读不出来时不靠时间判重`() {
        val a = ticket(time = null, seed = 1)
        val b = ticket(time = null, seed = 8)
        val v = Fingerprints.judge(a, listOf(row(9, b)))
        assertEquals("没有时间锚点就别硬判", DuplicateStatus.UNIQUE, v.status)
    }

    @Test
    fun `时间格式不同但指向同一秒 - 也要认出来`() {
        // 一张写成 "2026-09-03 17:32:02"，另一张写成 "2026/09/03 17:32:02"
        val a = ticket(time = "2026-09-03 17:32:02", seed = 5)
        val b = ticket(time = "2026/09/03 17:32:02", seed = 5)
        assertEquals(
            DuplicateStatus.EXACT_DUPLICATE,
            Fingerprints.judge(a, listOf(row(42, b))).status
        )
    }

    @Test
    fun `空库时没有任何重复`() {
        assertEquals(
            DuplicateStatus.UNIQUE,
            Fingerprints.judge(ticket(), emptyList()).status
        )
    }

    // ---------------- 第 3 层（原有行为，不能回归）----------------

    @Test
    fun `内容相同但时间不同 - 仍然只提示`() {
        val a = ticket(time = "2026-09-03 17:32:02", seed = 5)
        val b = ticket(time = "2026-09-04 11:00:00", seed = 5)
        val v = Fingerprints.judge(a, listOf(row(9, b)))
        assertEquals(DuplicateStatus.POSSIBLE_DUPLICATE, v.status)
        assertNotEquals("内容相同绝不能单独阻止入账", DuplicateStatus.EXACT_DUPLICATE, v.status)
    }
}
