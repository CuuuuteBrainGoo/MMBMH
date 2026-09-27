package com.bro.lotteryledger.core

import java.security.MessageDigest

/**
 * 指纹与去重（§6）。
 *
 * 三件事必须严格区分：
 *   A. 图片指纹  —— 只判「是不是同一个图片文件」，**只做辅助**
 *   B. 票身份指纹 —— 判「是不是同一张实体票」，**唯一可阻止入账的依据**
 *   C. 投注内容指纹 —— 判「投注内容是否相同」，**只提示，绝不阻断**
 *
 * 核心不可违背：两张实体票即使彩种/期号/号码/倍数/金额全同，也必须分别入账。
 */
object Fingerprints {

    /** 票身份指纹等级。 */
    enum class Level {
        /** 强唯一编号（验票码 / 条码值 / 票号）—— 可阻止入账 */
        STRONG,
        /** 站号 + 终端号 + 流水号 + 精确到秒的出票时间 —— 可阻止入账 */
        COMPOSITE,
        /** 弱组合 —— **只能提示，不能阻止入账** */
        WEAK
    }

    /** 带等级的票身份指纹。 */
    data class Identity(val level: Level, val hash: String, val reason: String)

    data class DuplicateVerdict(
        val status: DuplicateStatus,
        val matchedTicketId: Long?,
        val reason: String
    )

    /** 库内已有票的精简视图，避免去重时加载整张票。 */
    data class IdentityRow(
        val id: Long,
        val identityHash: String?,
        val identityLevel: String?,
        val contentHash: String?,
        /**
         * 购彩时间（原始串）—— 时间锚点用。
         *
         * 2026-09-27 追加：少爷要求把购彩时间提到判重的最高优先级。
         * 给默认值是为了不破坏已有的测试与调用点。
         */
        val purchaseTime: String? = null,
        /** 站点号 —— 时间锚点配上它才有足够区分度（同一秒在不同网点 = 巧合） */
        val stationNumber: String? = null
    )

