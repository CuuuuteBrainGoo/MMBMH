package com.bro.lotteryledger.core

import com.bro.lotteryledger.db.TicketEntity

/**
 * 分彩种 / 分期号的统计报表（少爷 2026-09-27 第 5 条）。
 *
 * ## 为什么要单独一层
 *
 * 首页那张主卡只有一个「总账」。少爷想知道的下一层是：
 *  - **哪个彩种在亏、哪个在赚**（双色球 vs 大乐透，各自的投入与回报）
 *  - **哪一期买得多、哪一期中过**（按期号聚合，看单期投入产出）
 *
 * 这两件事的数据全部来自**已有的票**，不需要新表、不需要网络，
 * 也不该塞进 ViewModel 里做 —— 抽成纯函数才能单测（尤其金额口径，错一分钱都难看）。
 *
 * ## 金额口径：跟首页总账**必须一致**（§15）
 *
 * 这是最容易出错的地方，逐条钉死：
 *  - `purchase`（花出去）：**照算**。钱确实花出去了，不管中没中、兑没兑。
 *  - `prize`（中奖）：**排除 `EXPIRED_UNCLAIMED`** —— 过期票的 `prizeAmount`
 *    是保留着用来显示「作废了多少钱」的，那笔钱永远拿不到，
 *    算进中奖会让净收支凭空多一笔收入。
 *  - `expired`（过期未兑）：单独统计，界面上带负号、紫色显示。
 *  - `net`（净收支）= `prize - purchase`。
 *
 * 换句话说：**同一批票，报表里各彩种的 net 之和必须等于首页总 net**。
 * `LedgerReportsTest` 里有专门一条断言在守这个不变量。
 */
object LedgerReports {

    /** 一个聚合桶（彩种维度 / 期号维度共用结构）。 */
    data class Bucket(
        /** 桶标识：彩种维度下是彩种代码（`ssq`/`dlt`），期号维度下是期号。 */
        val key: String,
        /** 展示名：彩种维度下是「双色球」这类中文名，期号维度下就是期号本身。 */
        val label: String,
        /** 副标题：彩种维度下可空；期号维度下放开奖日期。 */
        val subtitle: String? = null,
        val ticketCount: Int,
        /** 花出去（元） */
        val purchase: Double,
        /** 中奖（元，**不含过期未兑**） */
        val prize: Double,
        /** 已兑到手（元） */
        val redeemed: Double,
        /** 过期未兑（元，白扔的，界面带负号） */
        val expired: Double,
        /** 净收支 = prize - purchase */
        val net: Double
    )

    /**
     * 按彩种聚合。**固定按双色球、大乐透的顺序输出**，某彩种没有票就跳过 ——
     * 顺序稳定是为了界面上不会「今天双色球在前、明天大乐透在前」地跳。
     */
    fun byLotteryType(tickets: List<TicketEntity>): List<Bucket> {
        val groups = tickets.groupBy { it.lotteryType }
        val ordered = mutableListOf<Bucket>()
        // 已知彩种按固定顺序；未知代码（以后加了新彩种）排在后面，按代码字典序
        val known = LotteryType.entries.map { it.code }
        val codes = known.filter { groups.containsKey(it) } +
            groups.keys.filter { it !in known }.sorted()
        for (code in codes) {
            val list = groups[code] ?: continue
            val type = LotteryType.from(code)
            ordered += sum(
                key = code,
                label = type?.display ?: code,
                subtitle = null,
                list = list
            )
        }
        return ordered
    }

    /**
     * 按期号聚合（同一彩种 + 同一期号算一桶）。
     *
     * 排序：**开奖日期倒序**（最近的在最上面），日期相同按彩种 + 期号稳定排。
     * 期号是字符串，直接字典序排会出错（"2026" 和 "2026100" 之类长度不一致时），
     * 所以这里一律用开奖日期当主排序键。
     */
    fun byIssue(tickets: List<TicketEntity>): List<Bucket> {
        return tickets
            .groupBy { IssueSeq.key(it.lotteryType, it.issue) }
            .map { (_, list) ->
                val first = list.first()
                val type = LotteryType.from(first.lotteryType)
                // 排序键直接取开奖日期（"2026-09-20" 这种 ISO 串字典序 = 时间序），
                // 不去从 subtitle 里反解析 —— 那种写法一旦哪天 subtitle 格式变了就静默排错
                val sortDate = DateTimes.normalizeDate(first.drawDate) ?: ""
                Triple(
                    sortDate,
                    IssueSeq.key(first.lotteryType, first.issue),
                    sum(
                        key = IssueSeq.key(first.lotteryType, first.issue),
                        label = first.issue,
                        subtitle = buildString {
                            type?.display?.let { append(it) }
                            if (first.drawDate.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(first.drawDate)
                            }
                        }.ifEmpty { null },
                        list = list
                    )
                )
            }
            // 开奖日期倒序（最近的在前）；同日按彩种+期号稳定排
            .sortedWith(
                compareByDescending<Triple<String, String, Bucket>> { it.first }
                    .thenBy { it.second }
            )
            .map { it.third }
    }

    /** 把一组票压成一个桶。金额口径见类注释。 */
    private fun sum(key: String, label: String, subtitle: String?, list: List<TicketEntity>): Bucket {
        var purchase = 0.0
        var prize = 0.0
        var redeemed = 0.0
        var expired = 0.0
        for (t in list) {
            purchase += t.purchaseAmount
            // 过期票的中奖金额**不计入中奖** —— 跟 §15 / stats() 口径一致
            if (t.ticketStatus != TicketStatus.EXPIRED_UNCLAIMED.name) {
                prize += t.prizeAmount ?: 0.0
            }
            redeemed += t.redeemedAmount ?: 0.0
            expired += t.expiredUnclaimedAmount ?: 0.0
        }
        return Bucket(
            key = key,
            label = label,
            subtitle = subtitle,
            ticketCount = list.size,
            purchase = purchase,
            prize = prize,
            redeemed = redeemed,
            expired = expired,
            net = prize - purchase
        )
    }
}
