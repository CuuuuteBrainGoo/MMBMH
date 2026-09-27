package com.bro.lotteryledger.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 数据库实体（§19）。
 *
 * 索引策略：去重查询按 identity/content 指纹走，必须建索引 ——
 * 否则每次入账都要全表扫（§20 说本地操作可忽略，但那是指逻辑不重，不是指没索引）。
 */

@Entity(
    tableName = "ai_providers",
    indices = [Index(value = ["name"], unique = false)]
)
data class AiProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val provider: String,
    val enabled: Boolean = true,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    val model: String,
    /** 指向 Keystore 密文的引用名，**不是** API Key 本身（§1.2） */
    @ColumnInfo(name = "api_key_ref") val apiKeyRef: String,
    val role: String = "primary",
    /** 逗号分隔的彩种 code，空表示全部适用 */
    @ColumnInfo(name = "lottery_types") val lotteryTypes: String = "",
    @ColumnInfo(name = "timeout_seconds") val timeoutSeconds: Int = 45,
    @ColumnInfo(name = "max_retries") val maxRetries: Int = 1,
    @ColumnInfo(name = "image_quality") val imageQuality: String = "HIGH",
    @ColumnInfo(name = "structured_output") val structuredOutput: Boolean = true,
    val temperature: Double = 0.0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "tickets",
    indices = [
        Index(value = ["identity_hash"]),
        Index(value = ["content_hash"]),
        Index(value = ["ticket_status"]),
        Index(value = ["lottery_type", "issue"]),
        Index(value = ["draw_date"])
    ]
)
data class TicketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "lottery_type") val lotteryType: String,
    val issue: String,
    @ColumnInfo(name = "purchase_time") val purchaseTime: String?,
    @ColumnInfo(name = "draw_date") val drawDate: String,
    @ColumnInfo(name = "bet_type") val betType: String,
    val multiple: Int,
    val additional: Boolean,
    @ColumnInfo(name = "purchase_amount") val purchaseAmount: Double,
    /** 有效注数，复式时为组合数（§7.4） */
    @ColumnInfo(name = "effective_units") val effectiveUnits: Int,

    @ColumnInfo(name = "official_ticket_code") val officialTicketCode: String? = null,
    /** 票身份指纹（§6.2B）—— 阻止重复入账的唯一依据 */
    @ColumnInfo(name = "identity_hash") val identityHash: String? = null,
    @ColumnInfo(name = "identity_level") val identityLevel: String? = null,
    @ColumnInfo(name = "content_hash") val contentHash: String? = null,
    /** 图片指纹，仅辅助（§6.2A） */
    @ColumnInfo(name = "image_hash") val imageHash: String? = null,

    // 编号原样保存（§6.3）
    @ColumnInfo(name = "verification_code") val verificationCode: String? = null,
    @ColumnInfo(name = "ticket_number") val ticketNumber: String? = null,
    @ColumnInfo(name = "serial_number") val serialNumber: String? = null,
    @ColumnInfo(name = "terminal_number") val terminalNumber: String? = null,
    @ColumnInfo(name = "station_number") val stationNumber: String? = null,
    @ColumnInfo(name = "barcode_raw") val barcodeRaw: String? = null,

    @ColumnInfo(name = "duplicate_status") val duplicateStatus: String = "UNIQUE",
    @ColumnInfo(name = "input_method") val inputMethod: String = "manual",
    @ColumnInfo(name = "ticket_status") val ticketStatus: String = "PENDING_DRAW",

    @ColumnInfo(name = "prize_amount") val prizeAmount: Double? = null,
    @ColumnInfo(name = "redeemed_amount") val redeemedAmount: Double? = null,
    @ColumnInfo(name = "expired_unclaimed_amount") val expiredUnclaimedAmount: Double? = null,
    @ColumnInfo(name = "claim_deadline") val claimDeadline: String? = null,
    @ColumnInfo(name = "redeemed_at") val redeemedAt: Long? = null,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ticket_bets",
    indices = [Index(value = ["ticket_id"])]
)
data class TicketBetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ticket_id") val ticketId: Long,
    @ColumnInfo(name = "bet_index") val betIndex: Int,
    /** 主号码组名：红球 / 前区 */
    @ColumnInfo(name = "main_group") val mainGroup: String,
    /** 逗号分隔，保持票面顺序 */
    @ColumnInfo(name = "main_numbers") val mainNumbers: String,
    @ColumnInfo(name = "second_group") val secondGroup: String,
    @ColumnInfo(name = "second_numbers") val secondNumbers: String,
    /** 该注的有效组合数（复式 > 1） */
    val units: Int = 1
)

@Entity(
    tableName = "recognition_runs",
    indices = [Index(value = ["ticket_id"]), Index(value = ["timestamp"])]
)
data class RecognitionRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ticket_id") val ticketId: Long? = null,
    @ColumnInfo(name = "draft_id") val draftId: Long? = null,
    val provider: String,
    val model: String,
    @ColumnInfo(name = "request_type") val requestType: String,
    val result: String,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Int? = null,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Int? = null,
    @ColumnInfo(name = "latency_ms") val latencyMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 草稿（§16）：未确认入账前不进正式台账。
 */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 识别出的原始 JSON，原样保存以便回溯 */
    @ColumnInfo(name = "raw_json") val rawJson: String? = null,
    @ColumnInfo(name = "ticket_json") val ticketJson: String,
    @ColumnInfo(name = "recognition_status") val recognitionStatus: String,
    @ColumnInfo(name = "input_method") val inputMethod: String = "image",
    @ColumnInfo(name = "image_hash") val imageHash: String? = null,
    @ColumnInfo(name = "conflicts") val conflicts: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "edit_logs")
data class EditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ticket_id") val ticketId: Long? = null,
    @ColumnInfo(name = "draft_id") val draftId: Long? = null,
    val field: String,
    @ColumnInfo(name = "old_value") val oldValue: String?,
    @ColumnInfo(name = "new_value") val newValue: String?,
    val source: String,
    @ColumnInfo(name = "edited_at") val editedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "draw_results")
data class DrawResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "lottery_type") val lotteryType: String,
    val issue: String,
    @ColumnInfo(name = "draw_date") val drawDate: String,
    /** 逗号分隔，前区/红球在前 */
    val numbers: String,
    val source: String,
    @ColumnInfo(name = "source_reference") val sourceReference: String? = null,
    /** 大乐透固定奖规则模式：normal / enhanced（§12.3）。不保存奖池金额本身。 */
    @ColumnInfo(name = "prize_mode") val prizeMode: String = "normal",
    @ColumnInfo(name = "first_fetched_at") val firstFetchedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_verified_at") val lastVerifiedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String
)
