package com.bro.lotteryledger.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把视觉模型返回的 JSON 解析成 [RawTicket]（§4）。
 *
 * 设计要点：
 *  - 所有解析失败都被记录成 [ParseIssue]，绝不静默吞掉。
 *  - **不补零、不排序、不纠错** —— 模型输出什么就记什么，
 *    号码格式不合规交给 [BetRules] 报 ERROR，让用户改（§2.3）。
 *  - 用 org.json（Android 自带，单测里用 org.json:json 提供实现）。
 */
object TicketJsonParser {

    data class ParseResult(
        val ticket: RawTicket?,
        val issues: List<ParseIssue>
    ) {
        val ok: Boolean get() = ticket != null && issues.none { it.fatal }
    }

    data class ParseIssue(val path: String, val message: String, val fatal: Boolean = false)

    /**
     * 从模型原始输出里提取 JSON。
     * 模型常会把 JSON 包在 ```json 里或加一句废话，这里做最小限度的剥离。
     */
    fun extractJson(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null

        // 去掉 markdown 代码围栏
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fence.find(t)?.let { return it.groupValues[1].trim() }

        // 退而求其次：取第一个 { 到最后一个 }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start >= 0 && end > start) return t.substring(start, end + 1)

        return null
    }

    fun parse(modelOutput: String): ParseResult {
        val issues = mutableListOf<ParseIssue>()

        val jsonText = extractJson(modelOutput)
        if (jsonText == null) {
            return ParseResult(null, listOf(ParseIssue("\$", "模型输出中没有找到 JSON 对象", fatal = true)))
        }

        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            return ParseResult(
                null,
                listOf(ParseIssue("\$", "JSON 解析失败：${e.message}", fatal = true))
            )
        }

        // --- 彩种 ---
        val typeCode = root.optNullableString("lottery_type")
        val lotteryType = LotteryType.from(typeCode)
        if (typeCode != null && lotteryType == null) {
            issues += ParseIssue("lottery_type", "未知彩种：$typeCode", fatal = true)
        }
        if (lotteryType == null) {
            issues += ParseIssue("lottery_type", "彩种未识别（可能票面不清晰或不是支持的彩种）", fatal = true)
        }

        // --- 期号 ---
        // 期号：先剥掉「期」「第」这类装饰字（少爷 2026-09-27 第 1 条）。
        //
        // 「26101期」的语义毫无歧义，不该顶着一条「必须修正」逼用户手工删字。
        // 但**归一化不了就保留原值** —— 让 [BetRules] 报「期号格式异常：2610l」，
        // 用户看到原文才知道要改什么。
        val issueRaw = root.optNullableString("issue")
        val issue = IssueNormalizer.normalize(issueRaw) ?: issueRaw
        if (issueRaw != null && issue != issueRaw) {
            LedgerLog.i("Parse", "期号已归一化：「$issueRaw」→「$issue」")
        }

        // --- 时间 ---
        val purchaseRaw = root.optNullableString("purchase_time")
        val purchaseTime = DateTimes.normalize(purchaseRaw)
        if (purchaseRaw != null && purchaseTime == null) {
            issues += ParseIssue("purchase_time", "购彩时间格式无法识别：$purchaseRaw")
        }

        val drawRaw = root.optNullableString("draw_date")
        val drawDate = DateTimes.normalizeDate(drawRaw)
        if (drawRaw != null && drawDate == null) {
            issues += ParseIssue("draw_date", "开奖日期格式无法识别：$drawRaw")
        }

        // --- 投注方式 / 倍数 / 追加 ---
        val betType = when (root.optNullableString("bet_type")?.lowercase()) {
            "multiple" -> BetType.MULTIPLE
            "single" -> BetType.SINGLE
            else -> BetType.SINGLE
        }
        val multiple = root.optInt("multiple", 1).coerceAtLeast(1)
        val additional = root.optBoolean("additional", false)

        // --- 金额 ---
        val amount = if (root.has("amount_yuan") && !root.isNull("amount_yuan")) {
            root.optDouble("amount_yuan", Double.NaN).takeIf { !it.isNaN() }
        } else null

        // --- 编号 ---
        //
        // 归位：模型有时把验票码并到票号末尾（见 [IdentifierSplitter]）。
        // 不归位的话，验票码这个**最强唯一编号**就白丢了，而且票号被污染成
        // "110620-...-102984 p74ddQ"，两张不同的票会因为多带的尾巴而"指纹不相等"。
        val idObj = root.optJSONObject("ticket_identifiers")
        val rawIdentifiers = TicketIdentifiers(
            verificationCode = idObj?.optNullableString("verification_code"),
            ticketNumber = idObj?.optNullableString("ticket_number"),
            serialNumber = idObj?.optNullableString("serial_number"),
            terminalNumber = idObj?.optNullableString("terminal_number"),
            stationNumber = idObj?.optNullableString("station_number"),
            barcodeRaw = idObj?.optNullableString("barcode_raw")
        )
        val splitIds = IdentifierSplitter.split(rawIdentifiers)
        val identifiers = splitIds.identifiers
        if (splitIds.changed) {
            LedgerLog.i("Parse", "编号归位：" + splitIds.notes.joinToString("；"))
        }

