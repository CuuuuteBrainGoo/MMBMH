package com.bro.lotteryledger.repo

import android.content.Context
import androidx.room.withTransaction
import com.bro.lotteryledger.core.*
import com.bro.lotteryledger.db.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * 台账仓储。负责把 [RawTicket] 与世界（数据库）之间来回转换。
 *
 * 不在这里做业务判断 —— 规则在 [BetRules]，去重在 [Fingerprints]。
 */
class LedgerRepository(context: Context) {

    private val db = LedgerDb.get(context)
    private val ticketDao = db.ticketDao()
    private val draftDao = db.draftDao()
    private val runDao = db.recognitionRunDao()
    private val editDao = db.editLogDao()
    private val providerDao = db.aiProviderDao()
    private val settingDao = db.appSettingDao()
    private val drawDao = db.drawResultDao()

    // ---------------- 识别记录（§1.2 / §19）----------------

    suspend fun saveRun(run: RecognitionRun, ticketId: Long? = null, draftId: Long? = null) {
        runDao.insert(
            RecognitionRunEntity(
                ticketId = ticketId,
                draftId = draftId,
                provider = run.provider,
                model = run.model,
                requestType = run.requestType,
                result = run.result,
                promptTokens = run.promptTokens,
                completionTokens = run.completionTokens,
                latencyMs = run.latencyMs,
                timestamp = run.timestamp
            )
        )
    }

    suspend fun apiStats(): Pair<Long, Long> = runDao.callCount() to runDao.totalTokens()

    suspend fun recentRuns(limit: Int = 50): List<RecognitionRunEntity> = runDao.recent(limit)

    // ---------------- 草稿（§16）----------------

    /** 存草稿。草稿不进正式台账，不产生购彩支出。 */
    suspend fun saveDraft(
        raw: RawTicket,
        recognitionStatus: RecognitionStatus,
        inputMethod: InputMethod,
        imageHash: String?,
        conflicts: List<String>,
        rawJson: String? = null
    ): Long {
        val json = TicketCodec.encode(raw)
        return draftDao.insert(
            DraftEntity(
                rawJson = rawJson,
                ticketJson = json,
                recognitionStatus = recognitionStatus.name,
                inputMethod = inputMethod.code,
                imageHash = imageHash,
                conflicts = conflicts.joinToString("|")
            )
        )
    }

    suspend fun drafts(): List<DraftEntity> = draftDao.all()

    suspend fun draft(id: Long): DraftEntity? = draftDao.byId(id)

    /** 草稿队列的队首（批量导入用它取下一张要核对的票）。 */
    suspend fun oldestDraft(): DraftEntity? = draftDao.oldest()

    suspend fun deleteDraft(id: Long) = draftDao.delete(id)

    // ---------------- 去重（§6）----------------

    /** 取指纹列做去重判定。 */
    suspend fun existingIdentities(): List<Fingerprints.IdentityRow> =
        ticketDao.identityRows().map {
            Fingerprints.IdentityRow(
                id = it.id,
                identityHash = it.identityHash,
                identityLevel = it.identityLevel,
                contentHash = it.contentHash,
                purchaseTime = it.purchaseTime,
                stationNumber = it.stationNumber
            )
        }

    suspend fun judgeDuplicate(raw: RawTicket): Fingerprints.DuplicateVerdict =
        Fingerprints.judge(raw, existingIdentities())

    /**
     * 全部注（含所属票的彩种/期号/开奖日），给「号码撞上本期大奖」的彩蛋用。
     *
     * 一次查库，不在循环里逐张取注。
     */
    suspend fun allBetsForTease(): List<JackpotTease.PastBet> =
        ticketDao.allBetsWithTicket().mapNotNull { row ->
            val type = LotteryType.from(row.lotteryType) ?: return@mapNotNull null
            JackpotTease.PastBet(
                ticketId = row.ticketId,
                lotteryType = type,
                issue = row.issue,
                drawDate = row.drawDate,
                betIndex = row.betIndex,
                main = splitNumbers(row.mainNumbers),
                second = splitNumbers(row.secondNumbers)
            )
        }

