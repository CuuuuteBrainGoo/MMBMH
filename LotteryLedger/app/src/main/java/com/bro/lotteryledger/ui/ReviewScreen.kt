package com.bro.lotteryledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bro.lotteryledger.core.*

/**
 * 核对与修改界面（§5 / §2.2 步骤 8）。
 *
 * 设计原则：
 *  - 所有问题按「必须修正（ERROR）」和「建议确认（WARNING）」分开显示，不混在一起吓人。
 *  - 号码用数字选号面板改，不用键盘。选号面板的候选池按彩种规则生成。
 *  - **所有字段都可改** —— 期号、开奖日期、购彩时间、号码、倍数、金额、票面编号。
 *    识别读错任何一项都必须能人工修正，否则这张票就废了。
 *  - 这一页同时承担**手工录入**（`InputMethod.MANUAL`）：从空白票开始填，
 *    复式和倍投都走这里，不另做一套界面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    entry: MainViewModel.PendingEntry,
    onChange: ((RawTicket) -> RawTicket) -> Unit,
    onConfirmDistinct: () -> Unit,
    onCommit: () -> Unit,
    onDiscard: () -> Unit,
    /** 顶栏返回键 / 系统返回键都走这里（统一二次确认） */
    onBack: () -> Unit = onDiscard
) {
    val ticket = entry.ticket
    val spec = ticket.lotteryType?.let { LotterySpecs.of(it) }
    val isManual = entry.inputMethod == InputMethod.MANUAL

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isManual) "手工录入" else "核对识别结果") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = "放弃")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.padding(12.dp)) {
                    // 重复提示（§6.4）
                    when (entry.duplicate.status) {
                        DuplicateStatus.EXACT_DUPLICATE -> {
                            BlockedBanner(entry.duplicate.reason)
                        }
                        DuplicateStatus.POSSIBLE_DUPLICATE -> {
                            PossibleDupBanner(
                                reason = entry.duplicate.reason,
                                onDistinct = onConfirmDistinct
                            )
                        }
                        else -> {}
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDiscard,
                            modifier = Modifier.weight(1f)
                        ) { Text("放弃") }

                        Button(
                            onClick = onCommit,
                            enabled = !entry.validation.hasError &&
                                entry.duplicate.status != DuplicateStatus.EXACT_DUPLICATE,
                            modifier = Modifier.weight(2f)
                        ) {
                            Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("确认入账")
                        }
                    }
                }
            }
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
            // --- 手工录入的引导 ---
            if (isManual) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "照票面逐项填写",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "号码用下面的选号盘点选。选得比单式需要多就是复式，注数会自动算给你。\n" +
                                "票面编号能填就填 —— 那是防止同一张票被录两遍的主要依据。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // --- 问题清单 ---
            if (entry.validation.hasError) {
                IssueCard(
                    title = "必须修正",
                    color = MaterialTheme.colorScheme.error,
                    issues = entry.validation.errors
                )
            }
            if (entry.validation.hasWarning) {
                IssueCard(
                    title = "建议确认",
                    color = LedgerColors.Warn,
                    issues = entry.validation.warnings
                )
            }
            // 「需要你确认」这张卡片里，**去掉上面两张卡片已经列过的字段**。
            //
            // 少爷 2026-09-27 的截图里「投注方式」上下各出现了一次：
            // collectConflicts 会把本地校验的问题字段一起收进 conflicts，
            // 而校验的问题又已经由「必须修正 / 建议确认」两张卡展示了。
            // 同一件事说两遍，用户会以为有两个问题要处理。
            val alreadyListed = entry.validation.issues.map { it.field }.toSet()
            val modelConflicts = entry.conflicts.filterNot { it in alreadyListed }
            if (modelConflicts.isNotEmpty()) {
                ConflictCard(
                    conflicts = modelConflicts,
                    values = entry.conflictValues,
                    type = ticket.lotteryType,
                    reviewed = entry.recognizedStatus == RecognitionStatus.DOUBLE_VERIFIED
                )
            }

            // --- 票面基本信息 ---
            if (spec != null) {
                SectionCard("票面信息") {
                    LotteryTypePicker(
                        current = ticket.lotteryType,
                        onChange = { t ->
                            onChange {
                                // 换彩种必须把号码组整体换掉：红球(1-33)/蓝球(1-16) 与
                                // 前区(1-35)/后区(1-12) 完全不通用，残留旧组会让号码串味。
                                // 注数保留（用户加了几注就还是几注），号码清空。
                                it.copy(
                                    lotteryType = t,
                                    additional = false,
                                    bets = Bets.reindex(it.bets.map { b -> Bets.blank(t, b.index) })
                                )
                            }
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "期号和开奖日期是对奖的必需信息，务必和票面一致。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))

                    EditableFieldRow(
                        label = "期号",
                        value = ticket.issue,
                        placeholder = "票面印的期号，如 2026111",
                        keyboardType = KeyboardType.Number,
                        transform = { raw -> raw.filter { it.isDigit() }.take(7) },
                        onChange = { v -> onChange { it.copy(issue = v) } }
                    )
                    EditableFieldRow(
                        label = "开奖日期",
                        value = ticket.drawDate,
                        placeholder = "敲 8 位数字自动补横线，如 20260926",
                        keyboardType = KeyboardType.Number,
                        transform = { raw -> autoDashDate(raw) },
                        onChange = { v -> onChange { it.copy(drawDate = v) } }
                    )
                    EditableFieldRow(
                        label = "购彩时间",
                        value = ticket.purchaseTime,
                        placeholder = "票面印的时间，选填",
                        onChange = { v -> onChange { it.copy(purchaseTime = v) } }
                    )
                }
            }

            // --- 投注内容 ---
            if (spec != null) {
                ticket.bets.forEachIndexed { betIdx, bet ->
                    // 只剩一注时不给删除按钮 —— 一张票至少要有一注
                    val deleteAction: (@Composable () -> Unit)? = if (ticket.bets.size > 1) {
                        {
                            IconButton(
                                onClick = {
                                    onChange { t ->
                                        t.copy(
                                            bets = Bets.reindex(
                                                t.bets.filterIndexed { i, _ -> i != betIdx }
                                            )
                                        )
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除第 ${bet.index} 注",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    } else null

                    SectionCard(title = "第 ${bet.index} 注", trailing = deleteAction) {
                        BetEditor(
                            bet = bet,
                            spec = spec,
                            onBetChange = { newBet ->
                                onChange { t ->
                                    t.copy(
                                        bets = t.bets.toMutableList().also { it[betIdx] = newBet }
                                    )
                                }
                            }
                        )
                    }
                }

                // 手工录入时起点只有 1 注，买了几注就得加几次。
                // 也要能加：一张 5 注的票不该逼用户重拍。
                OutlinedButton(
                    onClick = {
                        onChange { t ->
                            t.copy(
                                bets = Bets.reindex(
                                    t.bets + Bets.blank(spec.type, t.bets.size + 1)
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加一注")
                }
            }

            // --- 倍数 / 金额 ---
            if (spec != null) {
                SectionCard("投注参数") {
                    MultipleEditor(
                        multiple = ticket.multiple,
                        additional = ticket.additional,
                        showAdditional = ticket.lotteryType == LotteryType.DLT,
                        onChange = { m, add -> onChange { it.copy(multiple = m, additional = add) } }
                    )
                    Spacer(Modifier.height(10.dp))
                    val units = entry.validation.effectiveBetCount
                    val expected = ticket.lotteryType?.let {
                        BetRules.expectedAmount(it, units, ticket.multiple, ticket.additional)
                    } ?: 0.0
                    // 投注构成是自动判定的（某一注号码超量就是复式），这里只是把结论显示出来。
                    //
                    // ⚠️ 不能拿 `betType` 直接渲染成「单式 / 复式」两个字：
                    // **组合票**（比如 3 注单式 + 1 注复式）会被说成「复式」，
                    // 用户看不出它是混合的、也没法核对那 3 注单式。
                    // 少爷 2026-09-27 指出大乐透存在这种票面，所以改成分注统计。
                    val form = ticket.lotteryType?.let { BetForm.of(it, ticket.bets) }
                    Text(
                        (form?.labelCompact()
                            ?: (if (ticket.betType == BetType.MULTIPLE) "复式" else "单式")) +
                            " · 有效注数 $units × ${ticket.multiple} 倍" +
                            (if (ticket.additional) " + 追加" else "") +
                            " → 应为 ${fmtYuan(expected)} 元",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    AmountEditor(
                        amount = ticket.amountYuan,
                        onChange = { a -> onChange { it.copy(amountYuan = a) } }
                    )
                }
            }

            // --- 票面编号（防重复的关键，§6.3）---
            SectionCard("票面编号（用于防止重复入账）") {
                Text(
                    "这些编号是判断「是不是同一张实体票」的主要依据。能填就填，越全越安全。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                IdEditor("验票码", ticket.identifiers.verificationCode) { v ->
                    onChange { it.copy(identifiers = it.identifiers.copy(verificationCode = v)) }
                }
                IdEditor("票号", ticket.identifiers.ticketNumber) { v ->
                    onChange { it.copy(identifiers = it.identifiers.copy(ticketNumber = v)) }
                }
                IdEditor("流水号", ticket.identifiers.serialNumber) { v ->
                    onChange { it.copy(identifiers = it.identifiers.copy(serialNumber = v)) }
                }
                IdEditor("终端号", ticket.identifiers.terminalNumber) { v ->
                    onChange { it.copy(identifiers = it.identifiers.copy(terminalNumber = v)) }
                }
                IdEditor("销售站号", ticket.identifiers.stationNumber) { v ->
                    onChange { it.copy(identifiers = it.identifiers.copy(stationNumber = v)) }
                }
                IdEditor("条码值", ticket.identifiers.barcodeRaw) { v ->
                    onChange { it.copy(identifiers = it.identifiers.copy(barcodeRaw = v)) }
                }

                val identity = Fingerprints.identityFingerprint(ticket)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (identity == null)
                        "⚠️ 目前信息不足，无法生成身份指纹。这张票只能靠投注内容提示重复，安全性较低。"
                    else "身份指纹等级：${levelName(identity.level)}（${identity.reason}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (identity == null) LedgerColors.Warn
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun levelName(l: Fingerprints.Level) = when (l) {
    Fingerprints.Level.STRONG -> "强"
    Fingerprints.Level.COMPOSITE -> "中"
    Fingerprints.Level.WEAK -> "弱"
}

private fun fmtYuan(d: Double): String =
    if (d == kotlin.math.floor(d)) d.toLong().toString() else String.format("%.2f", d)

// ------------------------------------------------------------------

@Composable
private fun BlockedBanner(reason: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("这张票已经入账过了", fontWeight = FontWeight.SemiBold)
                Text(reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PossibleDupBanner(reason: String, onDistinct: () -> Unit) {
    Surface(
        color = LedgerColors.Warn.copy(alpha = 0.14f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = LedgerColors.Warn)
                Spacer(Modifier.width(8.dp))
                Text("可能重复", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(reason, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDistinct, modifier = Modifier.weight(1f)) {
                    Text("这是另一张票，继续入账")
                }
            }
        }
    }
}

@Composable
private fun IssueCard(title: String, color: Color, issues: List<RuleIssue>) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("$title（${issues.size}）", fontWeight = FontWeight.SemiBold, color = color)
            Spacer(Modifier.height(6.dp))
            issues.forEach {
                Text("• ${it.message}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ConflictCard(
    conflicts: List<String>,
    values: Map<String, Pair<String?, String?>>,
    type: LotteryType?,
    reviewed: Boolean
) {
    Surface(
        color = LedgerColors.Warn.copy(alpha = 0.10f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (reviewed) "两个模型读出来的结果不一致，请对着票面确认" else "以下几项需要你确认",
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            conflicts.take(12).forEach { path ->
                val pair = values[path]
                Column(Modifier.padding(vertical = 3.dp)) {
                    // 显示人话名称，不显示 `bets[2].main` 这种内部路径 ——
                    // 少爷 2026-09-27 反馈「bets 是什么字段」，根因就是这里直接渲染了路径。
                    Text(
                        "• " + com.bro.lotteryledger.core.ConflictText.label(path, type),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (pair != null) {
                        // 有对比值就把它摆出来 —— 用户需要的是「谁跟谁不一致」，
                        // 而不只是「有个字段要确认」。
                        Text(
                            "    主识别：${pair.first ?: "—"}　复核：${pair.second ?: "—"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (conflicts.size > 12) {
                Text("…还有 ${conflicts.size - 12} 项", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun LotteryTypePicker(current: LotteryType?, onChange: (LotteryType) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LotteryType.entries.forEach { t ->
            FilterChip(
                selected = current == t,
                onClick = { onChange(t) },
                label = { Text(t.display) }
            )
        }
    }
}

/**
 * 可编辑的字段行。
 *
 * ⚠️ 这里原来是只读的 `FieldRow`，而且它的 `onEdit` 参数**从来没被调用过**
 * （调用处传的是 `onChange { it.copy(issue = it.issue) }` 这种空实现）。
 * 等于期号、开奖日期根本改不了 —— AI 读错这两项，这张票就只能放弃重拍。
 * 而这两项恰恰是**对奖的必需信息**，读错就等于错到底。
 */
@Composable
private fun EditableFieldRow(
    label: String,
    value: String?,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** 逐字符过滤 / 自动成型 */
    transform: (String) -> String = { it },
    onChange: (String?) -> Unit
) {
    var text by remember(value) { mutableStateOf(value ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val t = transform(raw)
            text = t
            onChange(t.trim().ifEmpty { null })
        },
        label = { Text(label) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    )
}

/**
 * 日期「边打边成型」：连着敲 8 位数字自动变成 `yyyy-MM-dd`。
 *
 * 手机上敲横线要切键盘，很烦，所以自动补。
 * 已经带横线的**不再重复加工**，这样用户想改中间某一位也改得动
 * （否则每次输入都被重新格式化，光标会乱跳）。
 */
private fun autoDashDate(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    val hasDash = raw.contains('-')
    if (!hasDash && digits.length == 8) {
        return "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
    }
    return raw.filter { it.isDigit() || it == '-' }.take(10)
}

/**
 * 单注编辑：数字选号面板（§5 明确要求，不用纯文本键盘）。
 *
 * 交互：点击池里的号码切换选中；点击已选号码取消。
 * 复式直接多选即可，不需要"添加一注"这类操作。
 */
@Composable
private fun BetEditor(bet: Bet, spec: LotterySpec, onBetChange: (Bet) -> Unit) {
    Column {
        // 主号码组
        GroupPicker(
            label = "${spec.groupName.display}（选 ${spec.groupPickSingle} 个为单式，多选为复式）",
            group = spec.groupName,
            min = spec.groupMin,
            max = spec.groupMax,
            maxPick = spec.groupPickMax,
            current = bet.group(spec.groupName)?.numbers ?: emptyList(),
            candidates = bet.group(spec.groupName)?.candidates ?: emptyList(),
            onChange = { nums ->
                onBetChange(replaceGroup(bet, spec.groupName, nums, emptyList()))
            }
        )
        Spacer(Modifier.height(10.dp))
        GroupPicker(
            label = "${spec.secondName.display}（选 ${spec.secondPickSingle} 个）",
            group = spec.secondName,
            min = spec.secondMin,
            max = spec.secondMax,
            maxPick = spec.secondPickMax,
            current = bet.group(spec.secondName)?.numbers ?: emptyList(),
            candidates = bet.group(spec.secondName)?.candidates ?: emptyList(),
            onChange = { nums ->
                onBetChange(replaceGroup(bet, spec.secondName, nums, emptyList()))
            }
        )
    }
}

private fun replaceGroup(bet: Bet, name: GroupName, numbers: List<String>, candidates: List<List<String>>): Bet {
    val groups = bet.groups.toMutableList()
    val idx = groups.indexOfFirst { it.name == name }
    val ng = NumberGroup(name, numbers, candidates)
    if (idx >= 0) groups[idx] = ng else groups.add(ng)
    return bet.copy(groups = groups)
}

@Composable
private fun GroupPicker(
    label: String,
    group: GroupName,
    min: Int,
    max: Int,
    maxPick: Int,
    current: List<String>,
    candidates: List<List<String>>,
    onChange: (List<String>) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))

        // 已选号码
        if (current.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                current.forEach { n ->
                    NumberBall(
                        n, group, size = 32,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 候选提示
        if (candidates.isNotEmpty()) {
            Text(
                "模型给出多个候选，请点选正确的：",
                style = MaterialTheme.typography.bodySmall, color = LedgerColors.Warn
            )
            Spacer(Modifier.height(4.dp))
            candidates.forEach { group2 ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    group2.forEach { n ->
                        CandidateBall(n, group, size = 32)
                    }
                }
                Spacer(Modifier.height(3.dp))
            }
            Spacer(Modifier.height(6.dp))
        }

        // 号码池
        NumberPad(
            min = min, max = max, group = group,
            selected = current.toSet(),
            maxPick = maxPick,
            onToggle = { n ->
                val set = current.toMutableList()
                if (set.contains(n)) set.remove(n)
                else if (set.size < maxPick) set.add(n)
                onChange(set.sorted())
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NumberPad(
    min: Int,
    max: Int,
    group: GroupName,
    selected: Set<String>,
    maxPick: Int,
    onToggle: (String) -> Unit
) {
    val numbers = (min..max).map { "%02d".format(it) }
    // 简单流式布局：按宽度自动换行
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        numbers.forEach { n ->
            val isSel = selected.contains(n)
            val atLimit = selected.size >= maxPick && !isSel
            NumberChip(
                number = n,
                group = group,
                selected = isSel,
                enabled = !atLimit,
                onClick = { onToggle(n) }
            )
        }
    }
}

@Composable
private fun NumberChip(
    number: String,
    group: GroupName,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val base = when (group) {
        GroupName.RED, GroupName.FRONT -> LedgerColors.BallRed
        GroupName.BLUE, GroupName.BACK -> LedgerColors.BallBlue
    }
    val bg = when {
        selected -> base
        else -> MaterialTheme.colorScheme.surface
    }
    val fg = when {
        selected -> Color.White
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg)
            .then(
                if (selected) Modifier
                else Modifier.borderCompat(base.copy(alpha = if (enabled) 0.5f else 0.15f))
            )
            .clickable(enabled = enabled || selected, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(number, color = fg, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun Modifier.borderCompat(color: Color): Modifier =
    this.then(border(1.dp, color, CircleShape))

@Composable
private fun MultipleEditor(
    multiple: Int,
    additional: Boolean,
    showAdditional: Boolean,
    onChange: (Int, Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("倍数", Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { if (multiple > 1) onChange(multiple - 1, additional) }) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text("$multiple", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { if (multiple < 99) onChange(multiple + 1, additional) }) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
    if (showAdditional) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = additional, onCheckedChange = { onChange(multiple, it) })
            Text("追加投注（每注 +1 元）", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AmountEditor(amount: Double?, onChange: (Double?) -> Unit) {
    var text by remember(amount) { mutableStateOf(amount?.let { fmtYuan(it) } ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { c -> c.isDigit() || c == '.' }
            onChange(text.toDoubleOrNull())
        },
        label = { Text("票面总金额（元）") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun IdEditor(label: String, value: String?, onChange: (String?) -> Unit) {
    var text by remember(value) { mutableStateOf(value ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it.trim().ifEmpty { null }) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    )
}
