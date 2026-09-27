package com.bro.lotteryledger.core

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * 把 [LedgerLog] 的日志追加到私有目录文件。
 *
 * 为什么不在核心层直接写文件：
 *  核心层要能跑 JVM 单测（不依赖 android），所以用「注入 sink」的方式解耦。
 *
 * 文件策略：
 *  - 路径 `filesDir/logs/ledger.log`
 *  - 超过 [LedgerLog.MAX_FILE_BYTES] 时轮转成 `ledger.1.log`（只留一份旧文件）
 *  - **每周整份清空一次**（见 [sweepWeekly]，少爷 2026-09-27 要求）
 *  - 单线程写，避免多线程交错写坏文件
 */
class LogFileSink(private val appContext: Context) {

    private val dir: File get() = File(appContext.filesDir, "logs").apply { mkdirs() }
    private val current: File get() = File(dir, "ledger.log")
    private val rotated: File get() = File(dir, "ledger.1.log")

    /**
     * 「上次清空日志」的时间戳。
     *
     * 单独存一个文件而不是塞进 `app_settings` 表：那是**用户的设置项**，
     * 这张表会被导出进备份、也会被「恢复备份」覆盖 —— 日志轮转是程序内部的
     * 维护记录，混进用户设置里两边都不干净。
     * 清空日志时**不删它**，否则下次又变成「首次运行」，永远清不掉。
     */
    private val sweepStamp: File get() = File(dir, ".last_sweep")

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ledger-log-writer").apply { isDaemon = true }
    }

    fun install() {
        LedgerLog.attachSink { line -> executor.execute { append(line) } }
    }

    private fun append(line: String) {
        try {
            if (current.length() > LedgerLog.MAX_FILE_BYTES) {
                if (rotated.exists()) rotated.delete()
                current.renameTo(rotated)
            }
            current.appendText(line, Charsets.UTF_8)
        } catch (_: Exception) {
            // 日志写失败绝不能影响主流程
        }
    }

    /** 导出：内存快照 + 磁盘历史。内存里是最新的，磁盘里可能有更早的。 */
    fun exportAll(): String {
        val sb = StringBuilder()
        try {
            if (rotated.exists()) {
                sb.append("=== 历史日志（上一份）===\n")
                sb.append(rotated.readText(Charsets.UTF_8))
                sb.append("\n")
            }
        } catch (_: Exception) {
        }
        sb.append(LedgerLog.exportText())
        return sb.toString()
    }

    /**
     * 按周清空日志（少爷 2026-09-27 要求：低频使用，日志不该无限增长）。
     *
     * 判定逻辑全在 [LogRetention.shouldSweep] 里（纯函数，有单测）。
     *
     * @return 是否真的清空了
     */
    fun sweepWeekly(nowMs: Long = System.currentTimeMillis()): Boolean {
        return try {
            val last = sweepStamp.takeIf { it.exists() }
                ?.readText(Charsets.UTF_8)?.trim()?.toLongOrNull()

            if (!LogRetention.shouldSweep(nowMs, last)) {
                // 首次运行（或记录丢了）：**只建立基准，不动日志**。
                // 不这么做的话装好当天就会把刚写的日志清掉，而且每次启动都清。
                if (last == null) sweepStamp.writeText(nowMs.toString(), Charsets.UTF_8)
                return false
            }

            listOf(current, rotated).forEach { if (it.exists()) it.delete() }
            // 标记文件本身不删 —— 删了下次又变成「首次运行」，就再也清不掉了
            sweepStamp.writeText(nowMs.toString(), Charsets.UTF_8)

            // 清完写一条起点记录。两个作用：
            //  ① 新一周的日志有个开头，不是空文件；
            //  ② 以后看日志的人知道「这里被清过一次」，不会以为日志丢了
            LedgerLog.i(
                "Log",
                "已按周清理日志（保留期 ${LogRetention.INTERVAL_MS / 86_400_000} 天，" +
                    "上次清理于 ${LogRetention.INTERVAL_MS / 86_400_000} 天前）"
            )
            true
        } catch (_: Exception) {
            false   // 日志清理失败绝不能影响启动
        }
    }
}
