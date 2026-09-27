package com.bro.lotteryledger.core

/**
 * 彩票统一识别数据结构（对应交接稿 §4）。
 * 纯 Kotlin，不 import 任何 android.* —— 保证可在 JVM 上单测。
 */

enum class LotteryType(val code: String, val display: String) {
    SSQ("ssq", "双色球"),
    DLT("dlt", "大乐透");

    companion object {
        fun from(code: String?): LotteryType? = entries.firstOrNull { it.code == code?.lowercase() }
    }
}

/** 复式/单式（§7）。胆拖明确不做。 */
enum class BetType { SINGLE, MULTIPLE }

/** 号码组：双色球用 red/blue，大乐透用 front/back（§4）。 */
enum class GroupName(val id: String, val display: String) {
    RED("red", "红球"),
    BLUE("blue", "蓝球"),
    FRONT("front", "前区"),
    BACK("back", "后区");

    companion object {
        fun from(id: String?): GroupName? = entries.firstOrNull { it.id == id?.lowercase() }
    }
}

/**
 * 一组号码。号码本身是 String 且恒为两位（§2.3：`03` 不得输出 `3`）。
 * [candidates] 非空表示模型给出了多个候选、无法确定。
 */
data class NumberGroup(
    val name: GroupName,
    val numbers: List<String>,
    val candidates: List<List<String>> = emptyList()
) {
    val isAmbiguous: Boolean get() = candidates.isNotEmpty()
}

/** 单注（单式一注，或复式一次选择）。 */
data class Bet(
    val index: Int,
    val groups: List<NumberGroup>
) {
    fun group(name: GroupName): NumberGroup? = groups.firstOrNull { it.name == name }

    fun reds(): List<String> = group(GroupName.RED)?.numbers ?: emptyList()
    fun blues(): List<String> = group(GroupName.BLUE)?.numbers ?: emptyList()
    fun fronts(): List<String> = group(GroupName.FRONT)?.numbers ?: emptyList()
    fun backs(): List<String> = group(GroupName.BACK)?.numbers ?: emptyList()

    val isAmbiguous: Boolean get() = groups.any { it.isAmbiguous }
}

/** 票面识别出的各类唯一编号（§6.3）。全部可为空 —— 看不清就是 null，不猜。 */
data class TicketIdentifiers(
    val verificationCode: String? = null,
    val ticketNumber: String? = null,
    val serialNumber: String? = null,
    val terminalNumber: String? = null,
    val stationNumber: String? = null,
    val barcodeRaw: String? = null
) {
    val isEmpty: Boolean
        get() = listOf(
            verificationCode, ticketNumber, serialNumber,
            terminalNumber, stationNumber, barcodeRaw
        ).all { it.isNullOrBlank() }
}

/** 识别状态（§2.4）。不依赖模型自报置信度。 */
enum class RecognitionStatus {
    /** 主模型结果通过全部程序规则 */
    VERIFIED,
    /** 第二模型独立复核一致 */
    DOUBLE_VERIFIED,
    /** 存在冲突，需人工确认 */
    NEEDS_REVIEW,
    /** 用户已人工确认 */
    MANUAL_VERIFIED;

    companion object {
        fun from(s: String?): RecognitionStatus? = when (s?.lowercase()) {
            "verified" -> VERIFIED
            "double_verified" -> DOUBLE_VERIFIED
            "needs_review" -> NEEDS_REVIEW
            "manual_verified" -> MANUAL_VERIFIED
            else -> null
        }
    }
}

/** 录入来源（§5）。 */
enum class InputMethod(val code: String, val display: String) {
    IMAGE("image", "图片识别"),
    IMAGE_EDITED("image_edited", "图片识别后人工修改"),
    MANUAL("manual", "完全手工录入");

    companion object {
        fun from(code: String?): InputMethod =
            entries.firstOrNull { it.code == code } ?: MANUAL
    }
}

/** 去重判定结果（§6.4）。 */
enum class DuplicateStatus {
    /** 首录，无冲突 */
    UNIQUE,
    /** 强唯一编号完全一致 —— 同一实体票，阻止入账 */
    EXACT_DUPLICATE,
    /** 高度相似但无法确定 —— 交用户选择 */
    POSSIBLE_DUPLICATE,
    /** 用户已确认是另一张实体票，放行 */
    CONFIRMED_DISTINCT;

    companion object {
        fun from(code: String?): DuplicateStatus =
            entries.firstOrNull { it.name == code } ?: UNIQUE
    }
}

/** 票状态（§13）。 */
enum class TicketStatus(val display: String) {
    PENDING_DRAW("未开奖"),
    AWAITING_RESULT("等待开奖结果"),

    /**
     * 开奖日已经过去好几天，但官方接口里查不到这一期的结果。
     *
     * 出现原因通常是：**期号被 AI 读错**、或者这期超出了接口能查到的历史范围。
     * 少爷 2026-09-27 扫了 3 张 4 月份的双色球，就卡在这个情况 ——
     * 以前会一直停在「等待开奖结果」并反复被自动核验，永远出不来。
     *
     * 单独一个状态的价值：**它不再进入待核验队列**（自动重试对它是白费），
     * 同时在详情页给出人工出口（标记未中奖 / 手填金额）。
     */
    RESULT_UNAVAILABLE("查不到开奖结果"),

    NOT_WON("未中奖"),
    PRIZE_PENDING("奖金待确认"),
    TO_REDEEM("待兑奖"),
    REDEEMED("已兑奖"),
    EXPIRED_UNCLAIMED("已过期未兑奖");

    companion object {
        fun from(code: String?): TicketStatus =
            entries.firstOrNull { it.name == code } ?: PENDING_DRAW
    }
}

/**
 * AI 识别出的原始彩票数据（§4）。
 * 所有字段都可空 —— 模型看不清就必须返回 null，禁止猜测（§2.3）。
 */
data class RawTicket(
    val schemaVersion: Int = 1,
    val lotteryType: LotteryType?,
    val issue: String?,
    /** 模型看不清就是 null（§2.3），因此必须有默认值 */
    val purchaseTime: String? = null,
    val drawDate: String? = null,
    val betType: BetType = BetType.SINGLE,
    val multiple: Int = 1,
    val additional: Boolean = false,
    val amountYuan: Double? = null,
    val identifiers: TicketIdentifiers = TicketIdentifiers(),
    val bets: List<Bet> = emptyList(),
    val recognitionStatus: RecognitionStatus = RecognitionStatus.VERIFIED,
    val uncertainFields: List<String> = emptyList()
)
