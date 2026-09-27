package com.bro.lotteryledger.ai

import com.bro.lotteryledger.core.*

/**
 * 识别流程编排（§2.2）。
 *
 * 严格按交接稿的 9 步走：
 *  1-2. 拍照/选图 + 本地处理（由 ImagePipeline 负责，不在这里）
 *  3.   发送给主视觉模型
 *  4.   模型输出结构化数据
 *  5.   App 本地做彩票规则校验
 *  6.   异常/不确定/冲突 → 调第二视觉模型独立复核
 *  7.   第二模型不得看到第一模型的答案
 *  8.   仍冲突 → 只让用户确认冲突字段
 *  9.   用户确认后入账（由 UI 层负责）
 */
class RecognitionOrchestrator(
    private val client: OpenAiCompatibleClient
) {

    /** 一次识别的完整结果。 */
    data class Outcome(
        val ticket: RawTicket?,
        val validation: ValidationResult?,
        val runs: List<RecognitionRun>,
        val fatalError: String? = null,
        /** 需要用户确认的字段路径 */
        val conflicts: List<String> = emptyList(),
        /**
         * 冲突字段的实际值：路径 → (主模型的值, 复核模型的值)。
         *
         * 只有「两个模型结果不一致」时才有内容。有了它，
         * 核对页就能显示「注数：主识别 5 注 / 复核 3 注」，
         * 而不是干巴巴一个 `bets` —— 少爷 2026-09-27 反馈看不懂那个。
         */
        val conflictValues: Map<String, Pair<String?, String?>> = emptyMap(),
        /** 是否触发了第二模型复核 */
        val reviewed: Boolean = false
    ) {
        /** 可以直接入账（无需人工干涉）—— 但实际上仍要走核对界面 */
        val autoOk: Boolean get() = fatalError == null && ticket != null &&
            validation != null && !validation.hasError && conflicts.isEmpty()
    }

    /**
     * 执行识别。
     *
     * @param primary 主识别平台
     * @param reviewer 复核平台；null 表示未配置，则不做复核
     * @param primaryKey 主平台 API Key（明文，仅在内存中传递）
     * @param reviewerKey 复核平台 API Key
     * @param imageBase64 本地处理后的图片裸 base64
     */
    suspend fun run(
        primary: AiProviderConfig,
        reviewer: AiProviderConfig?,
        primaryKey: String,
        reviewerKey: String,
        imageBase64: String
    ): Outcome {
        val runs = mutableListOf<RecognitionRun>()

        // --- 步骤 3：主模型 ---
        LedgerLog.i("AI", "主识别开始：${primary.provider.code} / ${primary.model} @ ${EndpointNormalizer.normalize(primary.baseUrl)}")
        val primaryCall = client.recognize(primary, primaryKey, imageBase64, VisionPrompt.build())
        runs += toRun(primary, "primary", primaryCall)

        if (!primaryCall.ok) {
            LedgerLog.e("AI", "主识别失败（${primaryCall.latencyMs}ms, HTTP ${primaryCall.httpStatus}）：${primaryCall.error}")
            return Outcome(
                ticket = null, validation = null, runs = runs,
                fatalError = "主识别失败：${primaryCall.error}"
            )
        }
        LedgerLog.i("AI", "主识别成功（${primaryCall.latencyMs}ms, token ${primaryCall.totalTokens ?: "?"}），正文 ${primaryCall.rawText?.length ?: 0} 字")

        // --- 步骤 4：解析 ---
        val primaryParsed = TicketJsonParser.parse(primaryCall.rawText!!)
        val primaryTicket = primaryParsed.ticket
        primaryParsed.issues.forEach {
            LedgerLog.w("Parse", "${if (it.fatal) "致命" else "提示"} ${it.path}：${it.message}")
        }

        // --- 步骤 5：本地规则校验 ---
        val primaryValidation = primaryTicket?.let { BetRules.validate(it) }

        // 判断是否够格走 complete 状态
        val primaryClean = primaryTicket != null &&
            primaryParsed.issues.none { it.fatal } &&
            primaryValidation != null &&
            !primaryValidation.hasError &&
            !primaryValidation.hasWarning &&
            primaryTicket.uncertainFields.isEmpty() &&
            !primaryTicket.bets.any { it.isAmbiguous }

        // --- 步骤 6：需要复核吗 ---
        val needReview = !primaryClean && reviewer != null &&
            ProviderDefaults.applies(reviewer, primaryTicket?.lotteryType ?: LotteryType.SSQ)

        if (!needReview) {
            LedgerLog.i("AI", "无需复核：clean=$primaryClean，冲突=${collectConflicts(primaryParsed, primaryValidation, primaryTicket).size} 项")
            val finalTicket = primaryTicket?.let {
                if (primaryClean) it.copy(recognitionStatus = RecognitionStatus.VERIFIED) else it
            }
            return Outcome(
                ticket = finalTicket,
                validation = primaryValidation,
                runs = runs,
                fatalError = if (primaryTicket == null) fatalReason(primaryParsed) else null,
                conflicts = collectConflicts(primaryParsed, primaryValidation, primaryTicket)
            )
        }

        // --- 步骤 7：第二模型独立复核（不给它看第一模型答案）---
        // 注意：这里传的 prompt 由 buildReviewer() 生成，且不携带任何 primary 结果。
        LedgerLog.i("AI", "触发复核：${reviewer!!.provider.code} / ${reviewer.model}")
        val reviewCall = client.recognize(reviewer, reviewerKey, imageBase64, VisionPrompt.buildReviewer())
        runs += toRun(reviewer, "reviewer", reviewCall)

        if (!reviewCall.ok) {
            LedgerLog.w("AI", "复核失败，回退主模型结果并标记需人工确认：${reviewCall.error}")
            // 复核失败不阻塞主流程 —— 回退到主模型结果，标记需人工确认
            return Outcome(
                ticket = primaryTicket,
                validation = primaryValidation,
                runs = runs,
                fatalError = if (primaryTicket == null) fatalReason(primaryParsed) else null,
                conflicts = collectConflicts(primaryParsed, primaryValidation, primaryTicket),
                reviewed = true
            )
        }

        val reviewParsed = TicketJsonParser.parse(reviewCall.rawText!!)
        val reviewTicket = reviewParsed.ticket

        // --- 步骤 8：比对，只让用户确认冲突字段 ---
        val diff = TicketDiffer.diff(primaryTicket, reviewTicket)
        val finalStatus = if (diff.isEmpty() && primaryTicket != null &&
            primaryValidation?.hasError == false
        ) {
            RecognitionStatus.DOUBLE_VERIFIED
        } else {
            RecognitionStatus.NEEDS_REVIEW
        }

        val mergedTicket = primaryTicket?.copy(
            recognitionStatus = finalStatus,
            uncertainFields = (primaryTicket.uncertainFields + diff.keys).distinct()
        )
        LedgerLog.i("AI", "复核比对：状态=$finalStatus，差异 ${diff.size} 个字段 ${if (diff.isNotEmpty()) "→ ${diff.keys}" else ""}")

        return Outcome(
            ticket = mergedTicket,
            validation = primaryValidation,
            runs = runs,
            fatalError = if (primaryTicket == null) "识别结果无法解析" else null,
            conflicts = (diff.keys + collectConflicts(primaryParsed, primaryValidation, primaryTicket)).distinct(),
            // 只有真跑过复核、且两份结果有差异时才有值可展示
            conflictValues = diff,
            reviewed = true
        )
    }

    /**
     * 解析失败时，把**最具体的那条致命原因**带出去。
     *
     * 以前这里写死「识别结果无法解析」，把真正的原因（比如「彩种未识别」）吞掉了。
     * 后果有两个：
     *  ① 用户看不懂到底哪里出了问题；
     *  ② [BatchTriage.looksLikeNotTicket] 认不出「这不是彩票」，
     *     误拍的照片会被算成「识别失败」而不是「跳过」。
     */
    private fun fatalReason(parsed: TicketJsonParser.ParseResult): String =
        parsed.issues.firstOrNull { it.fatal }?.message ?: "识别结果无法解析"

    private fun toRun(cfg: AiProviderConfig, type: String, r: AiCallResult) = RecognitionRun(
        provider = cfg.provider.code,
        model = cfg.model,
        requestType = type,
        result = if (r.ok) "ok" else (r.error ?: "unknown"),
        promptTokens = r.promptTokens,
        completionTokens = r.completionTokens,
        latencyMs = r.latencyMs
    )

    /** 汇总本地校验发现的需要人工确认的字段。 */
    private fun collectConflicts(
        parsed: TicketJsonParser.ParseResult,
        validation: ValidationResult?,
        ticket: RawTicket?
    ): List<String> {
        val out = mutableListOf<String>()
        parsed.issues.forEach { out += it.path }
        validation?.issues?.forEach { out += it.field }
        ticket?.uncertainFields?.let { out += it }
        return out.distinct()
    }
}