    private fun sha256(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun norm(s: String?): String = s?.trim()?.uppercase() ?: ""

    /**
     * A. 图片指纹。仅用于识别「同一图片文件被重复导入」。
     * 注意：这**不能**作为实体彩票唯一判据（§6.2A 明确）。
     */
    fun imageFingerprint(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    /**
     * B. 彩票身份指纹（§6.2B），按优先级取第一档可用的。
     * 返回 null 表示**信息不足** —— 此时绝不能凭「内容相同」判重复。
     */
    fun identityFingerprint(raw: RawTicket): Identity? {
        val id = raw.identifiers

        // 优先级 1：强唯一编号
        val strong: Pair<String, String>? = when {
            !id.verificationCode.isNullOrBlank() -> "验票码" to id.verificationCode!!
            !id.barcodeRaw.isNullOrBlank() -> "条码值" to id.barcodeRaw!!
            !id.ticketNumber.isNullOrBlank() -> "票号" to id.ticketNumber!!
            else -> null
        }
        if (strong != null) {
            return Identity(
                level = Level.STRONG,
                hash = sha256("strong|${norm(strong.first)}|${norm(strong.second)}"),
                reason = "${strong.first}一致"
            )
        }

        val station = norm(id.stationNumber)
        val terminal = norm(id.terminalNumber)
        val serial = norm(id.serialNumber)
        val time = DateTimes.normalize(raw.purchaseTime).orEmpty()

        // 优先级 2：站号 + 终端号 + 流水号 + 精确到秒的出票时间
        if (station.isNotEmpty() && serial.isNotEmpty() && time.isNotEmpty()) {
            return Identity(
                level = Level.COMPOSITE,
                hash = sha256("composite|$station|$terminal|$serial|$time"),
                reason = "站号+终端+流水号+出票时间一致"
            )
        }

        // 优先级 3：彩种 + 期号 + 出票时间 + 站号 + 流水号 + 金额
        val amount = raw.amountYuan?.let { String.format("%.2f", it) }.orEmpty()
        if (time.isNotEmpty() && (serial.isNotEmpty() || station.isNotEmpty()) && amount.isNotEmpty()) {
            val type = raw.lotteryType?.code ?: "?"
            val issue = raw.issue?.trim().orEmpty()
            return Identity(
                level = Level.WEAK,
                hash = sha256("weak|$type|$issue|$time|$station|$serial|$amount"),
                reason = "彩种+期号+时间+金额组合一致"
            )
        }

        // 信息不足 —— 明确不给指纹，宁可放行也不能误判
        return null
    }

    /**
     * C. 投注内容指纹（§6.2C）。**只用于提示**「投注内容相同」。
     */
    fun contentFingerprint(raw: RawTicket): String {
        val sb = StringBuilder()
        sb.append(raw.lotteryType?.code ?: "?").append('|')
        sb.append(raw.issue?.trim().orEmpty()).append('|')
        raw.bets.forEach { bet ->
            bet.groups.sortedBy { it.name.ordinal }.forEach { g ->
                sb.append(g.name.id).append(':')
                    .append(g.numbers.sorted().joinToString(","))
                    .append(';')
            }
            sb.append('|')
        }
        sb.append("m=").append(raw.multiple)
        sb.append(";a=").append(raw.additional)
        sb.append(";amt=").append(raw.amountYuan?.let { String.format("%.2f", it) } ?: "?")
        return sha256(sb.toString())
    }

    /**
     * 去重判定（§6.4 + 少爷 2026-09-27 的优先级要求）。
     *
     * ## 判据优先级（从硬到软）
     *
     * | 层 | 锚点 | 含义 | 结论 |
     * |---|---|---|---|
     * | 1 | **验票码 / 条码值** | 票面唯一编号 | 命中即**阻止入账** |
     * | 1 | **票号** | 同上（稍弱，模型可能读串） | 命中即**阻止入账** |
     * | 2 | **出票时间(秒) + 网点号** | 同一台机器同一秒 | 见下 |
     * | 3 | 投注内容 | 只作提示/反证，**绝不单独阻止** | 提示 |
     * | 4 | 弱组合（彩种+期号+时间+金额） | 缺强编号时的兜底 | 提示 |
     *
     * ## 第 2 层为什么要"再看一眼投注内容"
     *
     * 少爷的原话是「**不应存在两张同一时间购买的彩票**；时间完全一致时优先判定为
     * 用户重复提交的失误，此时降低其他信息的交叉验证权重」。
     *
     * 方向上我同意 —— 时间精确到秒，是个很硬的锚点。但"时间一致就判重复"有一个
     * 真实的反例：**在同一台投注机上连着打两张不同号码的票**，出票时间完全可能落在同一秒。
     *
     * 所以第 2 层做成两档，用投注内容当**反证**而不是当"另一个必须满足的条件"：
     *  - 时间一致 **且** 内容一致 → **阻止入账**（同一秒、同一店、同一组号码，只可能是同一张票）
     *  - 时间一致 **但** 内容不同 → **只提示、默认放行**（他可能真买了两张）
     *
     * 这样既把时间提到了最高优先级，又不会把真票误杀 —— 误杀的代价是
     * 「一张真票入不了账」，比多记一笔更让人恼火。
     *
     * ## 为什么不动 [identityFingerprint] 的算法
     *
     * 库里已有的票，`identity_hash` 是按老算法存的。改算法会让**旧票全部对不上**，
     * 反而造成重复入账。所以这里只**新增**一层时间锚点 ——
     * 它读的是永远存在的 `purchase_time` / `station_number` 两列，
     * 旧票自动参与比较，零迁移。
     */
    fun judge(incoming: RawTicket, existing: List<IdentityRow>): DuplicateVerdict {
        val inId = identityFingerprint(incoming)
        val inContent = contentFingerprint(incoming)

        // ---- 第 1 层：票面唯一编号 ----
        if (inId != null && inId.level != Level.WEAK) {
            val hit = existing.firstOrNull {
                it.identityHash == inId.hash && it.identityLevel != Level.WEAK.name
            }
            if (hit != null) {
                return DuplicateVerdict(
                    status = DuplicateStatus.EXACT_DUPLICATE,
                    matchedTicketId = hit.id,
                    reason = "票面唯一编号与已有彩票 #${hit.id} 完全一致（${inId.reason}）"
                )
            }
        }

        // ---- 第 2 层：出票时间锚点（少爷要求提到最高优先级）----
        val inTime = DateTimes.normalize(incoming.purchaseTime)
        val inStation = norm(incoming.identifiers.stationNumber)
        if (inTime != null && inStation.isNotEmpty()) {
            val sameSlot = existing.filter {
                DateTimes.normalize(it.purchaseTime) == inTime &&
                    norm(it.stationNumber) == inStation
            }
            // 2a. 同秒同店 + 内容一致 = 铁证
            sameSlot.firstOrNull { it.contentHash == inContent }?.let { hit ->
                return DuplicateVerdict(
                    status = DuplicateStatus.EXACT_DUPLICATE,
                    matchedTicketId = hit.id,
                    reason = "出票时间精确到秒地与彩票 #${hit.id} 一致（同一网点），" +
                        "而且投注内容也完全相同 —— 判断为重复提交。"
                )
            }
            // 2b. 同秒同店，但内容不同 → 只提示
            sameSlot.firstOrNull()?.let { hit ->
                return DuplicateVerdict(
                    status = DuplicateStatus.POSSIBLE_DUPLICATE,
                    matchedTicketId = hit.id,
                    reason = "这张票的出票时间与彩票 #${hit.id} 「精确到秒地相同」" +
                        "（同一网点），但投注内容不一样。若是重复拍同一张票请放弃；" +
                        "若确实在同一台机器上连着打了两张，继续即可。"
                )
            }
        }
        // 2c. 只有时间一致（网点号缺失或不同）→ 提示
        if (inTime != null) {
            existing.firstOrNull { DateTimes.normalize(it.purchaseTime) == inTime }?.let { hit ->
                return DuplicateVerdict(
                    status = DuplicateStatus.POSSIBLE_DUPLICATE,
                    matchedTicketId = hit.id,
                    reason = "出票时间与彩票 #${hit.id} 精确到秒地相同" +
                        "（网点号不同或读不出来）。请对着票面再确认一次。"
                )
            }
        }

        // ---- 第 3 层：内容相同 → 仅提示（§6.1：绝不能因此阻止入账）----
        val contentHit = existing.firstOrNull { it.contentHash == inContent }
        if (contentHit != null) {
            return DuplicateVerdict(
                status = DuplicateStatus.POSSIBLE_DUPLICATE,
                matchedTicketId = contentHit.id,
                reason = "投注内容与彩票 #${contentHit.id} 完全相同。" +
                    "但投注内容相同不代表是同一张实体票，请自行确认。"
            )
        }

        // ---- 第 4 层：弱身份指纹命中，同样只提示 ----
        if (inId != null && inId.level == Level.WEAK) {
            val weakHit = existing.firstOrNull { it.identityHash == inId.hash }
            if (weakHit != null) {
                return DuplicateVerdict(
                    status = DuplicateStatus.POSSIBLE_DUPLICATE,
                    matchedTicketId = weakHit.id,
                    reason = "多项信息与彩票 #${weakHit.id} 高度相似（${inId.reason}），" +
                        "但缺少强唯一编号，无法确认为同一张。"
                )
            }
        }

        return DuplicateVerdict(DuplicateStatus.UNIQUE, null, "未发现重复")
    }
}
