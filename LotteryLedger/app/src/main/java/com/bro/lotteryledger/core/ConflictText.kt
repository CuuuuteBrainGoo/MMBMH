package com.bro.lotteryledger.core

/**
 * 把冲突字段的**内部路径**翻译成人话。
 *
 * ## 为什么需要这一层
 *
 * 少爷 2026-09-27 反馈：核对页弹出「以下字段需要你确认：bets」，
 * 完全不知道 `bets` 是什么，也不知道要确认什么。
 *
 * 根因是 UI 直接渲染了内部路径。这些路径是给程序比对用的
 * （`bets[2].main` 这种），对用户没有任何意义。
 *
 * 这一层只做翻译，不改变任何判定逻辑 —— 判定归判定，展示归展示。
 */
object ConflictText {

    /**
     * 路径 → 人话名称。
     *
     * @param type 彩种。用来把 `main` / `second` 说成「红球/蓝球」还是「前区/后区」
     *             —— 同一个内部字段名，两种彩票的叫法完全不同。
     */
    fun label(path: String, type: LotteryType? = null): String {
        // 1) 精确匹配的固定字段
        FIXED[path]?.let { return it }

        // 2) 带下标的路径：bets[0].main / bet[1].second / bets[2].groups
        BET_INDEXED.matchEntire(path)?.let { m ->
            val idx = m.groupValues[1].toIntOrNull()?.plus(1) ?: return@let
            val field = m.groupValues[2]
            return "第 $idx 注 ${groupName(field, type)}"
        }

        // 3) 号码组下标：bets[0].groups[0].name / bets[0].groups[0].numbers
        GROUP_INDEXED.matchEntire(path)?.let { m ->
            val bi = m.groupValues[1].toIntOrNull()?.plus(1) ?: return@let
            val gi = m.groupValues[2].toIntOrNull()?.plus(1) ?: return@let
            val field = m.groupValues[3]
            val what = if (field == "name") "号码组名称" else "号码"
            return "第 $bi 注 第 $gi 组 $what"
        }

        // 4) 票面编号：ticket_identifiers.verification_code
        if (path.startsWith("ticket_identifiers.")) {
            val k = path.removePrefix("ticket_identifiers.")
            return "票面编号 · " + (IDENTIFIERS[k] ?: k)
        }

        // 5) 认不出来就原样返回 —— 好过显示空白。
        //    将来加了新字段而忘了补翻译，用户至少还能看到一个标识。
        return path
    }

    /**
     * 主/副号码组在两种彩票里的叫法不同。
     *
     * ⚠️ **两套字段名都要认**（2026-09-27 少爷问大乐透多注票时发现的漏洞）：
     *
     * | 产出方 | 路径长什么样 |
     * |---|---|
     * | `TicketDiffer`（两份识别结果比对） | `bets[0].front` —— 用的是**号码组 id** |
     * | `BetRules`（本地校验） | `bet[0].front` —— 同样用 id |
     *
     * 原来这里只认 `main` / `second`，于是 `front` / `back` / `red` / `blue`
     * 一路落到 `else` 原样返回 —— **用户会看到「第 1 注 front」这种英文**。
     * 多发于多注票（每一注都要逐个号码组比），也就是少爷问的这个场景。
     *
     * 号码组 id 本身就区分了彩种（red/blue 只属于双色球，front/back 只属于大乐透），
     * 所以这里直接用 [GroupName.display]，比起从 [type] 反推更准。
     */
    private fun groupName(field: String, type: LotteryType?): String = when (field) {
        "main" -> when (type) {
            LotteryType.SSQ -> "红球"
            LotteryType.DLT -> "前区"
            else -> "主号码"
        }
        "second" -> when (type) {
            LotteryType.SSQ -> "蓝球"
            LotteryType.DLT -> "后区"
            else -> "副号码"
        }
        "groups" -> "号码组"
        else -> GroupName.from(field)?.display ?: when (field) {
            "numbers" -> "号码"
            "candidates" -> "候选号码"
            else -> field
        }
    }

    private val FIXED = mapOf(
        "$" to "识别结果（整体）",
        "ticket" to "识别结果（整体）",
        "lottery_type" to "彩种",
        "issue" to "期号",
        "purchase_time" to "购彩时间",
        "draw_date" to "开奖日期",
        "bet_type" to "投注方式",
        "multiple" to "倍数",
        "additional" to "是否追加",
        "amount_yuan" to "票面金额（元）",
        // 「bets」是用户最常看到、也最困惑的一个。
        // 它出现有两种含义：注数不一致（两模型对「这张票有几注」判断不同），
        // 或者根本没读出投注内容。所以说法要能同时覆盖这两层。
        "bets" to "注数 / 投注内容"
    )

    private val IDENTIFIERS = mapOf(
        "verification_code" to "验票码",
        "ticket_number" to "票号",
        "serial_number" to "流水号",
        "terminal_number" to "终端号",
        "station_number" to "站点号",
        "barcode_raw" to "条码内容"
    )

    // 两种前缀都要认：
    //  - `bets[0].main`  ← TicketDiffer 比对两份识别结果时产出
    //  - `bet[0].main`   ← BetRules 本地校验时产出（少了那个 s）
    // 以前只认前者，导致校验层的冲突项显示成原始路径，同样看不懂。
    private val BET_INDEXED = Regex("""^(?:bets|bet)\[(\d+)]\.(\w+)$""")
    private val GROUP_INDEXED = Regex("""^(?:bets|bet)\[(\d+)]\.groups\[(\d+)]\.(\w+)$""")
}