/**
 * 两份识别结果的字段级差异（§2.2 步骤 8：只让用户确认冲突字段）。
 */
object TicketDiffer {

    /** 返回 冲突字段路径 → (主模型值, 复核模型值)。空 map 表示两份结果一致。 */
    fun diff(a: RawTicket?, b: RawTicket?): Map<String, Pair<String?, String?>> {
        if (a == null || b == null) {
            return if (a == null && b == null) emptyMap()
            else mapOf("ticket" to (describe(a) to describe(b)))
        }

        val out = mutableMapOf<String, Pair<String?, String?>>()

        fun cmp(path: String, va: String?, vb: String?) {
            if (va != vb) out[path] = va to vb
        }

        cmp("lottery_type", a.lotteryType?.code, b.lotteryType?.code)
        cmp("issue", a.issue, b.issue)
        cmp("purchase_time", a.purchaseTime, b.purchaseTime)
        cmp("draw_date", a.drawDate, b.drawDate)
        cmp("bet_type", a.betType.name, b.betType.name)
        cmp("multiple", a.multiple.toString(), b.multiple.toString())
        cmp("additional", a.additional.toString(), b.additional.toString())
        cmp("amount_yuan", fmtAmount(a.amountYuan), fmtAmount(b.amountYuan))

        cmp("ticket_identifiers.verification_code", a.identifiers.verificationCode, b.identifiers.verificationCode)
        cmp("ticket_identifiers.ticket_number", a.identifiers.ticketNumber, b.identifiers.ticketNumber)
        cmp("ticket_identifiers.serial_number", a.identifiers.serialNumber, b.identifiers.serialNumber)
        cmp("ticket_identifiers.terminal_number", a.identifiers.terminalNumber, b.identifiers.terminalNumber)
        cmp("ticket_identifiers.station_number", a.identifiers.stationNumber, b.identifiers.stationNumber)
        cmp("ticket_identifiers.barcode_raw", a.identifiers.barcodeRaw, b.identifiers.barcodeRaw)

        // 投注内容：注数不同直接整体冲突；否则逐注逐组比
        //
        // ⚠️ 关键改动（少爷 2026-09-27）：注数不一致时**先用票面金额交叉验证**。
        // 他的原话是「只有出现演算异常时才应该确认」——
        // 票面金额 ÷ (单注价 × 倍数) 必须等于注数，这是死关系。
        // 主识别的注数满足这个关系，就说明金额和注数两个独立读数互相印证了，
        // 不该拿这种问题去烦用户（那类冲突占了绝大多数）。
        val aUnits = BetRules.validate(a).effectiveBetCount
        val bUnits = BetRules.validate(b).effectiveBetCount

        if (aUnits != bUnits) {
            val type = a.lotteryType ?: b.lotteryType
            val amount = a.amountYuan ?: b.amountYuan
            val verdict = TicketCrossCheck.arbitrate(
                type = type,
                amountYuan = amount,
                multiple = a.multiple,
                additional = a.additional,
                recognizedUnits = aUnits,
                reviewUnits = bUnits
            )
            if (TicketCrossCheck.canSuppressConflict(verdict)) {
                // 两个独立读数互相印证 → 抑制这条冲突，只留日志便于事后追溯
                LedgerLog.i(
                    "Diff",
                    "注数不一致（主 $aUnits / 复核 $bUnits），但主识别与票面金额" +
                        "${amount ?: "?"} 元吻合 → 采信主识别，不打扰用户"
                )
            } else {
                // 抑制不了：把「票面金额对应几注」写进提示，
                // 用户一眼就知道该以哪个为准
                out["bets"] = TicketCrossCheck.annotate(
                    type, aUnits, amount, a.multiple, a.additional
                ) to TicketCrossCheck.annotate(
                    type, bUnits, amount, b.multiple, b.additional
                )
            }
        } else if (a.bets.size != b.bets.size) {
            // 有效注数相同、但投注项个数不同（比如 1 个复式项 vs 2 个单式项）
            // —— 号码分布不一样，这个必须人看
            out["bets"] = ("${a.bets.size} 个投注项") to ("${b.bets.size} 个投注项")
        } else {
            a.bets.forEachIndexed { i, betA ->
                val betB = b.bets[i]
                GroupName.entries.forEach { g ->
                    val na = betA.group(g)?.numbers
                    val nb = betB.group(g)?.numbers
                    if (na != null || nb != null) {
                        cmp(
                            "bets[$i].${g.id}",
                            na?.joinToString(","),
                            nb?.joinToString(",")
                        )
                    }
                }
            }
        }

        return out
    }

    private fun fmtAmount(d: Double?): String? = d?.let { String.format("%.2f", it) }

    /** 两个模型互相看不到对方，只用来做异常兜底描述。 */
    private fun describe(t: RawTicket?): String =
        t?.let { "${it.lotteryType?.display ?: "?"} 期号 ${it.issue ?: "?"} ${it.bets.size} 注" } ?: "无法解析"
}
