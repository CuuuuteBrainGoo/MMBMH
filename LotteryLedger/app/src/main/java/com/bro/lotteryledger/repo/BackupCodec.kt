package com.bro.lotteryledger.repo

import com.bro.lotteryledger.db.DrawResultEntity
import com.bro.lotteryledger.db.EditLogEntity
import com.bro.lotteryledger.db.TicketBetEntity
import com.bro.lotteryledger.db.TicketEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份文件的编解码（§17）。
 *
 * ## 为什么用 JSON，而不是直接拷 SQLite 文件
 *
 * 拷 `.db` 看着最省事，实际有两个坑：
 *  1. 数据库开着 **WAL** 模式，还没 checkpoint 的数据在 `.db-wal` 里，
 *     只拷 `.db` 会**静默丢最后一批写入** —— 用户以为备份成功了，其实少几张票。
 *  2. 跨版本恢复会撞上 Room 的「版本不匹配就清空重建」策略，
 *     结果不是恢复数据，而是把数据全清了。
 *
 * JSON 与数据库结构无关，以后加字段旧备份照样能用。
 *
 * ## 为什么不导出识别运行记录和草稿
 *
 * 那两张表是**运行日志**性质（调了多少次 AI、消耗多少 token、拍了一半没入账的草稿），
 * 丢了不影响账目。备份只装「账目本身」：票、注、开奖结果、人工修改留痕。
 *
 * ## 缩进格式
 *
 * `toString(2)` 输出带缩进的 JSON。文件会大 30% 左右，换来**人可读** ——
 * 万一哪天 App 起不来，至少能用文本编辑器把号码抄出来。数据量本来就只有几百 KB，
 * 这点体积换「绝望时还能抢救」是划算的。
 */
object BackupCodec {

    /** 文件里的格式标识，用来挡住「随便选了个 json 文件」的情况。 */
    const val FORMAT = "lottery-ledger-backup"

    /** 备份格式版本。**加字段不用改**（读的时候容忍缺失），只有不兼容变更才 +1。 */
    const val SCHEMA_VERSION = 1

    /** 一张票 + 它的注 + 它的人工修改记录。 */
    data class TicketBundle(
        val ticket: TicketEntity,
        val bets: List<TicketBetEntity>,
        val editLogs: List<EditLogEntity>
    )

    /** 一份完整备份。 */
    data class Snapshot(
        val schemaVersion: Int,
        val appVersion: String,
        val exportedAt: Long,
        val tickets: List<TicketBundle>,
        val drawResults: List<DrawResultEntity>,
        val settings: Map<String, String>
    )

    /** 文件读不了（不是 JSON / 不是本 App 的备份 / 版本太新）。 */
    class BadBackupException(message: String) : Exception(message)

    // ---------------- 编码 ----------------

    fun encode(s: Snapshot): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("schemaVersion", s.schemaVersion)
        root.put("appVersion", s.appVersion)
        root.put("exportedAt", s.exportedAt)
        root.put("ticketCount", s.tickets.size)

        root.put("tickets", JSONArray().also { arr ->
            s.tickets.forEach { b ->
                arr.put(
                    JSONObject().apply {
                        put("ticket", b.ticket.toJson())
                        put("bets", JSONArray().also { a -> b.bets.forEach { a.put(it.toJson()) } })
                        put("editLogs", JSONArray().also { a -> b.editLogs.forEach { a.put(it.toJson()) } })
                    }
                )
            }
        })

        root.put("drawResults", JSONArray().also { a ->
            s.drawResults.forEach { a.put(it.toJson()) }
        })

        root.put("settings", JSONObject().also { o ->
            s.settings.forEach { (k, v) -> o.put(k, v) }
        })