        // --- 投注 ---
        val bets = mutableListOf<Bet>()
        val betsArr = root.optJSONArray("bets")
        if (betsArr == null || betsArr.length() == 0) {
            issues += ParseIssue("bets", "模型没有返回任何投注内容")
        } else {
            for (i in 0 until betsArr.length()) {
                val betObj = betsArr.optJSONObject(i) ?: continue
                val index = betObj.optInt("index", i + 1)
                val groups = mutableListOf<NumberGroup>()

                val groupsArr = betObj.optJSONArray("groups")
                if (groupsArr == null) {
                    issues += ParseIssue("bets[$i].groups", "第 $index 注缺少 groups")
                    continue
                }
                for (j in 0 until groupsArr.length()) {
                    val gObj = groupsArr.optJSONObject(j) ?: continue
                    val gName = GroupName.from(gObj.optNullableString("name"))
                    if (gName == null) {
                        issues += ParseIssue(
                            "bets[$i].groups[$j].name",
                            "未知号码组：${gObj.optNullableString("name")}"
                        )
                        continue
                    }
                    val numbers = readNumbers(gObj.optJSONArray("numbers"), "bets[$i].groups[$j].numbers", issues)
                    val candidates = readCandidates(gObj.optJSONArray("candidates"), "bets[$i].groups[$j].candidates", issues)
                    groups += NumberGroup(gName, numbers, candidates)
                }
                bets += Bet(index, groups)
            }
        }

        // --- 识别状态 ---
        val recObj = root.optJSONObject("recognition")
        val uncertain = mutableListOf<String>()
        recObj?.optJSONArray("uncertain_fields")?.let { arr ->
            for (k in 0 until arr.length()) {
                arr.optString(k)?.takeIf { it.isNotBlank() }?.let { uncertain += it }
            }
        }
        val modelStatus = recObj?.optNullableString("status")
        val anyCandidate = bets.any { it.isAmbiguous }

        val recognitionStatus = when {
            uncertain.isNotEmpty() || anyCandidate || modelStatus == "partial" ->
                RecognitionStatus.NEEDS_REVIEW
            else -> RecognitionStatus.VERIFIED
        }

        val ticket = RawTicket(
            schemaVersion = root.optInt("schema_version", 1),
            lotteryType = lotteryType,
            issue = issue,
            purchaseTime = purchaseTime,
            drawDate = drawDate,
            betType = betType,
            multiple = multiple,
            additional = additional,
            amountYuan = amount,
            identifiers = identifiers,
            bets = bets,
            recognitionStatus = recognitionStatus,
            uncertainFields = uncertain
        )

        return ParseResult(ticket, issues)
    }

    /**
     * 读号码数组。**保留原样**，只做 null 剔除。
     * 注意这里刻意不做「两位补零」—— 补零属于篡改模型输出，必须由用户确认。
     */
    private fun readNumbers(arr: JSONArray?, path: String, issues: MutableList<ParseIssue>): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            if (arr.isNull(i)) {
                issues += ParseIssue("$path[$i]", "模型标记该号码无法辨认")
                continue
            }
            val v = arr.optString(i, "").trim()
            if (v.isEmpty()) continue
            if (!NumberFormat.isTwoDigit(v)) {
                issues += ParseIssue("$path[$i]", "号码格式不规范：'$v'（应为两位，如 03）")
            }
            out += v
        }
        return out
    }

    private fun readCandidates(arr: JSONArray?, path: String, issues: MutableList<ParseIssue>): List<List<String>> {
        if (arr == null) return emptyList()
        val out = mutableListOf<List<String>>()
        for (i in 0 until arr.length()) {
            val inner = arr.optJSONArray(i)
            if (inner == null) {
                val single = arr.optString(i, "").trim()
                if (single.isNotEmpty()) out += listOf(single)
                continue
            }
            val group = mutableListOf<String>()
            for (j in 0 until inner.length()) {
                inner.optString(j, "").trim().takeIf { it.isNotEmpty() }?.let { group += it }
            }
            if (group.isNotEmpty()) out += group
        }
        if (out.isNotEmpty()) {
            issues += ParseIssue(path, "该组存在 ${out.size} 个候选，需人工确认")
        }
        return out
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "").trim()
        return v.ifEmpty { null }
    }
}
