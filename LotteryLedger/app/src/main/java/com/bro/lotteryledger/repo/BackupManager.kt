package com.bro.lotteryledger.repo

import android.content.Context
import android.net.Uri
import com.bro.lotteryledger.core.LedgerLog
import com.bro.lotteryledger.db.TicketEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 备份与恢复的 IO 编排（§17）。
 *
 * 只负责「和数据库、文件系统打交道」，编解码交给 [BackupCodec]
 * —— 那样纯逻辑能跑 JVM 单测，不会因为引了 Context 就没法测。
 *
 * ## 恢复策略：合并，不清空
 *
 * 导入时逐张查重（优先按票身份指纹，指纹缺失时按「彩种+期号+购买时间+金额」），
 * **已存在就跳过，不存在才插入**。所以同一份备份导入多少次都不会产生重复票。
 *
 * 为什么不做「清空后恢复」：那等于给用户一个「一键删光所有账目」的按钮，
 * 误点一次就没了。合并策略没有这个风险，而且反复导入是幂等的。
 */
class BackupManager(private val context: Context) {

    private val repo = LedgerRepository(context)

    /** 恢复前给用户看的预览。 */
    data class Preview(
        val total: Int,
        val newTickets: Int,
        val duplicates: Int,
        val drawResults: Int,
        val appVersion: String,
        val exportedAt: Long
    )

    /** 恢复结果。 */
    data class ImportResult(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val draws: Int
    )

    // ---------------- 导出 ----------------

    /** 读出当前全部账目，组装成快照。 */
    suspend fun buildSnapshot(): BackupCodec.Snapshot = withContext(Dispatchers.IO) {
        val bundles = repo.allTickets().map { t ->
            BackupCodec.TicketBundle(
                ticket = t,
                bets = repo.betsOf(t.id),
                editLogs = repo.editsOf(t.id)
            )
        }

        // 只带走「偏好」类设置；API Key 存在 Keystore 里，本来就导不出来（§17 硬要求）
        val settings = mutableMapOf<String, String>()
        repo.getSetting(SettingsStore.KEY_THINKING_LEVEL)?.let {
            settings[SettingsStore.KEY_THINKING_LEVEL] = it
        }

        BackupCodec.Snapshot(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            appVersion = com.bro.lotteryledger.BuildConfig.VERSION_NAME,
            exportedAt = System.currentTimeMillis(),
            tickets = bundles,
            drawResults = repo.allDraws(),
            settings = settings
        )
    }

    /** 导出到用户选定的位置，返回备份里的票数。 */
    suspend fun exportTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val snapshot = buildSnapshot()
        val json = BackupCodec.encode(snapshot)
        val bytes = json.toByteArray(Charsets.UTF_8)

        // "wt" 是 write + truncate，覆盖已有文件时不会留尾巴；
        // 少数 provider 不认 "wt"，退回默认的 "w"。
        val out = try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (_: Exception) {
            context.contentResolver.openOutputStream(uri)
        } ?: throw IllegalStateException("无法写入所选位置，换个文件夹试试")

        out.use {
            it.write(bytes)
            it.flush()
        }

        LedgerLog.i(
            "Backup",
            "已导出：${snapshot.tickets.size} 张票、" +
                "${snapshot.drawResults.size} 期开奖结果，${bytes.size / 1024} KB"
        )
        snapshot.tickets.size
    }

    // ---------------- 导入 ----------------

    /** 读取并解析备份文件。格式不对会抛 [BackupCodec.BadBackupException]。 */
    suspend fun readSnapshot(uri: Uri): BackupCodec.Snapshot = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalStateException("读不到这个文件，可能权限被收回了，重新选一次试试")
        BackupCodec.decode(text)
    }

    /** 比对备份与本机库，算出会新增多少、跳过多少。**不写库**。 */
    suspend fun inspect(s: BackupCodec.Snapshot): Preview = withContext(Dispatchers.IO) {
        val dup = s.tickets.count { isDuplicate(it.ticket) }
        Preview(
            total = s.tickets.size,
            newTickets = s.tickets.size - dup,
            duplicates = dup,
            drawResults = s.drawResults.size,
            appVersion = s.appVersion,
            exportedAt = s.exportedAt
        )
    }

    /**
     * 执行恢复。幂等 —— 重复导入同一份备份，第二次会全部算「跳过」。
     */
    suspend fun restore(s: BackupCodec.Snapshot): ImportResult = withContext(Dispatchers.IO) {
        var imported = 0
        var skipped = 0
        var failed = 0

        s.tickets.forEach { b ->
            if (isDuplicate(b.ticket)) {
                skipped++
                return@forEach
            }
            try {
                val newId = repo.insertTicketRaw(b.ticket, b.bets)
                // 编辑日志要指向新票号 —— 备份里的 id 在这台机器上没有意义
                b.editLogs.forEach { repo.insertEditLogRaw(it.copy(ticketId = newId)) }
                imported++
            } catch (e: Exception) {
                failed++
                LedgerLog.e("Backup", "导入第 ${b.ticket.issue} 期的票失败", e)
            }
        }

        var draws = 0
        s.drawResults.forEach { if (repo.insertDrawResultRaw(it)) draws++ }

        // 设置**只补缺不覆盖** —— 本机的偏好是用户刚设的，不该被旧备份改回去
        s.settings.forEach { (k, v) ->
            if (repo.getSetting(k) == null) repo.setSetting(k, v)
        }

        LedgerLog.i(
            "Backup",
            "恢复完成：新增 $imported 张、跳过 $skipped 张（已存在）、失败 $failed 张、" +
                "开奖结果 $draws 期"
        )
        ImportResult(imported, skipped, failed, draws)
    }

    /**
     * 判断这张票在本机是否已存在。
     *
     * 优先用票身份指纹（§6.2B）—— 那是防止重复入账的**唯一权威依据**。
     * 指纹缺失（弱票，模型没读到任何编号）时退回自然键比对：
     * 「彩种 + 期号 + 购买时间 + 购买金额」。这个组合不是绝对唯一，
     * 但比「同一天买的两张同期票被当成重复」要安全得多。
     */
    private suspend fun isDuplicate(t: TicketEntity): Boolean {
        val hash = t.identityHash
        if (!hash.isNullOrBlank() && repo.findByIdentity(hash) != null) return true
        return repo.findByNaturalKey(
            type = t.lotteryType,
            issue = t.issue,
            purchaseTime = t.purchaseTime.orEmpty(),
            amount = t.purchaseAmount
        ) != null
    }
}
