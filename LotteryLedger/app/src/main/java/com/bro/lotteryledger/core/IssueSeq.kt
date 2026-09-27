package com.bro.lotteryledger.core

/**
 * 「同一期同一彩种买了多张」的序号标注（少爷 2026-09-27 第 2 条）。
 *
 * > 同一期双色球 / 大乐透录入多张不同彩票时，应在某位置以 1、2、3 等数字标注，以体现区别。
 *
 * 为什么要这个：一张 5 注的票和「同一期买的 5 张单注票」在列表里长得几乎一样，
 * 光看「双色球 · 2026102 期 · 5 注」分不清是**一张票**还是**五张票**。
 * 标上 `2/5` 就一目了然。
 *
 * ## 序号从哪来
 *
 * **按当前列表顺序**（也就是购票时间从早到晚）编号 ——
 * 所以「第 1 张」永远是这一期最早买的那张。
 * 这不是随便定的：序号必须和列表顺序一致，否则用户核对时会以为顺序错了。
 * （排序规则见 `TicketDao.all()` 的注释。）
 *
 * 泛型是为了让它待在 `core` 里保持纯 Kotlin、可单测 —— 不依赖 Room 实体。
 */
object IssueSeq {

    /**
     * @param items 已排好序的票列表
     * @param keyOf 分组键（用「彩种 + 期号」）
     * @param idOf  票的 id
     * @return `票 id → (第几张, 共几张)`。
     *         **同一组只有一张票的不收录** —— 只有一张时标「1/1」纯属噪音。
     */
    fun <T> build(
        items: List<T>,
        keyOf: (T) -> String,
        idOf: (T) -> Long
    ): Map<Long, Pair<Int, Int>> {
        // LinkedHashMap 保持遇到顺序 = 列表顺序 = 购票时间顺序
        val grouped = LinkedHashMap<String, MutableList<Long>>()
        items.forEach { item ->
            grouped.getOrPut(keyOf(item)) { mutableListOf() }.add(idOf(item))
        }

        val out = LinkedHashMap<Long, Pair<Int, Int>>()
        grouped.values.forEach { ids ->
            if (ids.size <= 1) return@forEach
            ids.forEachIndexed { i, id -> out[id] = (i + 1) to ids.size }
        }
        return out
    }

    /** 分组键：彩种 + 期号。两个都用 `|` 隔开，避免拼接歧义。 */
    fun key(lotteryType: String?, issue: String?): String =
        "${lotteryType.orEmpty()}|${issue.orEmpty()}"
}
