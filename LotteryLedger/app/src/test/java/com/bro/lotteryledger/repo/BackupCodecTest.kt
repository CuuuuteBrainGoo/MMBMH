package com.bro.lotteryledger.repo

import com.bro.lotteryledger.db.DrawResultEntity
import com.bro.lotteryledger.db.EditLogEntity
import com.bro.lotteryledger.db.TicketBetEntity
import com.bro.lotteryledger.db.TicketEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 备份编解码。
 *
 * ## 为什么这个测试比一般的测试重要
 *
 * 备份功能的 bug **平时完全看不出来**：导出看着成功、文件也在、大小也正常，
 * 你要等到真出事那天去恢复，才发现「哦，中奖金额没备上」——而那时候原始数据已经没了。
 *
 * 所以这里用**全字段往返**兜底：造一张每个字段都填成非默认值的票，
 * 编码再解码，用 data class 自带的逐字段 equals 比对。
 * **将来给实体加字段，只要在 `fullTicket()` 里填上，测试就自动覆盖了**，
 * 不需要手写新断言。
 */
class BackupCodecTest {

    // ---------------- 造数据：每个字段都填，且都不是默认值 ----------------

    private fun fullTicket(id: Long = 77): TicketEntity = TicketEntity(
        id = id,
        lotteryType = "DLT",
        issue = "26109",
        purchaseTime = "2026-09-25 19:31:07",
        drawDate = "2026-09-26",
        betType = "MULTIPLE",
        multiple = 3,
        additional = true,
        purchaseAmount = 18.0,
        effectiveUnits = 3,
        officialTicketCode = "OFFICIAL-CODE-1",
        identityHash = "idhash-abc123",
        identityLevel = "STRONG",
        contentHash = "content-xyz789",
        imageHash = "imghash-qwe456",
        verificationCode = "VC-0001",
        ticketNumber = "TN-8888",
        serialNumber = "SN-1234",
        terminalNumber = "TERM-01",
        stationNumber = "ST-9527",
        barcodeRaw = "0123456789012",
        duplicateStatus = "CONFIRMED_DISTINCT",
        inputMethod = "image_edited",
        ticketStatus = "TO_REDEEM",
        prizeAmount = 6666.0,
        redeemedAmount = null,
        expiredUnclaimedAmount = null,
        claimDeadline = "2026-11-25",
        redeemedAt = null,
        createdAt = 1758888888000L,
        updatedAt = 1758999999000L
    )

    private fun fullBets(ticketId: Long = 77): List<TicketBetEntity> = listOf(
        TicketBetEntity(
            id = 5, ticketId = ticketId, betIndex = 1,
            mainGroup = "前区", mainNumbers = "01,02,03,04,05,06",
            secondGroup = "后区", secondNumbers = "01,02,03", units = 3
        ),
        TicketBetEntity(
            id = 6, ticketId = ticketId, betIndex = 2,
            mainGroup = "前区", mainNumbers = "11,12,13,14,15",
            secondGroup = "后区", secondNumbers = "04,05", units = 1
        )
    )

    private fun fullEdits(ticketId: Long = 77): List<EditLogEntity> = listOf(
        EditLogEntity(
            id = 9, ticketId = ticketId, draftId = 3,
            field = "drawDate", oldValue = "2026-09-25", newValue = "2026-09-26",
            source = "manual", editedAt = 1758777777000L
        )
    )

    private fun fullDraw(): DrawResultEntity = DrawResultEntity(
        id = 12,
        lotteryType = "DLT",
        issue = "26109",
        drawDate = "2026-09-26",
        numbers = "12,14,16,27,34,04,08",
        source = "official",
        sourceReference = "sporttery-26109",
        prizeMode = "enhanced",
        firstFetchedAt = 1758900000000L,
        lastVerifiedAt = 1758900123000L
    )

    private fun fullSnapshot() = BackupCodec.Snapshot(
        schemaVersion = BackupCodec.SCHEMA_VERSION,
        appVersion = "1.1.0",
        exportedAt = 1759000000000L,
        tickets = listOf(BackupCodec.TicketBundle(fullTicket(), fullBets(), fullEdits())),
        drawResults = listOf(fullDraw()),
        settings = mapOf("thinking_level" to "FAST")
    )

    // ---------------- 核心：全字段往返 ----------------

    @Test
    fun `一张每个字段都填满的票 - 编码再解码后完全相同`() {
        val original = fullTicket()
        val decoded = BackupCodec.decode(BackupCodec.encode(fullSnapshot()))
            .tickets.single().ticket

        // data class 的 equals 逐字段比对 —— 漏掉任何一个字段这里都会红
        assertEquals("票的字段在往返后必须一模一样", original, decoded)
    }

