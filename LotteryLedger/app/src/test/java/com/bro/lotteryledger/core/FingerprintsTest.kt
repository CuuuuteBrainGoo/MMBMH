package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 去重逻辑测试 —— 交接稿里最重要的不可违背行为（§6）。
 *
 * 核心断言：**号码全同的两张实体票必须分别入账**（§6.1 / §23.5）。
 */
class FingerprintsTest {

    private fun ssq(
        verificationCode: String? = null,
        serial: String? = null,
        station: String? = null,
        time: String? = "2026-09-24 13:54:02",
        numbers: List<String> = listOf("02", "07", "17", "18", "22", "26"),
        amount: Double = 2.0
    ) = RawTicket(
        lotteryType = LotteryType.SSQ,
        issue = "2026111",
        drawDate = "2026-09-24",
        purchaseTime = time,
        amountYuan = amount,
        identifiers = TicketIdentifiers(
            verificationCode = verificationCode,
            serialNumber = serial,
            stationNumber = station
        ),
        bets = listOf(
            Bet(1, listOf(
                NumberGroup(GroupName.RED, numbers),
                NumberGroup(GroupName.BLUE, listOf("13"))
            ))
        )
    )

    // ---------- §6.1 最核心的一条 ----------

    @Test
    fun `号码完全相同的两张实体票必须分别入账`() {
        // 两张票：号码/期号/倍数/金额全同，但流水号不同 → 是两张不同的实体票
        val a = ssq(serial = "0001", station = "11001")
        val b = ssq(serial = "0002", station = "11001")

        val idA = Fingerprints.identityFingerprint(a)
        val idB = Fingerprints.identityFingerprint(b)
        assertNotNull(idA)
        assertNotNull(idB)
        assertTrue("流水号不同 → 身份指纹必须不同", idA!!.hash != idB!!.hash)

        // 内容指纹相同（这正是「仅提示」的场景）
        assertEquals(
            "号码相同 → 内容指纹相同",
            Fingerprints.contentFingerprint(a),
            Fingerprints.contentFingerprint(b)
        )
    }

    @Test
    fun `仅内容相同只能判为待确认而不能判为重复`() {
        val existing = ssq(serial = "0001", station = "11001")
        val incoming = ssq(serial = "0002", station = "11001")

        val rows = listOf(
            Fingerprints.IdentityRow(
                id = 1,
                identityHash = Fingerprints.identityFingerprint(existing)!!.hash,
                identityLevel = Fingerprints.identityFingerprint(existing)!!.level.name,
                contentHash = Fingerprints.contentFingerprint(existing)
            )
        )

        val verdict = Fingerprints.judge(incoming, rows)
        assertEquals(
            "号码相同但流水号不同 → 只能是 POSSIBLE_DUPLICATE，绝不能是 EXACT",
            DuplicateStatus.POSSIBLE_DUPLICATE,
            verdict.status
        )
    }

    // ---------- 强唯一编号 ----------

    @Test
    fun `验票码一致应判为同一张实体票`() {
        val existing = ssq(verificationCode = "ABC123456")
        val incoming = ssq(verificationCode = "ABC123456")

        val id = Fingerprints.identityFingerprint(existing)!!
        assertEquals(Fingerprints.Level.STRONG, id.level)

        val verdict = Fingerprints.judge(
            incoming,
            listOf(Fingerprints.IdentityRow(1, id.hash, id.level.name, Fingerprints.contentFingerprint(existing)))
        )
        assertEquals(DuplicateStatus.EXACT_DUPLICATE, verdict.status)
        assertEquals(1L, verdict.matchedTicketId)
    }

    @Test
    fun `验票码大小写与空格差异不应影响判定`() {
        val a = ssq(verificationCode = "abc-123")
        val b = ssq(verificationCode = "  ABC-123 ")
        assertEquals(
            Fingerprints.identityFingerprint(a)!!.hash,
            Fingerprints.identityFingerprint(b)!!.hash
        )
    }