        return root.toString(2)
    }

    // ---------------- 解码 ----------------

    fun decode(json: String): Snapshot {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            throw BadBackupException("这个文件不是有效的 JSON，可能选错了文件。")
        }

        val fmt = root.optString("format")
        if (fmt != FORMAT) {
            throw BadBackupException(
                "这不是「彩票账本」的备份文件。" +
                    if (fmt.isNotBlank()) "（文件里的格式标识是 \"$fmt\"）" else ""
            )
        }

        val ver = root.optInt("schemaVersion", 0)
        if (ver > SCHEMA_VERSION) {
            throw BadBackupException(
                "这份备份来自更新版本的 App（格式 v$ver，本机只支持 v$SCHEMA_VERSION）。" +
                    "请先把 App 升级到最新版再恢复。"
            )
        }

        val tickets = root.optJSONArray("tickets").mapObj { o ->
            TicketBundle(
                ticket = o.getJSONObject("ticket").toTicket(),
                bets = o.optJSONArray("bets").mapObj { it.toBet() },
                editLogs = o.optJSONArray("editLogs").mapObj { it.toEditLog() }
            )
        }

        val draws = root.optJSONArray("drawResults").mapObj { it.toDrawResult() }

        val settings = mutableMapOf<String, String>()
        root.optJSONObject("settings")?.let { o ->
            o.keys().forEach { k -> settings[k] = o.optString(k) }
        }

        return Snapshot(
            schemaVersion = ver,
            appVersion = root.optString("appVersion"),
            exportedAt = root.optLong("exportedAt", 0L),
            tickets = tickets,
            drawResults = draws,
            settings = settings
        )
    }

    // ---------------- JSON 读写小工具 ----------------
    //
    // 坑点：org.json 的 optString() 遇到 JSONObject.NULL 会返回**字符串 "null"**，
    // 所以取值前必须先用 isNull() 判断。这些 helper 把这个判断收在一处。

    private fun JSONObject.putN(key: String, v: Any?) = put(key, v ?: JSONObject.NULL)

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)
    private fun JSONObject.lng(k: String, def: Long = 0L): Long = if (isNull(k)) def else optLong(k, def)
    private fun JSONObject.lngN(k: String): Long? = if (isNull(k)) null else optLong(k, 0L)
    private fun JSONObject.int(k: String, def: Int = 0): Int = if (isNull(k)) def else optInt(k, def)
    private fun JSONObject.intN(k: String): Int? = if (isNull(k)) null else optInt(k, 0)
    private fun JSONObject.dbl(k: String, def: Double = 0.0): Double =
        if (isNull(k)) def else optDouble(k, def)

    private fun JSONObject.dblN(k: String): Double? = if (isNull(k)) null else optDouble(k, 0.0)
    private fun JSONObject.bol(k: String, def: Boolean = false): Boolean =
        if (isNull(k)) def else optBoolean(k, def)

    private inline fun <T> JSONArray?.mapObj(f: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { f(getJSONObject(it)) }
    }

    // ---------------- 实体 ↔ JSON ----------------
    //
    // 字段名用 Kotlin 属性名（不是数据库列名）—— 这是 App 自己的交换格式，
    // 跟数据库列名解耦，将来改列名也不会影响旧备份。

    private fun TicketEntity.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("lotteryType", lotteryType)
        put("issue", issue)
        putN("purchaseTime", purchaseTime)
        put("drawDate", drawDate)
        put("betType", betType)
        put("multiple", multiple)
        put("additional", additional)
        put("purchaseAmount", purchaseAmount)
        put("effectiveUnits", effectiveUnits)
        putN("officialTicketCode", officialTicketCode)
        putN("identityHash", identityHash)
        putN("identityLevel", identityLevel)
        putN("contentHash", contentHash)
        putN("imageHash", imageHash)
        putN("verificationCode", verificationCode)
        putN("ticketNumber", ticketNumber)
        putN("serialNumber", serialNumber)
        putN("terminalNumber", terminalNumber)
        putN("stationNumber", stationNumber)
        putN("barcodeRaw", barcodeRaw)
        put("duplicateStatus", duplicateStatus)
        put("inputMethod", inputMethod)
        put("ticketStatus", ticketStatus)
        putN("prizeAmount", prizeAmount)
        putN("redeemedAmount", redeemedAmount)
        putN("expiredUnclaimedAmount", expiredUnclaimedAmount)
        putN("claimDeadline", claimDeadline)
        putN("redeemedAt", redeemedAt)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    private fun JSONObject.toTicket(): TicketEntity = TicketEntity(
        id = lng("id"),
        lotteryType = optString("lotteryType"),
        issue = optString("issue"),
        purchaseTime = str("purchaseTime"),
        drawDate = optString("drawDate"),
        betType = optString("betType", "SINGLE"),
        multiple = int("multiple", 1),
        additional = bol("additional"),
        purchaseAmount = dbl("purchaseAmount"),
        effectiveUnits = int("effectiveUnits", 1),
        officialTicketCode = str("officialTicketCode"),
        identityHash = str("identityHash"),
        identityLevel = str("identityLevel"),
        contentHash = str("contentHash"),
        imageHash = str("imageHash"),
        verificationCode = str("verificationCode"),
        ticketNumber = str("ticketNumber"),
        serialNumber = str("serialNumber"),
        terminalNumber = str("terminalNumber"),
        stationNumber = str("stationNumber"),
        barcodeRaw = str("barcodeRaw"),
        duplicateStatus = optString("duplicateStatus", "UNIQUE"),
        inputMethod = optString("inputMethod", "manual"),
        ticketStatus = optString("ticketStatus", "PENDING_DRAW"),
        prizeAmount = dblN("prizeAmount"),
        redeemedAmount = dblN("redeemedAmount"),
        expiredUnclaimedAmount = dblN("expiredUnclaimedAmount"),
        claimDeadline = str("claimDeadline"),
        redeemedAt = lngN("redeemedAt"),
        createdAt = lng("createdAt", System.currentTimeMillis()),
        updatedAt = lng("updatedAt", System.currentTimeMillis())
    )

    private fun TicketBetEntity.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("ticketId", ticketId)
        put("betIndex", betIndex)
        put("mainGroup", mainGroup)
        put("mainNumbers", mainNumbers)
        put("secondGroup", secondGroup)
        put("secondNumbers", secondNumbers)
        put("units", units)
    }

    private fun JSONObject.toBet(): TicketBetEntity = TicketBetEntity(
        id = lng("id"),
        ticketId = lng("ticketId"),
        betIndex = int("betIndex", 1),
        mainGroup = optString("mainGroup"),
        mainNumbers = optString("mainNumbers"),
        secondGroup = optString("secondGroup"),
        secondNumbers = optString("secondNumbers"),
        units = int("units", 1)
    )

    private fun EditLogEntity.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        putN("ticketId", ticketId)
        putN("draftId", draftId)
        put("field", field)
        putN("oldValue", oldValue)
        putN("newValue", newValue)
        put("source", source)
        put("editedAt", editedAt)
    }

    private fun JSONObject.toEditLog(): EditLogEntity = EditLogEntity(
        id = lng("id"),
        ticketId = lngN("ticketId"),
        draftId = lngN("draftId"),
        field = optString("field"),
        oldValue = str("oldValue"),
        newValue = str("newValue"),
        source = optString("source", "manual"),
        editedAt = lng("editedAt", System.currentTimeMillis())
    )

    private fun DrawResultEntity.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("lotteryType", lotteryType)
        put("issue", issue)
        put("drawDate", drawDate)
        put("numbers", numbers)
        put("source", source)
        putN("sourceReference", sourceReference)
        put("prizeMode", prizeMode)
        put("firstFetchedAt", firstFetchedAt)
        put("lastVerifiedAt", lastVerifiedAt)
    }

    private fun JSONObject.toDrawResult(): DrawResultEntity = DrawResultEntity(
        id = lng("id"),
        lotteryType = optString("lotteryType"),
        issue = optString("issue"),
        drawDate = optString("drawDate"),
        numbers = optString("numbers"),
        source = optString("source", "official"),
        sourceReference = str("sourceReference"),
        prizeMode = optString("prizeMode", "normal"),
        firstFetchedAt = lng("firstFetchedAt", System.currentTimeMillis()),
        lastVerifiedAt = lng("lastVerifiedAt", System.currentTimeMillis())
    )
}
