package com.bro.lotteryledger.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface AiProviderDao {
    @Query("SELECT * FROM ai_providers ORDER BY id ASC")
    suspend fun all(): List<AiProviderEntity>

    @Query("SELECT * FROM ai_providers WHERE enabled = 1 ORDER BY id ASC")
    suspend fun enabled(): List<AiProviderEntity>

    @Query("SELECT * FROM ai_providers WHERE id = :id")
    suspend fun byId(id: Long): AiProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: AiProviderEntity): Long

    @Update
    suspend fun update(e: AiProviderEntity)

    @Query("DELETE FROM ai_providers WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TicketDao {

    @Insert
    suspend fun insertTicket(e: TicketEntity): Long

    @Insert
    suspend fun insertBets(list: List<TicketBetEntity>)

    @Update
    suspend fun updateTicket(e: TicketEntity)

    /**
     * 全部票，**按开奖日期倒序**（少爷 2026-09-27 第 2 条）。
     *
     * 以前是 `ORDER BY id DESC` —— 也就是按**录入顺序**排。
     * 后果：补录几张往期的老票之后，它们会全部冒到列表最上面，
     * 而更接近今天开奖的票被挤到下面，看起来就是"排序乱了"。
     *
     * 现在的排序规则：
     * 1. `draw_date DESC` —— 离最新开奖日期越近越靠前（主诉求）
     * 2. `issue DESC` —— 同一天开奖的（同期不同彩种）按期号大的在前
     * 3. `purchase_time ASC` —— 同一期内确实买了多张时，按出票时间**从早到晚**排，
     *    这样和界面上标的「2/3」序号顺序一致（第 1 张就是最早买的那张）
     *    `IFNULL(...,'~')` 是把读不出时间的排到最后，别插在最前面
     * 4. `id ASC` —— 兜底，保证同输入下顺序稳定（不然每次刷新顺序会跳）
     */
    @Query(
        """
        SELECT * FROM tickets
        ORDER BY draw_date DESC,
                 issue DESC,
                 IFNULL(purchase_time, '~') ASC,
                 id ASC
        """
    )
    suspend fun all(): List<TicketEntity>

    @Query("SELECT * FROM tickets WHERE id = :id")
    suspend fun byId(id: Long): TicketEntity?

    /**
     * 备份恢复时的查重：按**票身份指纹**找已存在的票（§6.2B）。
     * 这是最可靠的去重依据，所以优先用它。
     */
    @Query("SELECT * FROM tickets WHERE identity_hash = :hash LIMIT 1")
    suspend fun byIdentity(hash: String): TicketEntity?

    /**
     * 备份恢复时的查重兜底：指纹缺失（弱票）时，
     * 用「彩种 + 期号 + 购买时间 + 购买金额」这组自然键来判断是不是同一张票。
     */
    @Query(
        """
        SELECT * FROM tickets
        WHERE lottery_type = :type
          AND issue = :issue
          AND IFNULL(purchase_time, '') = :purchaseTime
          AND purchase_amount = :amount
        LIMIT 1
        """
    )
    suspend fun byNaturalKey(
        type: String, issue: String, purchaseTime: String, amount: Double
    ): TicketEntity?

    @Query("SELECT * FROM ticket_bets WHERE ticket_id = :ticketId ORDER BY bet_index ASC")
    suspend fun betsOf(ticketId: Long): List<TicketBetEntity>

    @Query("DELETE FROM tickets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM ticket_bets WHERE ticket_id = :ticketId")
    suspend fun deleteBets(ticketId: Long)

    /**
     * 「拜拜财神」用：清空全部票。
     *
     * ⚠️ 这里只删业务数据。**绝不能改成 `clearAllTables()`** ——
     * 那会把 `ai_providers`（平台配置）和 `app_settings`（思考强度等设置）
     * 一起清掉，而少爷明确要求「不得改动软件的任何设置项」。
     */
    @Query("DELETE FROM tickets")
    suspend fun wipeAllTickets(): Int

    @Query("DELETE FROM ticket_bets")
    suspend fun wipeAllBets(): Int

    /** 注数合计（清空确认框里显示「要清掉多少注」）。 */
    @Query("SELECT COUNT(*) FROM ticket_bets")
    suspend fun countBets(): Int

    /**
     * 去重候选：只取指纹相关列。
     * 不加载整表 —— 票多了之后这个差别很大。
     *
     * `purchase_time` / `station_number` 是 2026-09-27 追加的：
     * 少爷要求把**购彩时间**提到判重的最高优先级，而这两列要参与判定。
     */
    @Query(
        "SELECT id, identity_hash, identity_level, content_hash, " +
            "purchase_time, station_number FROM tickets"
    )
    suspend fun identityRows(): List<IdentityRowProjection>

    /**
     * 全部注 + 它所属票的彩种 / 期号 / 开奖日，一次取全。
     *
     * 给「号码撞上本期大奖」的彩蛋用（[com.bro.lotteryledger.core.JackpotTease]）。
     * 写成联表而不是「查出所有票再逐张取注」—— 后者是 N+1，
     * 票多了会明显变慢，而这个检查每次冷启动都要跑。
     */
    @Query(
        """
        SELECT t.id AS ticketId, t.lottery_type AS lotteryType, t.issue AS issue,
               t.draw_date AS drawDate, b.bet_index AS betIndex,
               b.main_numbers AS mainNumbers, b.second_numbers AS secondNumbers
        FROM ticket_bets b
        JOIN tickets t ON t.id = b.ticket_id
        """
    )
    suspend fun allBetsWithTicket(): List<BetWithTicketProjection>

    @Query("SELECT * FROM tickets WHERE ticket_status = :status ORDER BY draw_date ASC")
    suspend fun byStatus(status: String): List<TicketEntity>

    /**
     * 待核验：开奖日期**确实已过**（`draw_date <= cutoff`），但状态还没出结果。
     *
     * ⚠️ `cutoff` **不是「今天」**，是 [com.bro.lotteryledger.core.DrawSchedule.checkCutoff]
     * 算出的「已过开奖时间的最大日期」—— 当天 22:00 之前它等于**昨天**。
     * 直接把今天当截止日，就会让开奖日**当天下午**的票被提前判成
     * 「已过开奖日期」（2026-09-28 少爷报的 bug：大乐透当晚 21:30 才开奖，
     * 18:12 首页就弹了「有 1 张彩票已过开奖日期」）。
     */
    @Query(
        """
        SELECT * FROM tickets
        WHERE draw_date <= :cutoff
          AND ticket_status IN ('PENDING_DRAW', 'AWAITING_RESULT')
        ORDER BY draw_date ASC
        """
    )
    suspend fun needingCheck(cutoff: String): List<TicketEntity>

    @Transaction
    suspend fun insertWithBets(ticket: TicketEntity, bets: List<TicketBetEntity>): Long {
        val id = insertTicket(ticket)
        insertBets(bets.map { it.copy(ticketId = id) })
        return id
    }

    @Transaction
    suspend fun replaceBets(ticketId: Long, bets: List<TicketBetEntity>) {
        deleteBets(ticketId)
        insertBets(bets.map { it.copy(ticketId = ticketId) })
    }

    /**
     * 台账统计（§15）。
     *
     * ⚠️ `prizeConfirmed` **排除已过期未兑的票**（少爷 2026-09-27 要求）。
     *
     * 原因：过期票在转状态时 `prize_amount` 是**保留**的（要显示"作废了多少钱"），
     * 如果不过滤，那笔永远拿不到的钱会被算进「中奖」——
     * 净收支会凭空多出一笔收入，而用户以为钱已经到手了。
     *
     * 少爷的原话：「总购彩支出等账目里，这张票按未中奖算，忽略过期未兑奖金金额。
     * 过期未兑奖金只标记在过期未兑账里，不影响其他账目。」
     *
     * 所以口径是：
     *  - `purchase`  照算（钱确实花出去了，不管中没中、兑没兑）
     *  - `prizeConfirmed` **不含**过期票 → 净收支把它当"未中奖"
     *  - `expired`   单独统计，界面上用绿色 + 负号显示
     */
    @Query(
        """
        SELECT
          IFNULL(SUM(purchase_amount), 0)                        AS purchase,
          IFNULL(SUM(CASE WHEN prize_amount IS NOT NULL
                            AND ticket_status != 'EXPIRED_UNCLAIMED'
                          THEN prize_amount ELSE 0 END), 0)      AS prizeConfirmed,
          IFNULL(SUM(CASE WHEN redeemed_amount IS NOT NULL THEN redeemed_amount ELSE 0 END), 0) AS redeemed,
          IFNULL(SUM(CASE WHEN ticket_status = 'TO_REDEEM' AND prize_amount IS NOT NULL
                          THEN prize_amount ELSE 0 END), 0)      AS toRedeem,
          IFNULL(SUM(CASE WHEN expired_unclaimed_amount IS NOT NULL
                          THEN expired_unclaimed_amount ELSE 0 END), 0) AS expired,
          IFNULL(SUM(CASE WHEN ticket_status = 'PRIZE_PENDING' THEN 1 ELSE 0 END), 0) AS pendingPrizeCount,
          COUNT(*)                                               AS total
        FROM tickets
        """
    )
    suspend fun stats(): StatsProjection
}