    private fun splitNumbers(s: String): List<String> =
        s.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    // ---------------- 入账（§9）----------------

    /**
     * 正式入账。只有走到这一步才产生购彩支出（§16）。
     *
     * @param duplicateStatus 去重判定结果 —— 若为 EXACT_DUPLICATE 调用方必须先阻止；
     *                        若为 POSSIBLE_DUPLICATE 且用户选择「继续入账」，
     *                        传 CONFIRMED_DISTINCT，表示用户已确认是另一张实体票。
     */
    suspend fun commit(
        raw: RawTicket,
        inputMethod: InputMethod,
        duplicateStatus: DuplicateStatus,
        imageHash: String? = null
    ): Long {
        val validation = BetRules.validate(raw)
        val identity = Fingerprints.identityFingerprint(raw)
        val content = Fingerprints.contentFingerprint(raw)

        val drawDate = raw.drawDate ?: error("缺少开奖日期，不能入账")

        // 状态初判（§9.1 / §9.2 / §9.3）
        val status = initialStatus(drawDate)

        val effectiveBetType = if (validation.effectiveBetCount > 1) BetType.MULTIPLE else raw.betType

        val entity = TicketEntity(
            lotteryType = raw.lotteryType!!.code,
            issue = raw.issue!!,
            purchaseTime = raw.purchaseTime,
            drawDate = drawDate,
            betType = effectiveBetType.name,
            multiple = raw.multiple,
            additional = raw.additional,
            purchaseAmount = raw.amountYuan
                ?: BetRules.expectedAmount(
                    raw.lotteryType, validation.effectiveBetCount, raw.multiple, raw.additional
                ),
            effectiveUnits = validation.effectiveBetCount,
            officialTicketCode = raw.identifiers.verificationCode,
            identityHash = identity?.hash,
            identityLevel = identity?.level?.name,
            contentHash = content,
            imageHash = imageHash,
            verificationCode = raw.identifiers.verificationCode,
            ticketNumber = raw.identifiers.ticketNumber,
            serialNumber = raw.identifiers.serialNumber,
            terminalNumber = raw.identifiers.terminalNumber,
            stationNumber = raw.identifiers.stationNumber,
            barcodeRaw = raw.identifiers.barcodeRaw,
            duplicateStatus = duplicateStatus.name,
            inputMethod = inputMethod.code,
            ticketStatus = status.name,
            claimDeadline = DateTimes.claimDeadline(drawDate)
        )

        val bets = raw.bets.map { bet ->
            val mainG = bet.group(mainGroupOf(raw.lotteryType!!))!!
            val secondG = bet.group(secondGroupOf(raw.lotteryType))!!
            TicketBetEntity(
                ticketId = 0,
                betIndex = bet.index,
                mainGroup = mainG.name.id,
                mainNumbers = mainG.numbers.joinToString(","),
                secondGroup = secondG.name.id,
                secondNumbers = secondG.numbers.joinToString(","),
                units = (Combinatorics.c(mainG.numbers.size, specOf(raw.lotteryType).groupPickSingle) *
                    Combinatorics.c(secondG.numbers.size, specOf(raw.lotteryType).secondPickSingle)).toInt()
            )
        }

        return ticketDao.insertWithBets(entity, bets)
    }

    /**
     * 初始状态（§9）：
     *  - 票面开奖日期还没到 → 未开奖
     *  - 已到/已过  → 已过开奖时间，去官网查
     */
    private fun initialStatus(drawDate: String): TicketStatus {
        val today = java.time.LocalDate.now().toString()
        return if (drawDate > today) TicketStatus.PENDING_DRAW else TicketStatus.AWAITING_RESULT
    }

    // ---------------- 查询 ----------------

