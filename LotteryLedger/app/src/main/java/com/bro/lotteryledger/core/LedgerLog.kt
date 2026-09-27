package com.bro.lotteryledger.core

/**
 * 极简日志：内存环形缓冲 + 落盘。
 *
 * 为什么不用 Logcat：
 *  少爷要能「一键复制发给 Bro」——Logcat 需要连电脑或装第三方工具。
 *  这里自己存一份，App 内直接看 / 复制 / 导出 txt。
 *
 * 设计取舍：
 *  - **内存环形缓冲**：只留最近 [CAPACITY] 条，不无限膨胀（个人自用，够用）
 *  - **同时落盘**：App 被杀后日志还在，导出时读文件
 *  - 落盘**追加写**，单文件超 [MAX_FILE_BYTES] 就轮转一次，最多留 2 个文件
 *  - 不记录 API Key、不记录图片 base64（§3 隐私）—— 由调用方保证
 */
object LedgerLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val ts: Long,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        /** 形如 `17:23:45.123  E  [AI] 识别失败：HTTP 404` */
        fun format(): String =
            "%s  %-5s  [%s]  %s".format(TimeFmt.hmsMillis(ts), level.name, tag, message)
    }

    /** 简单的时分秒毫秒格式化，不引 android.text.format（核心层不依赖 android）。 */
    private object TimeFmt {
        fun hmsMillis(ts: Long): String {
            val d = java.util.Date(ts)
            val c = java.util.Calendar.getInstance().apply { time = d }
            return "%02d:%02d:%02d.%03d".format(
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND),
                c.get(java.util.Calendar.MILLISECOND)
            )
        }
        fun full(ts: Long): String {
            val c = java.util.Calendar.getInstance().apply { time = java.util.Date(ts) }
            return "%04d-%02d-%02d %02d:%02d:%02d".format(
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND)
            )
        }
    }

    const val CAPACITY = 500
    const val MAX_FILE_BYTES = 256 * 1024

    private val buffer = ArrayDeque<Entry>()
    private val lock = Any()

    /** 由 App 启动时注入；空表示只存内存不落盘（单测环境）。 */
    @Volatile
    private var sink: ((String) -> Unit)? = null

    /** 单测用：清空缓冲。 */
    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    fun attachSink(writer: (String) -> Unit) {
        sink = writer
    }

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String) = log(Level.ERROR, tag, msg)

    fun e(tag: String, msg: String, t: Throwable) =
        log(Level.ERROR, tag, "$msg\n${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(1200)}")

    private fun log(level: Level, tag: String, msg: String) {
        val entry = Entry(System.currentTimeMillis(), level, tag, msg)
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > CAPACITY) buffer.removeFirst()
        }
        sink?.invoke(entry.format() + "\n")
    }

    /** 快照，最新在前（给人看，倒序更顺手）。 */
    fun snapshotNewestFirst(): List<Entry> =
        synchronized(lock) { buffer.toList().asReversed() }

    fun count(): Int = synchronized(lock) { buffer.size }

    /** 导出用的完整文本，最旧在前（按时间正序读日志更符合习惯）。 */
    fun exportText(): String = buildString {
        append("=== 彩票账本 日志导出 ===\n")
        append("导出时间：").append(TimeFmt.full(System.currentTimeMillis())).append("\n")
        append("条目数：").append(count()).append("\n\n")
        synchronized(lock) {
            buffer.forEach { append(it.format()).append("\n") }
        }
    }

    /** 给文件名用的紧凑时间戳，如 `20260926-172345`。 */
    fun fileStamp(): String {
        val c = java.util.Calendar.getInstance()
        return "%04d%02d%02d-%02d%02d%02d".format(
            c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND)
        )
    }
}