    @Test
    fun `站号加终端加流水加秒级时间一致应判为同一张`() {
        val existing = ssq(serial = "0001", station = "11001")
        val incoming = ssq(serial = "0001", station = "11001")

        val id = Fingerprints.identityFingerprint(existing)!!
        assertEquals(Fingerprints.Level.COMPOSITE, id.level)

        val verdict = Fingerprints.judge(
            incoming,
            listOf(Fingerprints.IdentityRow(7, id.hash, id.level.name, Fingerprints.contentFingerprint(existing)))
        )
        assertEquals(DuplicateStatus.EXACT_DUPLICATE, verdict.status)
    }

    @Test
    fun `出票时间差一秒即视为不同票`() {
        val a = ssq(serial = "0001", station = "11001", time = "2026-09-24 13:54:02")
        val b = ssq(serial = "0001", station = "11001", time = "2026-09-24 13:54:03")
        assertTrue(
            "秒级时间是指纹的一部分，差一秒就是两张票",
            Fingerprints.identityFingerprint(a)!!.hash != Fingerprints.identityFingerprint(b)!!.hash
        )
    }

    // ---------- 弱指纹不得阻止入账 ----------

    @Test
    fun `弱组合指纹即使命中也不能判为重复`() {
        // 无验票码、无流水号、无站号 → 只能靠弱组合
        val a = ssq(time = "2026-09-24 13:54:02", amount = 2.0).copy(
            identifiers = TicketIdentifiers(stationNumber = "11001")
        )
        val id = Fingerprints.identityFingerprint(a)
        assertNotNull(id)
        assertEquals("信息不足时只能给弱指纹", Fingerprints.Level.WEAK, id!!.level)

        val verdict = Fingerprints.judge(
            a,
            listOf(Fingerprints.IdentityRow(9, id.hash, id.level.name, Fingerprints.contentFingerprint(a)))
        )
        assertEquals(
            "弱指纹绝不能阻止入账（§6.4）",
            DuplicateStatus.POSSIBLE_DUPLICATE,
            verdict.status
        )
    }

    @Test
    fun `信息完全不足时不生成身份指纹`() {
        val bare = RawTicket(
            lotteryType = LotteryType.SSQ,
            issue = "2026111",
            drawDate = "2026-09-24",
            purchaseTime = null,
            amountYuan = null,
            bets = listOf(
                Bet(1, listOf(
                    NumberGroup(GroupName.RED, listOf("02", "07", "17", "18", "22", "26")),
                    NumberGroup(GroupName.BLUE, listOf("13"))
                ))
            )
        )
        assertNull("没有可用编号时不能凭号码造指纹", Fingerprints.identityFingerprint(bare))
    }

    @Test
    fun `无任何历史记录时应判为唯一`() {
        assertEquals(DuplicateStatus.UNIQUE, Fingerprints.judge(ssq(), emptyList()).status)
    }

    // ---------- 图片指纹（仅辅助，§6.2A）----------

    @Test
    fun `图片指纹可区分不同图片且对同字节稳定`() {
        val a = byteArrayOf(1, 2, 3, 4, 5)
        val b = byteArrayOf(1, 2, 3, 4, 6)
        assertEquals(Fingerprints.imageFingerprint(a), Fingerprints.imageFingerprint(a.copyOf()))
        assertTrue(Fingerprints.imageFingerprint(a) != Fingerprints.imageFingerprint(b))
    }

    // ---------- 内容指纹与顺序无关 ----------

    @Test
    fun `内容指纹不受号码书写顺序影响`() {
        val a = ssq(numbers = listOf("02", "07", "17", "18", "22", "26"))
        val b = ssq(numbers = listOf("26", "22", "18", "17", "07", "02"))
        assertEquals(
            "票面顺序不同但集合相同，内容指纹应一致（提示用途）",
            Fingerprints.contentFingerprint(a),
            Fingerprints.contentFingerprint(b)
        )
    }

    @Test
    fun `倍数不同则内容指纹不同`() {
        val a = ssq()
        val b = ssq().copy(multiple = 2)
        assertTrue(Fingerprints.contentFingerprint(a) != Fingerprints.contentFingerprint(b))
    }
}
