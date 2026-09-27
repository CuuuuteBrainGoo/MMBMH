package com.bro.lotteryledger.core

/**
 * 连续点击的计数逻辑（「拜拜财神」入口用）。
 *
 * 少爷的原话是「在该行上**一次性连续点击 13 次**」——
 * 「连续」这两个字是有要求的：今天点 6 次、明天点 7 次不该凑够 13。
 *
 * ## 为什么单独抽出来
 *
 * 全是整数时间比较，边界（1.5 秒整算不算连续？系统时间回拨怎么办？）
 * 一旦写错，表现是「偶尔要点多几次才触发」或「隔很久也能累积」——
 * **两个都不会报错**，只在真机上偶然表现出来。所以必须能单测。
 */
object TapSequence {

    /**
     * 两次点击最多隔多久还算同一轮。
     *
     * 1.5 秒：比人手连点慢一点，但比"停一下想事情再点"快 ——
     * 用户真在连点时会远快于这个值，而中途分神一下就自动重新计数。
     */
    const val WINDOW_MS = 1_500L

    /**
     * 算下一次的计数。
     *
     * @param currentTaps 当前计数
     * @param lastTapAt 上一次点击时间（0 表示这是第一次）
     * @param now 本次点击时间
     * @return 新的计数。间隔超过 [WINDOW_MS]、或时间倒流，都**从 1 重新开始**
     */
    fun next(currentTaps: Int, lastTapAt: Long, now: Long): Int {
        if (currentTaps <= 0 || lastTapAt <= 0L) return 1
        // 时间倒流（用户改了系统时间）时不算连续 —— 保守重数，
        // 好过用错的时间差凑出一个虚高的计数
        if (now < lastTapAt) return 1
        val gap = now - lastTapAt
        return if (gap <= WINDOW_MS) currentTaps + 1 else 1
    }
}
