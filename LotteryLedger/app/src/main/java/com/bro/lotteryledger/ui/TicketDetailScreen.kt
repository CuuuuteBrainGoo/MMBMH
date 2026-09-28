package com.bro.lotteryledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bro.lotteryledger.core.*
import com.bro.lotteryledger.db.TicketBetEntity
import com.bro.lotteryledger.repo.PrizeService

/**
 * 票详情页。
 *
 * 展示五块：**票面信息 / 兑奖期限 / 开奖结果 / 投注号码 / 票面编号**。
 *
 * 兑奖期限是**实时算的**，并且会把顺延的原因写出来
 * （「因国庆假期顺延 3 天」），否则用户按 60 天自己一算，会以为程序算错了。
 *
 * 这一页也是**两个状态的唯一出口**：
 *  - `PRIZE_PENDING`（一二等奖待确认）→ 输入并确认金额 → `TO_REDEEM`
 *  - `TO_REDEEM`（待兑奖）→ 标记已兑奖 → `REDEEMED`
 * 之前这两条路都没有入口，票会卡在中间状态出不去。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketDetailScreen(
    detail: MainViewModel.TicketDetail,
    checking: Boolean,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onConfirmAmount: (Double) -> Unit,
    onRedeem: (Double?) -> Unit,
    /** 手动标记未中奖（给「查不到开奖结果」的票兜底） */
    onMarkNotWon: () -> Unit,
    /** 手动记为中奖并填金额（同上） */
    onMarkWon: (Double) -> Unit
) {
    val t = detail.ticket
    val type = LotteryType.from(t.lotteryType)
    // 展示修正（见 DrawSchedule.effectiveStatus）：开奖日当天还没到 22:00 的票，
    // 库里可能是 AWAITING_RESULT，这里修正回「未开奖」。纯计算，不写库。
    val status = DrawSchedule.effectiveStatus(TicketStatus.from(t.ticketStatus), t.drawDate)
    val accent = when (type) {
        LotteryType.SSQ -> LedgerColors.BallRed
        LotteryType.DLT -> LedgerColors.BallBlue
        else -> MaterialTheme.colorScheme.outline
    }

    // 「标记已兑奖」的二次确认。兑奖是**不可逆**的状态变更（会进已兑金额统计），
    // 误点一下就得手工改数据库，所以必须问一句。
    var redeemDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("票详情")
                        // 顶部就亮出编号（少爷 2026-09-27 要求）——
                        // 从列表点进来第一眼要能确认「是不是我要找的那张」。
                        detail.code?.let {
                            Spacer(Modifier.width(8.dp))
                            DetailCodeChip(it)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 头部：彩种 + 期号 + 状态 ----
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
                    Column(Modifier.padding(16.dp).weight(1f)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${type?.display ?: t.lotteryType} · ${t.issue} 期",
                                style = MaterialTheme.typography.titleLarge
                            )
                            StatusChipLarge(status)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            yuan(t.purchaseAmount),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            // 倍数无条件显示，跟首页统一（少爷 2026-09-27 第 3 条）
                            "${t.effectiveUnits} 注 × ${t.multiple} 倍" +
                                (if (t.additional) " · 追加" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (t.prizeAmount != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "中奖 ${yuan(t.prizeAmount)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = LedgerColors.Prize
                            )
                        }
                    }
                }
            }

            // ---- 票面信息 ----
            Section("票面信息") {
                // 展示编号放第一条 —— 这是用户报给开发者、或在列表里定位这张票用的
                // 「门牌号」，跟下面那些票面印刷的编号不是一回事。
                detail.code?.let { InfoRow("展示编号", "#$it") }
                InfoRow("购彩时间", t.purchaseTime ?: "票面未印")
                InfoRow("开奖日期", t.drawDate)
                // ⚠️ 不能只写「复式」—— 组合票（单式 + 复式混合）会被说成整张都是复式。
                // 用每注的 units（库里已经存了）还原构成，见 BetForm.fromPerBetUnits。
                InfoRow(
                    "投注方式",
                    if (detail.bets.isNotEmpty()) {
                        BetForm.fromPerBetUnits(detail.bets.map { it.units }).label()
                    } else if (t.betType == "MULTIPLE") {
                        "复式"
                    } else {
                        "单式"
                    }
                )
                InfoRow("录入方式", InputMethod.from(t.inputMethod).display)
            }

            // ---- 兑奖期限 ----
            Section("兑奖期限") {
                val d = detail.deadline
                if (d == null) {
                    Text(
                        "开奖日期读不出来，算不了兑奖截止日",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    InfoRow("开奖日", RedemptionDeadline.format(d.drawDate), bold = false)
                    InfoRow(
                        "兑奖截止",
                        RedemptionDeadline.format(d.deadline),
                        bold = true,
                        valueColor = if (d.expired) MaterialTheme.colorScheme.error else LedgerColors.Warn
                    )
                    Text(
                        d.remainingText(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (d.expired) MaterialTheme.colorScheme.error else LedgerColors.Prize
                    )
                    if (d.extended) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已按${d.extensionName}顺延 ${d.extendedDays} 天" +
                                "（不顺延的话是 ${RedemptionDeadline.format(d.rawDeadline)}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = LedgerColors.Warn
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "按开奖日 + 60 天计算。国庆头 3 天、春节头 7 天内到期的会自动顺延，其它时间不顺延。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- 开奖结果 ----
            Section("开奖结果") {
                val draw = detail.draw
                if (draw == null) {
                    Text(
                        when (status) {
                            // 开奖日**当天**（还没到 22:00）也是 PENDING_DRAW，
                            // 但说「还没到开奖日」会让用户困惑 —— 票面明明写着今天开奖。
                            TicketStatus.PENDING_DRAW ->
                                if (t.drawDate == java.time.LocalDate.now().toString()) {
                                    "今天开奖，还没到开奖时间"
                                } else {
                                    "还没到开奖日"
                                }
                            TicketStatus.AWAITING_RESULT -> "已到开奖日，官方还没公布结果"
                            TicketStatus.RESULT_UNAVAILABLE ->
                                "官方接口里没有这一期的记录 —— 往下看，需要你手动处理"
                            else -> "还没查到这一期的开奖号码"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status == TicketStatus.RESULT_UNAVAILABLE) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                } else {
                    Text(
                        "第 ${draw.issue} 期 · ${draw.drawDate} 开奖",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        draw.main.forEach { Ball(it, LedgerColors.BallRed) }
                        Spacer(Modifier.width(6.dp))
                        draw.second.forEach { Ball(it, LedgerColors.BallBlue) }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                when (status) {
                    TicketStatus.NOT_WON ->
                        ResultLine("未中奖", MaterialTheme.colorScheme.onSurfaceVariant)

                    // ---- 查不到这一期：人工出口（2026-09-27 新增） ----
                    //
                    // 少爷扫了 3 张 4 月份的双色球，卡在「等待开奖结果」出不来。
                    // 根因是查询范围只有 50 期（约 3.8 个月），4 月的票根本不在里面。
                    // 范围已扩大到 200 期；这个状态是**范围也覆盖不到**时的兜底 ——
                    // 不能再让票永远悬着，必须给用户手动了结的入口。
                    TicketStatus.RESULT_UNAVAILABLE -> {
                        ResultLine("查不到这一期的开奖结果", MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "官方接口里没有第 ${t.issue} 期的记录（票面开奖日 ${t.drawDate}）。" +
                                "一般是因为期号或开奖日期被读错了。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "你要是已经在官方渠道核对过这一期的结果，直接手动记一笔就行 ——" +
                                "不用等它自己变。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = onMarkNotWon,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("我核对过了，没中奖") }

                        Spacer(Modifier.height(8.dp))
                        var manualAmount by remember(t.id) { mutableStateOf("") }
                        val manualParsed = manualAmount.trim().toDoubleOrNull()
                        OutlinedTextField(
                            value = manualAmount,
                            onValueChange = { s ->
                                manualAmount = s.filter { it.isDigit() || it == '.' }
                            },
                            label = { Text("我核对过了，中了（填金额）") },
                            placeholder = { Text("例：200") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = { manualParsed?.let(onMarkWon) },
                            enabled = manualParsed != null && manualParsed > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("按这个金额记为中奖") }
                    }

                    // ---- 待兑奖：出口是「标记已兑奖」 ----
                    TicketStatus.TO_REDEEM -> {
                        ResultLine("已中奖", LedgerColors.Prize)
                        t.prizeAmount?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                yuan(it),
                                style = MaterialTheme.typography.headlineMedium,
                                color = LedgerColors.Prize
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "拿票去体彩/福彩网点兑奖。兑完点下面的按钮记一笔，" +
                                "已兑金额会单独统计，和「应得」分开。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { redeemDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("标记已兑奖") }
                    }

                    // ---- 一二等奖待确认：出口是「确认金额」 ----
                    TicketStatus.PRIZE_PENDING -> {
                        ResultLine("中了一、二等奖", LedgerColors.Warn)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "一、二等奖是浮动奖，官方公布的是单注金额。" +
                                "下面是按「单注金额 × 中奖注数」算的，「确认后才会算进盈亏」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))

                        // 预填官方公布值，用户核对公告后确认或改成税后金额
                        var amountText by remember(t.id, t.prizeAmount) {
                            mutableStateOf(t.prizeAmount?.let { "%.2f".format(it) } ?: "")
                        }
                        val parsed = amountText.trim().toDoubleOrNull()
                        val bad = amountText.isNotBlank() && (parsed == null || parsed <= 0)

                        OutlinedTextField(
                            value = amountText,
                            onValueChange = { s ->
                                amountText = s.filter { it.isDigit() || it == '.' }
                            },
                            label = { Text("中奖金额（元）") },
                            placeholder = { Text("例：100000") },
                            supportingText = {
                                Text(
                                    when {
                                        bad -> "请填一个大于 0 的金额"
                                        parsed != null -> "确认后按这个数计入盈亏"
                                        else -> "可改成实际到手的金额"
                                    }
                                )
                            },
                            isError = bad,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { parsed?.let(onConfirmAmount) },
                            enabled = parsed != null && parsed > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("确认金额") }
                    }

                    TicketStatus.REDEEMED -> {
                        ResultLine("已兑奖", LedgerColors.Spend)
                        t.redeemedAmount?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                yuan(it),
                                style = MaterialTheme.typography.headlineMedium,
                                color = LedgerColors.Spend
                            )
                        }
                        t.redeemedAt?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "兑奖时间 ${dateOf(it)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    TicketStatus.EXPIRED_UNCLAIMED -> {
                        // 少爷 2026-09-27 指定：过期未兑用**紫色 + 负号**。
                        // 负号是关键 —— 这笔钱没拿到，不是收入。
                        ResultLine("已过期未兑奖", LedgerColors.Expired)
                        t.expiredUnclaimedAmount?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "−${yuan(it)}",
                                style = MaterialTheme.typography.headlineMedium,
                                color = LedgerColors.Expired
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "这笔奖金没能领到，只计入「过期未兑账」——" +
                                    "不参与中奖金额和净收支的计算。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> Text(
                        "核验之后这里会显示中奖情况",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 已兑奖 / 已过期的票再核验没有意义，按钮直接不显示
                if (status != TicketStatus.REDEEMED && status != TicketStatus.EXPIRED_UNCLAIMED) {
                    // 还没到开奖时间就别让点 —— 官方也还没出结果，白跑一趟网络
                    val drawTimePassed = PrizeService.isDrawTimePassed(t.drawDate)

                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onCheck,
                        enabled = drawTimePassed && !checking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("核验中…")
                        } else {
                            Text("核验这一期开奖")
                        }
                    }
                    if (!drawTimePassed) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "开奖日 ${t.drawDate}，当天开奖时间之后才能核验",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- 投注号码 ----
            if (detail.bets.isNotEmpty()) {
                Section("投注号码") {
                    detail.bets.sortedBy { it.betIndex }.forEach { b ->
                        BetRow(b, type)
                    }
                }
            }

            // ---- 票面编号 ----
            val ids = listOf(
                "验票码" to t.verificationCode,
                "票号" to t.ticketNumber,
                "流水号" to t.serialNumber,
                "终端号" to t.terminalNumber,
                "网点号" to t.stationNumber,
                "条码" to t.barcodeRaw
            ).filter { !it.second.isNullOrBlank() }

            if (ids.isNotEmpty()) {
                Section("票面编号（用于防重复录入）") {
                    ids.forEach { InfoRow(it.first, it.second!!) }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (redeemDialog) {
        RedeemDialog(
            initial = t.prizeAmount,
            onConfirm = { amt ->
                redeemDialog = false
                onRedeem(amt)
            },
            onDismiss = { redeemDialog = false }
        )
    }
}

/**
 * 「标记已兑奖」的确认框。
 *
 * 金额预填「应得」值，允许改 —— 大额奖金有税，实际到手会少于应得，
 * 直接锁死应得值会让统计失真。
 */
@Composable
private fun RedeemDialog(
    initial: Double?,
    onConfirm: (Double?) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial?.let { "%.2f".format(it) } ?: "") }
    val parsed = text.trim().toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认已兑奖？") },
        text = {
            Column {
                Text("确认后这张票会记入「已兑金额」，并完成本次中奖流程。")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { s -> text = s.filter { it.isDigit() || it == '.' } },
                    label = { Text("实际到手（元）") },
                    supportingText = { Text("有税的话按实际到手的填") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(parsed) },
                enabled = parsed != null && parsed > 0
            ) { Text("确认已兑") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 时间戳 → `yyyy-MM-dd`。详情页只到日粒度就够，不用引 core 层的私有工具。 */
private fun dateOf(ts: Long): String {
    val c = java.util.Calendar.getInstance().apply { time = java.util.Date(ts) }
    return "%04d-%02d-%02d".format(
        c.get(java.util.Calendar.YEAR),
        c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.DAY_OF_MONTH)
    )
}

@Composable
private fun ResultLine(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun DetailCodeChip(code: Int) {
    Surface(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            "#$code",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    bold: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = valueColor
        )
    }
}

/**
 * 一注号码。
 *
 * 号码画成圆球：红球/前区用红、蓝球/后区用蓝 —— 跟真实彩票的配色一致，
 * 一眼就能看出哪个是哪个区。复式注会额外标「N 注」。
 */
@Composable
private fun BetRow(b: TicketBetEntity, type: LotteryType?) {
    val mainColor = when (type) {
        LotteryType.SSQ -> LedgerColors.BallRed
        LotteryType.DLT -> LedgerColors.BallRed
        else -> LedgerColors.BallRed
    }
    val secondColor = LedgerColors.BallBlue

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "第 ${b.betIndex} 注",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (b.units > 1) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "复式 ${b.units} 注",
                    style = MaterialTheme.typography.labelSmall,
                    color = LedgerColors.Gold
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            b.mainNumbers.split(",").filter { it.isNotBlank() }.forEach { Ball(it.trim(), mainColor) }
            Spacer(Modifier.width(6.dp))
            b.secondNumbers.split(",").filter { it.isNotBlank() }.forEach { Ball(it.trim(), secondColor) }
        }
    }
}

@Composable
private fun Ball(text: String, color: Color) {
    Box(
        Modifier.size(28.dp).background(color, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun StatusChipLarge(status: TicketStatus) {
    // 配色统一在 statusColor 里（以前首页和详情页各写一份，加状态时必漏一处）
    val color = statusColor(status)
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.15f)) {
        Text(
            status.display,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = color
        )
    }
}
