package com.bro.lotteryledger.core

/**
 * 彩票展示编号（少爷 2026-09-27 要求）。
 *
 * > 「在首页每个彩票前面加一个编号，越新的编号数字越大，作为每张彩票展示给用户的编码。
 * >  如果其他功能有提示涉及具体某个彩票，可以附带编码，让用户更好定位。」
 *
 * ## 编号规则（少爷亲口追加确认的两条边界）
 *
 * 1. **只给成功录入的票编号** —— 草稿/待核对的票不在列表里，也就没有编号。
 * 2. **后录入的票若开奖日期在若干票之前，就插到那个位置，并把它后面的票全部更新编号。**
 *    → 这正是「按列表位置编」的语义：编号跟着**列表顺序**走，不是跟着录入时间走。
 * 3. **筛选时编号不变** —— 筛选只决定「显示哪几张」，编号始终按**全量列表**算。
 *    否则筛一次编号全变，用户刚记下的「第 12 张」就对不上了。
 *
 * ## 具体怎么算
 *
 * 列表按开奖日期**倒序**（越新越靠前，见 `TicketDao.all()` 的注释），
 * 而我们希望**越新的编号越大**。所以：
 *
 * ```
 * 编号 = 总数 - 在列表中的下标
 * ```
 *
 * 即：列表**最下面**那张（最旧）= `1`，**最上面**那张（最新）= `总数`。
 * 这样「数字越大 = 越新」天然成立，且**编号连续、没有空号**。
 *
 * ## 删票会怎样
 *
 * 编号会整体顺移（删掉中间一张，它上面那些票的编号各减 1）。
 * 这是**刻意的取舍** —— 换来的是「编号永远连续 + 越大越新永远成立」。
 * 如果反过来做成「入账时分配、永不变」，编号在按日期排序的列表里就会是乱序的
 * （第 3 号票排在第 12 号票上面），用户照着编号找反而更费劲。
 *
 * ## 为什么放 core 且不依赖 Room
 *
 * 编排全是纯计算，放这里就能在 JVM 上直接单测 —— 跟 [IssueSeq] 同一套路。
 */
object TicketCode {

    /**
     * 给全量票列表编号。
     *
     * @param ordered 已按「开奖日期倒序」排好的**全量**票列表（不是筛选后的！）
     * @param idOf    取票的 id
     * @return `票 id → 编号`。列表最下面那张是 1，最上面那张是 size。
     */
    fun <T> assign(ordered: List<T>, idOf: (T) -> Long): Map<Long, Int> {
        val n = ordered.size
        if (n == 0) return emptyMap()
        val out = LinkedHashMap<Long, Int>(n)
        ordered.forEachIndexed { index, item ->
            out[idOf(item)] = n - index
        }
        return out
    }

    /**
     * 取某张票的编号，拿不到就返回 null。
     *
     * 提示文案里到处要用，单独抽一个 —— 免得每处都写 `map[id] ?: continue`。
     */
    fun codeOf(codes: Map<Long, Int>, id: Long): Int? = codes[id]

    /** 把编号显示成人看的样子：`#12`。统一格式，别各写各的。 */
    fun label(code: Int?): String? = code?.let { "#$it" }

    /**
     * 把一组票的编号拼成一句提示，形如 `#3、#7、#12`。
     *
     * 超过 [max] 个就在末尾省略（首页那种「有 5 张查不到结果」的提示，
     * 全列出来会把提示条撑爆）。编号**从大到小**排 —— 跟列表顺序一致，
     * 用户从上往下看列表就能依次对上。
     */
    fun join(codes: List<Int?>, max: Int = 6): String {
        val valid = codes.filterNotNull().sortedDescending()
        if (valid.isEmpty()) return ""
        val head = valid.take(max).joinToString("、") { "#$it" }
        return if (valid.size > max) "$head 等 ${valid.size} 张" else head
    }
}
