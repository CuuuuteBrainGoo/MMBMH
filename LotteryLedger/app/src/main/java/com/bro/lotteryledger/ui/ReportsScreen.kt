package com.bro.lotteryledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bro.lotteryledger.core.LedgerReports

/**
 * 分彩种 / 分期号统计报表（少爷 2026-09-27 第 5 条）。
 *
 * ## 它回答什么问题
 *
 * 首页那张主卡只有「总账」一个数字。少爷想知道下一层：
 *  - **哪个彩种在亏、哪个在赚** —— 「按彩种」这一屏
 *  - **哪一期买得多、哪一期中过** —— 「按期号」这一屏
 *
 * ## 口径
 *
 * 金额口径**完全复用**首页总账那一套（见 [LedgerReports] 的类注释）：
 * 花出去照算、中奖排除过期未兑、过期单独带负号显示。
 * 所以各彩种的net求和 = 首页总 net，不会出现「报表加不出总账」这种事。
 *
 * ## 为什么不做图表
 *
 * 少爷的票量级是「几十张」，不是「几万条」。这种量级下**表格比图更直观** ——
 * 一眼就能看到具体金额和净收支，而柱状图还得去对刻度。等票上千再考虑图。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    tickets: List<com.bro.lotteryledger.db.TicketEntity>,
    onBack: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }

    val byType = remember(tickets) { LedgerReports.byLotteryType(tickets) }
    val byIssue = remember(tickets) { LedgerReports.byIssue(tickets) }

    val totalPurchase = tickets.sumOf { it.purchaseAmount }
    val totalPrize = tickets.filter { it.ticketStatus != "EXPIRED_UNCLAIMED" }
        .sumOf { it.prizeAmount ?: 0.0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("统计报表") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(14.dp, 10.dp, 14.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---- 总览条 ----
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "共 ${tickets.size} 张票 · 花出去 ${yuan(totalPurchase)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            signedYuan(totalPrize - totalPurchase),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "净收支（中奖 ${yuan(totalPrize)} − 投入）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                        )
                    }
                }
            }

            // ---- Tab 切换 ----
            item {
                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = { Text("按彩种") }
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text("按期号") }
                    )
                }
            }

            val buckets = if (tab == 0) byType else byIssue

            if (buckets.isEmpty()) {
                item {
                    Text(
                        "还没有可统计的票。先去录一张吧。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(buckets, key = { it.key }) { b ->
                ReportRow(b)
            }

            if (buckets.size > 1) {
                item {
                    Spacer(Modifier.height(2.dp))
                    // 合计行 —— 让用户能当场验证「各项加起来等于总账」
                    val sumPurchase = buckets.sumOf { it.purchase }
                    val sumPrize = buckets.sumOf { it.prize }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (tab == 0) "合计（${buckets.size} 个彩种）"
                                else "合计（${buckets.size} 期）",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.weight(1f))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    signedYuan(sumPrize - sumPurchase),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (sumPrize - sumPurchase >= 0) LedgerColors.Prize
                                    else LedgerColors.Spend
                                )
                                Text(
                                    "投入 ${yuan(sumPurchase)} · 中奖 ${yuan(sumPrize)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportRow(b: LedgerReports.Bucket) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        b.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val sub = b.subtitle
                    if (!sub.isNullOrBlank()) {
                        Text(
                            sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        signedYuan(b.net),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        // 中国习惯：正=红、负=绿
                        color = if (b.net >= 0) LedgerColors.Prize else LedgerColors.Spend
                    )
                    Text(
                        "${b.ticketCount} 张",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                MiniStat("投入", yuan(b.purchase), LedgerColors.Spend, Modifier.weight(1f))
                MiniStat("中奖", yuan(b.prize), LedgerColors.Prize, Modifier.weight(1f))
                MiniStat("已兑", yuan(b.redeemed), LedgerColors.Spend, Modifier.weight(1f))
                // 过期带负号 + 紫色 —— 跟首页保持一致
                MiniStat(
                    "过期",
                    if (b.expired > 0) "−${yuan(b.expired)}" else yuan(0.0),
                    LedgerColors.Expired,
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}