    suspend fun allTickets(): List<TicketEntity> = ticketDao.all()

    suspend fun ticket(id: Long): TicketEntity? = ticketDao.byId(id)

    suspend fun betsOf(ticketId: Long): List<TicketBetEntity> = ticketDao.betsOf(ticketId)

    /** 载入一张已入账票对应的 [RawTicket]，用于重新核对/重算。 */
    suspend fun loadRaw(ticketId: Long): RawTicket? {
        val t = ticketDao.byId(ticketId) ?: return null
        val type = LotteryType.from(t.lotteryType) ?: return null
        val spec = LotterySpecs.of(type)
        val bets = ticketDao.betsOf(ticketId).map { b ->
            Bet(
                b.betIndex,
                listOf(
                    NumberGroup(GroupName.from(b.mainGroup) ?: spec.groupName, splitNums(b.mainNumbers)),
                    NumberGroup(GroupName.from(b.secondGroup) ?: spec.secondName, splitNums(b.secondNumbers))
                )
            )
        }
        return RawTicket(
            lotteryType = type,
            issue = t.issue,
            purchaseTime = t.purchaseTime,
            drawDate = t.drawDate,
            betType = betTypeFrom(t.betType),
            multiple = t.multiple,
            additional = t.additional,
            amountYuan = t.purchaseAmount,
            identifiers = TicketIdentifiers(
                t.verificationCode, t.ticketNumber, t.serialNumber,
                t.terminalNumber, t.stationNumber, t.barcodeRaw
            ),
            bets = bets
        )
    }

    suspend fun needsCheck(today: String = java.time.LocalDate.now().toString()): List<TicketEntity> =
        ticketDao.needingCheck(today)

    /** 按状态取票（用于扫「待兑奖是否已过截止日」）。 */
    suspend fun ticketsByStatus(status: String): List<TicketEntity> = ticketDao.byStatus(status)

    // ---------------- 开奖结果（§12）----------------

    /** 查库里已有的开奖结果。 */
    suspend fun drawResult(type: LotteryType, issue: String): DrawResultEntity? =
        drawDao.find(type.code, issue)

    /**
     * 保存一期开奖结果。**已存在就跳过**，不覆盖 ——
     * 官方公告偶尔会补充修订（比如追加投注金额），但重复写库没有意义，
     * 真要更正应该走人工，而不是被自动流程悄悄改掉。
     */
    suspend fun saveDrawResult(
        numbers: com.bro.lotteryledger.core.DrawNumbers,
        poolHigh: Boolean,
        sourceRef: String? = null
    ): Boolean {
        if (drawDao.find(numbers.type.code, numbers.issue) != null) return false
        drawDao.insert(
            DrawResultEntity(
                lotteryType = numbers.type.code,
                issue = numbers.issue,
                drawDate = numbers.drawDate,
                // 主区在前、次区在后，与 §19 的注释一致
                numbers = (numbers.main + numbers.second).joinToString(","),
                source = "official",
                sourceReference = sourceRef,
                prizeMode = if (poolHigh) "enhanced" else "normal"
            )
        )
        return true
    }

    suspend fun recentDraws(limit: Int = 50): List<DrawResultEntity> = drawDao.recent(limit)

    suspend fun stats(): StatsProjection = ticketDao.stats()