    @Test
    fun `注的字段往返后完全相同`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(fullSnapshot()))
            .tickets.single().bets
        assertEquals(fullBets(), decoded)
    }

    @Test
    fun `人工修改记录往返后完全相同`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(fullSnapshot()))
            .tickets.single().editLogs
        assertEquals(fullEdits(), decoded)
    }

    @Test
    fun `开奖结果往返后完全相同`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(fullSnapshot())).drawResults
        assertEquals(listOf(fullDraw()), decoded)
    }

    @Test
    fun `设置往返后完全相同`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(fullSnapshot())).settings
        assertEquals(mapOf("thinking_level" to "FAST"), decoded)
    }

    @Test
    fun `元信息往返后完全相同`() {
        val d = BackupCodec.decode(BackupCodec.encode(fullSnapshot()))
        assertEquals("1.1.0", d.appVersion)
        assertEquals(1759000000000L, d.exportedAt)
        assertEquals(BackupCodec.SCHEMA_VERSION, d.schemaVersion)
    }

    // ---------------- null 必须还是 null ----------------

    @Test
    fun `可空字段为 null 时 - 往返后仍然是 null 而不是字符串 null`() {
        // org.json 的坑：JSONObject.NULL 用 optString 取出来会变成 "null" 字符串。
        // 如果踩了这个坑，"没兑奖" 会被恢复成 "兑奖金额 = 字符串null"，
        // 统计直接算错。这条专门挡它。
        val decoded = BackupCodec.decode(BackupCodec.encode(fullSnapshot()))
            .tickets.single().ticket

        assertNull("redeemedAmount 应为 null", decoded.redeemedAmount)
        assertNull("redeemedAt 应为 null", decoded.redeemedAt)
        assertNull("expiredUnclaimedAmount 应为 null", decoded.expiredUnclaimedAmount)
    }

    @Test
    fun `整张票的编号全空 - 往返后也全空`() {
        // 弱票（模型啥编号都没读到）是很常见的真实情况
        val weak = TicketEntity(
            lotteryType = "SSQ",
            issue = "2026111",
            purchaseTime = null,
            drawDate = "2026-09-24",
            betType = "SINGLE",
            multiple = 1,
            additional = false,
            purchaseAmount = 10.0,
            effectiveUnits = 5
        )
        val snap = BackupCodec.Snapshot(
            BackupCodec.SCHEMA_VERSION, "1.1.0", 1L,
            listOf(BackupCodec.TicketBundle(weak, emptyList(), emptyList())),
            emptyList(), emptyMap()
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(snap)).tickets.single().ticket
        assertEquals(weak, decoded)
        assertNull(decoded.verificationCode)
        assertNull(decoded.identityHash)
    }

    // ---------------- 空备份 ----------------

    @Test
    fun `一张票都没有的备份 - 能正常往返`() {
        val empty = BackupCodec.Snapshot(
            BackupCodec.SCHEMA_VERSION, "1.1.0", 1L, emptyList(), emptyList(), emptyMap()
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(empty))
        assertEquals(0, decoded.tickets.size)
        assertEquals(0, decoded.drawResults.size)
        assertTrue(decoded.settings.isEmpty())
    }

    // ---------------- 挡住坏文件 ----------------

    @Test
    fun `随便一个 json 文件 - 要报「不是本 App 的备份」`() {
        try {
            BackupCodec.decode("""{"hello":"world"}""")
            fail("应该抛异常")
        } catch (e: BackupCodec.BadBackupException) {
            assertTrue("提示里应说明不是备份文件", e.message!!.contains("备份文件"))
        }
    }

    @Test
    fun `根本不是 json - 要报「不是有效 JSON」`() {
        try {
            BackupCodec.decode("这是一张彩票的照片内容，不是 json")
            fail("应该抛异常")
        } catch (e: BackupCodec.BadBackupException) {
            assertTrue(e.message!!.contains("JSON"))
        }
    }

    @Test
    fun `空文件 - 要报错而不是崩溃`() {
        try {
            BackupCodec.decode("")
            fail("应该抛异常")
        } catch (_: BackupCodec.BadBackupException) {
            // 期望
        }
    }

    @Test
    fun `版本比本机新的备份 - 要明确提示先升级 App`() {
        val future = BackupCodec.encode(fullSnapshot())
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        try {
            BackupCodec.decode(future)
            fail("应该抛异常")
        } catch (e: BackupCodec.BadBackupException) {
            assertTrue("应提示升级 App，实际：" + e.message, e.message!!.contains("升级"))
        }
    }

    @Test
    fun `旧版本备份 - 缺字段也要能读出来并用默认值`() {
        // 模拟 v1.0.0 时期导出的文件：票里少了后来才加的字段
        val legacy = """
        {
          "format": "lottery-ledger-backup",
          "schemaVersion": 1,
          "appVersion": "1.0.0",
          "exportedAt": 1758000000000,
          "tickets": [
            {
              "ticket": {
                "lotteryType": "SSQ",
                "issue": "2026100",
                "drawDate": "2026-09-01",
                "purchaseAmount": 10.0
              },
              "bets": []
            }
          ],
          "drawResults": []
        }
        """.trimIndent()

        val d = BackupCodec.decode(legacy)
        val t = d.tickets.single().ticket
        assertEquals("SSQ", t.lotteryType)
        assertEquals(10.0, t.purchaseAmount, 0.001)
        // 缺失字段落到合理默认值，而不是崩溃
        assertEquals("SINGLE", t.betType)
        assertEquals(1, t.multiple)
        assertEquals(1, t.effectiveUnits)
        assertEquals("PENDING_DRAW", t.ticketStatus)
        assertEquals(0L, t.id)
        assertTrue("票没有注也要能读", d.tickets.single().bets.isEmpty())
    }

    // ---------------- 文件形态 ----------------

    @Test
    fun `导出的文件带格式标识 - 便于人肉辨认`() {
        val json = BackupCodec.encode(fullSnapshot())
        val root = JSONObject(json)
        assertEquals(BackupCodec.FORMAT, root.getString("format"))
        // 顺手记了票数，出错时一眼能看出「文件说 30 张，实际只读到 12 张」
        assertEquals(1, root.getInt("ticketCount"))
    }

    @Test
    fun `导出的 json 带缩进 - 文件坏了也能用文本编辑器抢救`() {
        val json = BackupCodec.encode(fullSnapshot())
        assertTrue("应该有多行缩进格式", json.contains("\n"))
        assertNotNull(json.lines().firstOrNull { it.contains("mainNumbers") })
    }

    // ---------------- 安全：API Key 绝不能进备份（§17 硬要求） ----------------

    @Test
    fun `导出的备份里不出现任何 API Key 相关字段`() {
        // 为什么单独立一条：备份文件很可能被少爷传到网盘上，
        // 里面出现 API Key 就是实打实的安全漏洞。§17 明确规定「API Key 不进入普通备份」。
        //
        // 这条测试是**反向断言**：不是检查某个字段存在，而是检查危险字样不存在。
        // 将来若有人把 ai_providers 表加进备份，这里会立刻变红。
        val json = BackupCodec.encode(fullSnapshot()).lowercase()

        listOf("apikey", "api_key", "api-key", "secret", "sk-", "bearer").forEach { bad ->
            assertTrue(
                "备份里出现了 \"$bad\" —— 违反 §17「API Key 不进入普通备份」",
                !json.contains(bad)
            )
        }
    }

    @Test
    fun `备份只装账目类数据 - 字段集是固定的`() {
        // 备份只该装：票、注、编辑留痕、开奖结果、少量偏好设置。
        // 用反射列出 Snapshot 的字段，将来加字段时强制走一遍这里 ——
        // 让人停下来想一下「这个新字段适合放进备份吗」。
        //
        // 两个过滤条件都必要（踩过）：
        //  - static：Compose 插件会给每个 data class 生成 `public static final int $stable`，
        //    它不是 synthetic，只靠 isSynthetic 过滤不掉；
        //  - `$` 开头：兜住其它编译器合成字段。
        // 用 Java 反射而不是 kotlin-reflect：后者要额外引依赖，为一条测试不值得。
        val props = BackupCodec.Snapshot::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filter { !it.name.startsWith('$') }
            .map { it.name }
            .sorted()
        assertEquals(
            listOf("appVersion", "drawResults", "exportedAt", "schemaVersion", "settings", "tickets"),
            props
        )
    }

    // ---------------- 多张票 ----------------

    @Test
    fun `多张票 - 顺序与归属都不乱`() {
        val snap = BackupCodec.Snapshot(
            BackupCodec.SCHEMA_VERSION, "1.1.0", 2L,
            listOf(
                BackupCodec.TicketBundle(fullTicket(1), fullBets(1), fullEdits(1)),
                BackupCodec.TicketBundle(fullTicket(2), emptyList(), emptyList()),
                BackupCodec.TicketBundle(fullTicket(3), fullBets(3), emptyList())
            ),
            listOf(fullDraw()),
            emptyMap()
        )
        val d = BackupCodec.decode(BackupCodec.encode(snap))
        assertEquals(3, d.tickets.size)
        assertEquals(listOf(1L, 2L, 3L), d.tickets.map { it.ticket.id })
        assertEquals(2, d.tickets[0].bets.size)
        assertEquals(0, d.tickets[1].bets.size)
        assertEquals(2, d.tickets[2].bets.size)
        assertEquals("第 3 张的注应属于第 3 张", 3L, d.tickets[2].bets[0].ticketId)
    }
}