data class IdentityRowProjection(
    val id: Long,
    @androidx.room.ColumnInfo(name = "identity_hash") val identityHash: String?,
    @androidx.room.ColumnInfo(name = "identity_level") val identityLevel: String?,
    @androidx.room.ColumnInfo(name = "content_hash") val contentHash: String?,
    /** 购彩时间（秒级）—— 时间锚点用，2026-09-27 追加 */
    @androidx.room.ColumnInfo(name = "purchase_time") val purchaseTime: String?,
    /** 站点号 —— 时间锚点要配上它才有足够区分度 */
    @androidx.room.ColumnInfo(name = "station_number") val stationNumber: String?
)

/** 一注 + 它所属票的关键字段（[TicketDao.allBetsWithTicket] 的投影）。 */
data class BetWithTicketProjection(
    val ticketId: Long,
    @androidx.room.ColumnInfo(name = "lotteryType") val lotteryType: String,
    val issue: String,
    @androidx.room.ColumnInfo(name = "drawDate") val drawDate: String,
    @androidx.room.ColumnInfo(name = "betIndex") val betIndex: Int,
    @androidx.room.ColumnInfo(name = "mainNumbers") val mainNumbers: String,
    @androidx.room.ColumnInfo(name = "secondNumbers") val secondNumbers: String
)