    suspend fun updateTicket(t: TicketEntity) =
        ticketDao.updateTicket(t.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteTicket(id: Long) {
        ticketDao.deleteBets(id)
        ticketDao.delete(id)
    }

    suspend fun claimDeadlineOf(ticketId: Long): String? = ticketDao.byId(ticketId)?.claimDeadline

    // ---------------- 人工修改留痕（§16）----------------

    suspend fun logEdit(ticketId: Long?, draftId: Long?, field: String, oldValue: String?, newValue: String?) {
        if (oldValue == newValue) return
        editDao.insert(
            EditLogEntity(
                ticketId = ticketId, draftId = draftId, field = field,
                oldValue = oldValue, newValue = newValue, source = "manual"
            )
        )
    }

    suspend fun editsOf(ticketId: Long): List<EditLogEntity> = editDao.ofTicket(ticketId)

    // ---------------- 备份恢复专用（§17）----------------
    //
    // 这些方法都刻意和日常业务方法分开：导入要**原样**落库（保留原始时间戳、状态、金额），
    // 不能走 commit 那套「会顺带算指纹、改状态」的业务逻辑。

    /** 导出用：全部编辑日志（按票号取要查 N 次，一次全取更省）。 */
    suspend fun allEdits(): List<EditLogEntity> = editDao.all()

    /** 导出用：全部开奖结果。 */
    suspend fun allDraws(): List<DrawResultEntity> = drawDao.all()

    /** 导入查重（首选）：按票身份指纹。 */
    suspend fun findByIdentity(hash: String): TicketEntity? = ticketDao.byIdentity(hash)

    /** 导入查重（兜底）：指纹缺失时用「彩种+期号+购买时间+金额」。 */
    suspend fun findByNaturalKey(
        type: String, issue: String, purchaseTime: String, amount: Double
    ): TicketEntity? = ticketDao.byNaturalKey(type, issue, purchaseTime, amount)

    /**
     * 原样插入一张票与它的注，返回新票号。
     *
     * id 强制归零，让 Room 重新分配 —— 备份里的 id 只在**那份备份**里有意义，
     * 直接沿用会和现有数据撞主键。注的 ticketId 由 `insertWithBets` 回填。
     */
    suspend fun insertTicketRaw(t: TicketEntity, bets: List<TicketBetEntity>): Long =
        ticketDao.insertWithBets(t.copy(id = 0), bets.map { it.copy(id = 0, ticketId = 0) })

    /** 原样插入编辑日志（保留原始时间，导入后能看出"这是从备份来的历史记录"）。 */
    suspend fun insertEditLogRaw(e: EditLogEntity) = editDao.insert(e.copy(id = 0))

    /** 原样插入开奖结果；同期已有就跳过（已存的结果不该被备份覆盖）。 */
    suspend fun insertDrawResultRaw(e: DrawResultEntity): Boolean {
        if (drawDao.find(e.lotteryType, e.issue) != null) return false
        drawDao.insert(e.copy(id = 0))
        return true
    }

    // ---------------- AI 平台配置（§18）----------------

    suspend fun providers(): List<AiProviderEntity> = providerDao.all()

    suspend fun enabledProviders(): List<AiProviderEntity> = providerDao.enabled()

    suspend fun saveProvider(e: AiProviderEntity): Long =
        if (e.id == 0L) providerDao.insert(e) else { providerDao.update(e); e.id }

    suspend fun deleteProvider(id: Long) = providerDao.delete(id)

    // ---------------- 设置 ----------------

    suspend fun setSetting(key: String, value: String) =
        settingDao.put(AppSettingEntity(key, value))

    suspend fun getSetting(key: String): String? = settingDao.get(key)

    // ---------------- 「拜拜财神」：清空台账（2026-09-27 少爷要求）----------------

    /** 这次清掉了多少行，给提示和日志用。 */
    data class WipeResult(
        val tickets: Int,
        val bets: Int,
        val drafts: Int,
        val drawResults: Int,
        val editLogs: Int,
        val runs: Int
    ) {
        val isEmpty: Boolean
            get() = tickets == 0 && bets == 0 && drafts == 0 &&
                drawResults == 0 && editLogs == 0 && runs == 0

        /** 一句话描述，写进日志和提示。 */
        fun describe(): String =
            "票 $tickets / 注 $bets / 草稿 $drafts / 开奖 $drawResults / " +
                "留痕 $editLogs / 调用记录 $runs"
    }

    /**
     * 清空**台账数据**，保留全部设置。
     *
     * ## 清什么 / 不清什么（少爷明确要求「不得改动软件的任何设置项」）
     *
     * 清：票、投注号码、核对草稿、开奖结果缓存、修改留痕、AI 调用记录。
     * **不清**：`ai_providers`（平台配置）、`app_settings`（思考强度等）、
     * Keystore 里的 API Key —— 清完不用重配，直接还能拍照识别。
     *
     * ## 为什么不用 `clearAllTables()`
     *
     * 那个 API 把 8 张表全清，包括上面两张设置表 —— 直接违反需求。
     * 而且它还会重置 Room 的内部状态，行为不可控。
     * 这里逐个表删，多写几行但语义精确、可控。
     *
     * ## 为什么必须包在一个事务里
     *
     * 「票」和「投注号码」是两张表。删到一半失败会留下
     * **有注没票**（或反过来）的残局 —— 比不清还糟：
     * 首页统计会拿这些孤儿数据算出错误金额，而且用户看不出来。
     */
    suspend fun clearLedger(): WipeResult = db.withTransaction {
        // 先删子表（注）再删主表（票）：从叶子往根删，语义清楚
        val bets = ticketDao.wipeAllBets()
        val tickets = ticketDao.wipeAllTickets()
        val drafts = draftDao.wipeAll()
        val draws = drawDao.wipeAll()
        val edits = editDao.wipeAll()
        val runs = runDao.wipeAll()
        WipeResult(tickets, bets, drafts, draws, edits, runs)
    }

    /** 清空前的现状 —— 确认框里要告诉用户「这次要清掉多少」。 */
    suspend fun ledgerCounts(): WipeResult = WipeResult(
        tickets = ticketDao.stats().total,
        bets = ticketDao.countBets(),
        drafts = draftDao.all().size,
        drawResults = drawDao.all().size,
        editLogs = editDao.all().size,
        runs = runDao.callCount().toInt()
    )

    // ---------------- 辅助 ----------------

    private fun splitNums(s: String): List<String> =
        s.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun mainGroupOf(t: LotteryType) = LotterySpecs.of(t).groupName
    private fun secondGroupOf(t: LotteryType) = LotterySpecs.of(t).secondName
    private fun specOf(t: LotteryType) = LotterySpecs.of(t)
}

/** BetType 反序列化辅助（放在 core 包外，避免与 Models.kt 里的定义冲突）。 */
private fun betTypeFrom(s: String?): BetType =
    if (s?.equals("MULTIPLE", true) == true) BetType.MULTIPLE else BetType.SINGLE

/**
 * [RawTicket] ⇄ JSON。用于草稿持久化与恢复。
 * 与 [TicketJsonParser] 分开：那边解析**模型输出**（可能脏），这边读写**自己的**数据（可信）。
 */
object TicketCodec {

