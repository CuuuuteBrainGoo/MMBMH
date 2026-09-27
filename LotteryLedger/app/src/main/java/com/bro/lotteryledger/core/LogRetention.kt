package com.bro.lotteryledger.core

/**
 * 日志保留策略：**每周清空一次**。
 *
 * 少爷 2026-09-27 的要求：这是个低频使用的 App，日志不该无限增长。
 *
 * ## 为什么是「清空」而不是「按天保留」
 *
 * 日志文件只有一个（`ledger.log`，满了轮转成 `ledger.1.log`），
 * 行里只有 `HH:mm:ss.mmm` 没有日期 —— 想按「保留最近 7 天」逐条裁掉，
 * 就得先给日志行加日期、还要处理跨天，复杂度和收益不成比例。
 * 「每周整份清掉」实现简单、效果直观，也正好对上"低频使用"这个前提。
 *
 * ## 判定为什么单独抽成纯函数
 *
 * 「上次清理时间」的边界情况全在时间比较上（首次运行、时钟回拨、差一点点到 7 天），
 * 这些一旦写错，表现是「日志莫名其妙被清掉」或「永远不清」——
 * 两种都不会报错，只能靠测试发现。所以这一层必须能单测。
 */
object LogRetention {

    /** 保留时长：7 天。 */
    const val INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * 是否该清空日志。
     *
     * @param nowMs 当前时间戳（可注入，便于单测）
     * @param lastSweepMs **上次清空的时间**。null / 0 表示还没有记录（首次运行）
     *
     * 三种情况返回 false（都不清）：
     *  1. 没有上次记录 —— 首次运行要**先建立基准**，否则一装就打的话
     *     会把刚写的日志清掉（而且是每次启动都清）
     *  2. 还没到 7 天
     *  3. 时间倒流（[nowMs] 早于 [lastSweepMs]）—— 用户改过系统时间、
     *     或者时区变动。这种情况**保守不清**：宁可多留几天日志，也别因为
     *     时间乱跳就把日志删了
     */
    fun shouldSweep(nowMs: Long, lastSweepMs: Long?): Boolean {
        if (lastSweepMs == null || lastSweepMs <= 0L) return false
        if (nowMs <= lastSweepMs) return false
        return nowMs - lastSweepMs >= INTERVAL_MS
    }

    /** 距离下次清空还有多久（毫秒）。给日志页显示用；已到期返回 0。 */
    fun msUntilNextSweep(nowMs: Long, lastSweepMs: Long?): Long {
        if (lastSweepMs == null || lastSweepMs <= 0L) return INTERVAL_MS
        if (nowMs <= lastSweepMs) return INTERVAL_MS
        val left = INTERVAL_MS - (nowMs - lastSweepMs)
        return left.coerceAtLeast(0L)
    }
}