data class StatsProjection(
    val purchase: Double,
    val prizeConfirmed: Double,
    val redeemed: Double,
    val toRedeem: Double,
    val expired: Double,
    val pendingPrizeCount: Int,
    val total: Int
)

@Dao
interface RecognitionRunDao {
    @Insert
    suspend fun insert(e: RecognitionRunEntity): Long

    @Query("SELECT * FROM recognition_runs ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<RecognitionRunEntity>

    /** 「拜拜财神」用：清空 AI 调用记录（首页那栏「识别 N 次 / M token」会归零）。 */
    @Query("DELETE FROM recognition_runs")
    suspend fun wipeAll(): Int

    @Query("SELECT IFNULL(SUM(prompt_tokens),0)+IFNULL(SUM(completion_tokens),0) FROM recognition_runs")
    suspend fun totalTokens(): Long

    @Query("SELECT COUNT(*) FROM recognition_runs")
    suspend fun callCount(): Long
}

@Dao
interface DraftDao {
    @Insert
    suspend fun insert(e: DraftEntity): Long

    @Update
    suspend fun update(e: DraftEntity)

    @Query("SELECT * FROM drafts ORDER BY id DESC")
    suspend fun all(): List<DraftEntity>

    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun byId(id: Long): DraftEntity?

    /**
     * 取最早的一条草稿。
     *
     * 批量导入把草稿表当**队列**用：识别完一张存一条，核对完删一条。
     * 取最旧的（而不是最新）才能保证「先进先出」——
     * 少爷先拍的那张先核对，顺序和拍照顺序一致。
     */
    @Query("SELECT * FROM drafts ORDER BY id ASC LIMIT 1")
    suspend fun oldest(): DraftEntity?

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun delete(id: Long)

    /** 「拜拜财神」用：清空核对队列里的草稿。 */
    @Query("DELETE FROM drafts")
    suspend fun wipeAll(): Int
}

@Dao
interface EditLogDao {
    @Insert
    suspend fun insert(e: EditLogEntity)

    @Query("SELECT * FROM edit_logs WHERE ticket_id = :ticketId ORDER BY id ASC")
    suspend fun ofTicket(ticketId: Long): List<EditLogEntity>

    /** 导出备份用。 */
    @Query("SELECT * FROM edit_logs ORDER BY id ASC")
    suspend fun all(): List<EditLogEntity>

    /** 「拜拜财神」用：清空修改留痕。 */
    @Query("DELETE FROM edit_logs")
    suspend fun wipeAll(): Int
}

@Dao
interface DrawResultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: DrawResultEntity): Long

    @Query("SELECT * FROM draw_results WHERE lottery_type = :type AND issue = :issue LIMIT 1")
    suspend fun find(type: String, issue: String): DrawResultEntity?

    @Query("SELECT * FROM draw_results ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<DrawResultEntity>

    /** 导出备份用（升序，让备份文件里的顺序稳定、便于比对）。 */
    @Query("SELECT * FROM draw_results ORDER BY id ASC")
    suspend fun all(): List<DrawResultEntity>

    /** 「拜拜财神」用：清空已抓到的开奖结果（下次对奖会重新拉）。 */
    @Query("DELETE FROM draw_results")
    suspend fun wipeAll(): Int
}

@Dao
interface AppSettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: AppSettingEntity)

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM app_settings")
    suspend fun all(): List<AppSettingEntity>
}