    fun encode(t: RawTicket): String {
        val root = JSONObject()
        root.put("schema_version", t.schemaVersion)
        root.put("lottery_type", t.lotteryType?.code ?: JSONObject.NULL)
        root.put("issue", t.issue ?: JSONObject.NULL)
        root.put("purchase_time", t.purchaseTime ?: JSONObject.NULL)
        root.put("draw_date", t.drawDate ?: JSONObject.NULL)
        root.put("bet_type", if (t.betType == BetType.MULTIPLE) "multiple" else "single")
        root.put("multiple", t.multiple)
        root.put("additional", t.additional)
        root.put("amount_yuan", t.amountYuan ?: JSONObject.NULL)
        root.put("recognition_status", t.recognitionStatus.name)

        root.put("ticket_identifiers", JSONObject().apply {
            put("verification_code", t.identifiers.verificationCode ?: JSONObject.NULL)
            put("ticket_number", t.identifiers.ticketNumber ?: JSONObject.NULL)
            put("serial_number", t.identifiers.serialNumber ?: JSONObject.NULL)
            put("terminal_number", t.identifiers.terminalNumber ?: JSONObject.NULL)
            put("station_number", t.identifiers.stationNumber ?: JSONObject.NULL)
            put("barcode_raw", t.identifiers.barcodeRaw ?: JSONObject.NULL)
        })

        val betsArr = JSONArray()
        t.bets.forEach { bet ->
            betsArr.put(JSONObject().apply {
                put("index", bet.index)
                put("groups", JSONArray().apply {
                    bet.groups.forEach { g ->
                        put(JSONObject().apply {
                            put("name", g.name.id)
                            put("numbers", JSONArray(g.numbers))
                            put("candidates", JSONArray().apply {
                                g.candidates.forEach { c -> put(JSONArray(c)) }
                            })
                        })
                    }
                })
            })
        }
        root.put("bets", betsArr)
        root.put("uncertain_fields", JSONArray(t.uncertainFields))
        return root.toString()
    }

    fun decode(json: String): RawTicket? = try {
        val root = JSONObject(json)
        val type = LotteryType.from(root.optString("lottery_type", "").ifEmpty { null })

        val idObj = root.optJSONObject("ticket_identifiers")
        val identifiers = TicketIdentifiers(
            verificationCode = idObj?.optNullable("verification_code"),
            ticketNumber = idObj?.optNullable("ticket_number"),
            serialNumber = idObj?.optNullable("serial_number"),
            terminalNumber = idObj?.optNullable("terminal_number"),
            stationNumber = idObj?.optNullable("station_number"),
            barcodeRaw = idObj?.optNullable("barcode_raw")
        )

        val bets = mutableListOf<Bet>()
        root.optJSONArray("bets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val groups = mutableListOf<NumberGroup>()
                b.optJSONArray("groups")?.let { ga ->
                    for (j in 0 until ga.length()) {
                        val g = ga.optJSONObject(j) ?: continue
                        val name = GroupName.from(g.optString("name")) ?: continue
                        val nums = mutableListOf<String>()
                        g.optJSONArray("numbers")?.let { na ->
                            for (k in 0 until na.length()) na.optString(k).takeIf { it.isNotEmpty() }?.let { nums += it }
                        }
                        val cands = mutableListOf<List<String>>()
                        g.optJSONArray("candidates")?.let { ca ->
                            for (k in 0 until ca.length()) {
                                val inner = ca.optJSONArray(k) ?: continue
                                val one = mutableListOf<String>()
                                for (m in 0 until inner.length()) {
                                    inner.optString(m).takeIf { it.isNotEmpty() }?.let { one += it }
                                }
                                if (one.isNotEmpty()) cands += one
                            }
                        }
                        groups += NumberGroup(name, nums, cands)
                    }
                }
                bets += Bet(b.optInt("index", i + 1), groups)
            }
        }

        val uncertain = mutableListOf<String>()
        root.optJSONArray("uncertain_fields")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { uncertain += it }
        }

        RawTicket(
            schemaVersion = root.optInt("schema_version", 1),
            lotteryType = type,
            issue = root.optNullable("issue"),
            purchaseTime = root.optNullable("purchase_time"),
            drawDate = root.optNullable("draw_date"),
            betType = if (root.optString("bet_type") == "multiple") BetType.MULTIPLE else BetType.SINGLE,
            multiple = root.optInt("multiple", 1),
            additional = root.optBoolean("additional", false),
            amountYuan = if (root.has("amount_yuan") && !root.isNull("amount_yuan"))
                root.optDouble("amount_yuan") else null,
            identifiers = identifiers,
            bets = bets,
            recognitionStatus = enumOrNull<RecognitionStatus>(root.optNullable("recognition_status"))
                ?: RecognitionStatus.VERIFIED,
            uncertainFields = uncertain
        )
    } catch (_: Exception) {
        null
    }

    private fun JSONObject.optNullable(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").trim().ifEmpty { null }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } }
}
