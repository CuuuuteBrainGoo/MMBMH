package com.bro.lotteryledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bro.lotteryledger.core.*
import com.bro.lotteryledger.db.TicketEntity
import java.time.LocalDate

/**
 * 首页台账（§15）。
 *
 * 排版思路：**先给结论，再给明细**。
 * 最上面一张 hero 卡放「净收支」大数字（视觉重心），
 * 下面才是张数/注数这类次要指标，最后才是票据列表。
 * 之前所有信息都是同一字号的灰字平铺，看一眼不知道重点在哪。
 *
 * 关键业务约束（§15）：一、二等奖「奖金待确认」时**不能按 0 元计入盈亏**，
 * 这时 hero 卡不显示净收支数字，改为显式提示待确认笔数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: MainViewModel.HomeState,
    checking: Boolean,
    /** 一键兑奖进行中 */
    redeeming: Boolean,
    /**
     * 列表滚动位置 —— **由外层持有**（少爷 2026-09-27 要求保留位置）。
     *
     * 不能在这里 `rememberLazyListState()`：进详情页时整个 HomeScreen
     * 会被移出组合树，内部 state 跟着销毁，返回就弹回顶部了。
     * 详见 [MainActivity] 里 `homeListState` 的注释。
     */
    listState: LazyListState,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onLogs: () -> Unit,
    /** 打开分彩种 / 分期号统计报表（少爷 2026-09-27 第 5 条） */
    onReports: () -> Unit,
    onCheckAll: () -> Unit,
    /** 一键兑奖：把全部「已中奖待兑」的票标成已兑奖 */
    onRedeemAll: () -> Unit,
    onFilterChange: (TicketFilter) -> Unit,
    /** 继续核对还没入账的识别草稿（批量导入的队列） */
    onReviewDrafts: () -> Unit,
    onTicketClick: (TicketEntity) -> Unit
) {
    val s = state.stats
    val totalUnits = state.tickets.sumOf { it.effectiveUnits.toLong() }
    // 筛选面板的展开状态是纯 UI 状态，不必提到 ViewModel
    var filterOpen by remember { mutableStateOf(false) }
    // 一键兑奖的二次确认（不可逆操作，必须问一句）
    var redeemConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("彩票账本", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "v${com.bro.lotteryledger.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // 顶栏三个图标按钮 —— 方案 2（少爷 2026-09-27 定稿）：
                    // **加底片 + 线宽加粗 + tint 提亮，不加文字标签**。
                    //
                    // 起因：原来的裸图标在浅色底上用的是 onSurfaceVariant，
                    // 灰扑扑一片，少爷的原话是「首行右边这三个按钮是不是辨识度太低了」。
                    //
                    // 三处改动合起来才有效果，缺一不可：
                    //  1. 38dp 圆角底片 —— 把图标从背景里"托"起来，给出可点区域边界
                    //     浅色 `rgba(28,26,23,.055)` / 深色 `rgba(237,231,220,.09)`
                    //     ⚠️ 用**极淡**的填充，不能是实心色块 —— 少爷之前已经否掉过
                    //     「墨金黑块太突兀」，这里同理
                    //  2. 线宽 1.7 → 2.1 —— 像素层面加粗，小尺寸下最直接的辨识度提升
                    //  3. tint 提到正文级 —— 从 onSurfaceVariant 提到 onSurface，
                    //     对比度从 4.5:1 提到 12:1 级别
                    //
                    // 曾经讨论过的「文字胶囊」（图标下面配字）被少爷否掉了：
                    // 顶栏本来就只有一条，配字会把标题挤没。
                    //
                    // ⚠️ 这里**故意没有角标**（少爷 2026-09-27 删掉的）。
                    // 原先是 `BadgedBox` + 「生效条件数」的数字，但少爷明确说
                    // 「不存在需要未读提示的功能，不要把这个未读提示做进去」。
                    // 那颗数字在用户眼里就是「未读」，跟它实际含义（筛选条件条数）
                    // 对不上，属于误导 —— 删掉。
                    //
                    // 那「列表被过滤过了，票没丢」怎么告诉用户？靠两处了：
                    //  1. 按钮本身**变色**（下面 tint 那行），一眼看出筛选在生效
                    //  2. 列表标题那行显示「3/12 张」，这是最直接的地方
                    TopBarIcon(
                        icon = Icons.Default.FilterList,
                        label = "筛选与搜索",
                        tint = if (state.filter.active) MaterialTheme.colorScheme.primary else null,
                        onClick = { filterOpen = !filterOpen }
                    )
                    TopBarIcon(Icons.Default.BarChart, "统计报表", onClick = onReports)
                    TopBarIcon(Icons.Default.Article, "日志", onClick = onLogs)
                    TopBarIcon(Icons.Default.Settings, "设置", onClick = onSettings)
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("录入彩票", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(14.dp, 10.dp, 14.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                HeroCard(
                    purchase = s?.purchase ?: 0.0,
                    prizeConfirmed = s?.prizeConfirmed ?: 0.0,
                    redeemed = s?.redeemed ?: 0.0,
                    toRedeem = s?.toRedeem ?: 0.0,
                    expired = s?.expired ?: 0.0,
                    pendingPrizeCount = s?.pendingPrizeCount ?: 0,
                    // 待确认金额的票编号清单（少爷 2026-09-27 要求）
                    pendingConfirmCodes = state.toConfirmCodes,
                    loading = state.loading,
                    // 筛选生效时主账卡多显示一行筛选后金额（少爷 2026-09-27 要求）
                    filterSummary = state.filter.summary(),
                    filtered = state.visibleStats
                )
            }

            // ---- 公益贡献合计（少爷 2026-09-27 要求）----
            // 只要录入了票就能算出来，不需要额外操作。
            // **单独一条，不塞进 hero 卡**：那个位置是「净收支」的主数字，
            // 混进公益金额会让"到底亏了多少"失焦。
            if (!state.loading && state.tickets.isNotEmpty()) {
                item {
                    PublicWelfareBar(
                        PublicWelfare.totalOf(state.tickets.map { it.purchaseAmount })
                    )
                }
            }

            // 次要指标：并排小卡，扫一眼就有数
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricTile(
                        label = "已入账",
                        value = "${state.tickets.size}",
                        unit = "张",
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "总注数",
                        value = "$totalUnits",
                        unit = "注",
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "待开奖",
                        value = "${state.needsCheckCount}",
                        unit = "张",
                        accent = if (state.needsCheckCount > 0) LedgerColors.Warn else null,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (state.draftCount > 0) {
                item {
                    // 这条以前是纯提示，用户看到了也没法处理 ——
                    // 批量导入之后草稿是**队列**，必须有出口，否则票会一直躺着。
                    InfoBanner(
                        text = "有 ${state.draftCount} 张识别好的票还没核对入账（不计入账目）",
                        action = {
                            TextButton(
                                onClick = onReviewDrafts,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            ) { Text("继续核对") }
                        }
                    )
                }
            }
            // ---- 一键兑奖（少爷 2026-09-27 要求）----
            // 场景：去网点一次把兜里那叠票都兑了，回来不想一张张点。
            if (state.toRedeemCount > 0) {
                item {
                    InfoBanner(
                        text = "有 ${state.toRedeemCount} 张票已中奖待兑，" +
                            "合计 ${yuan(state.toRedeemAmount)}" +
                            // 附上编号清单，用户能去列表里逐个对上（少爷 2026-09-27 要求）
                            if (state.pendingRedeemCodes.isNotEmpty())
                                "\n编号：${state.pendingRedeemCodes}" else "",
                        action = {
                            if (redeeming) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "处理中…",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            } else {
                                TextButton(
                                    onClick = { redeemConfirm = true },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                ) { Text("一键兑奖") }
                            }
                        }
                    )
                }
            }
            // 查不到结果的票：**不再自动重试**（重试也不会变），必须让用户知道有这回事，
            // 否则他只会在列表里看到几张状态奇怪的票，不知道该怎么办。
            if (state.unavailableCount > 0) {
                item {
                    InfoBanner(
                        text = "有 ${state.unavailableCount} 张票查不到开奖结果，" +
                            "需要你手动记一笔（点开下面的票）" +
                            if (state.unavailableCodes.isNotEmpty())
                                "\n编号：${state.unavailableCodes}" else "",
                        action = {
                            TextButton(
                                onClick = {
                                    onFilterChange(TicketFilter(status = StatusFilter.PENDING))
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            ) { Text("找出来") }
                        }
                    )
                }
            }
            if (state.needsCheckCount > 0) {
                item {
                    InfoBanner(
                        text = "有 ${state.needsCheckCount} 张彩票已过开奖日期，等待获取开奖结果",
                        action = {
                            if (checking) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "核验中…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                            } else {
                                TextButton(
                                    onClick = onCheckAll,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                ) { Text("立即核验") }
                            }
                        }
                    )
                }
            }

            // ---- 筛选面板（展开时）----
            if (filterOpen) {
                item { FilterPanel(state.filter, onFilterChange) }
            }

            // ---- 列表标题 ----
            if (state.tickets.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("购彩记录", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            // 筛选生效时显示「N/M 张」—— 让用户一眼知道列表被过滤过，
                            // 不会以为票丢了
                            if (state.filter.active) "${state.visible.size}/${state.tickets.size} 张"
                            else "${state.tickets.size} 张",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.filter.active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        if (state.filter.active) {
                            TextButton(onClick = { onFilterChange(TicketFilter.NONE) }) {
                                Text("清除筛选", style = MaterialTheme.typography.labelSmall)
                            }
                        } else if (state.totalCalls > 0) {
                            Text(
                                "AI 识别 ${state.totalCalls} 次 · ${state.totalTokens} token",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (state.tickets.isEmpty() && !state.loading) {
                item { EmptyHint() }
            } else if (state.visible.isEmpty() && !state.loading) {
                item {
                    NoMatchHint(state.filter.summary()) {
                        onFilterChange(TicketFilter.NONE)
                    }
                }
            }

            items(state.visible, key = { it.id }) { t ->
                // 同一期买了多张时把序号传下去（同组只有一张时为 null，不显示）
                TicketCard(
                    t = t,
                    seq = state.issueSeq[t.id],
                    code = state.ticketCodes[t.id],
                    onClick = { onTicketClick(t) }
                )
            }
        }
    }

    // 一键兑奖的二次确认。不可逆 + 涉及金额，必须让用户看清将入账多少再点。
    if (redeemConfirm) {
        AlertDialog(
            onDismissRequest = { redeemConfirm = false },
            title = { Text("一键兑奖？") },
            text = {
                Column {
                    Text(
                        "会把 ${state.toRedeemCount} 张「已中奖待兑」的票全部标记为已兑奖，" +
                            "合计 ¥${"%.2f".format(state.toRedeemAmount)} 计入「已兑」。"
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "这个操作不能撤销。\n" +
                            "只处理已中奖待兑的票 —— 奖金待确认的（一、二等奖）" +
                            "和已过期的都不会动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    redeemConfirm = false
                    onRedeemAll()
                }) { Text("全部兑掉") }
            },
            dismissButton = {
                TextButton(onClick = { redeemConfirm = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 公益贡献合计（少爷 2026-09-27 要求）。
 *
 * 数据来源：财政部公告明确福彩双色球、体彩超级大乐透的**公益金提取比例都是 36%**
 * （奖金 51% + 发行费 13% + 公益金 36%）。票面上印的那句
 * 「感谢您为公益事业贡献 X 元」就是这个数 —— 2 元一注对应 0.72 元。
 *
 * 所以「票面金额 × 36%」即公益金，不需要额外记录，也**不用动数据库**。
 *
 * 用金色而非红/绿：这不是收益也不是亏损，是另一本账。
 */
@Composable
private fun PublicWelfareBar(total: Double) {
    if (total <= 0) return
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = LedgerColors.Gold.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.VolunteerActivism,
                contentDescription = null,
                tint = LedgerColors.Gold,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "公益贡献 ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        yuan(total),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LedgerColors.Gold
                    )
                }
                Text(
                    PublicWelfare.describe(total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 「同一期买的第几张」小徽章，形如 `2/5`。
 *
 * 只在**同一期同一彩种确实买了多张**时才显示（由 [IssueSeq] 判定）。
 * 用金色跟「待确认」区分开 —— 它是纯信息标注，不表示任何异常。
 */
@Composable
private fun IssueSeqBadge(index: Int, total: Int) {
    Surface(
        color = LedgerColors.Gold.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            "$index/$total",
            style = MaterialTheme.typography.labelSmall,
            color = LedgerColors.Gold,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/**
 * 彩票展示编号徽章，形如 `#12`（少爷 2026-09-27 要求）。
 *
 * > 「在首页每个彩票前面加一个编号，越新的编号数字越大，
 * >  作为每张彩票展示给用户的编码。」
 *
 * ## 为什么用中性色而不是金色
 *
 * 金色已经被 [IssueSeqBadge]（同一期第几张）和「奖金待确认」占了，
 * 红色是「中奖」、紫色是「已过期」——**编号是纯标识，不带任何含义**，
 * 抢了语义色会让用户以为它代表某种状态。
 *
 * 所以用 `onSurfaceVariant` 配一层浅底：够醒目（能一眼扫到、能报给开发者），
 * 又明确是「中性信息」。等宽字体让数字对齐，一眼能比大小。
 */
@Composable
private fun TicketCodeBadge(code: Int) {
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

/**
 * 主账卡 —— **变体 4**（少爷 2026-09-27 从 4 个变体里挑的）。
 *
 * ## 为什么不是实心色块
 *
 * 原来这里是 `primary` 的**实心渐变色块**（墨金皮肤下就是一大块墨绿）。
 * 少爷的原话是「首页的"墨金黑块"是不是太突兀了」—— 问题有三层：
 *
 * 1. **它跟整页的浅色调脱节**：上面一块深色，下面全是浅卡片，像贴了张黑纸
 * 2. **反白字反而更累**：白字压在深色上，亮度差最大，盯久了眼睛跳
 * 3. **它把"净收支"这件事喊得太重**：账本天天看，不需要每天被吼一次
 *
 * ## 变体 4 的做法：**降噪，但保留层级**
 *
 * 层级还在（它仍是最显眼的卡），只是从"喊"改成"说"：
 * - 底：皮肤主色的**极淡渐变**（0.07 → 0.02）+ `surfaceVariant` 打底
 *   ⚠️ 必须用渐变而不是纯色 —— 纯色在深色主题下就是一个灰平面，没有"这是一张卡"的暗示
 * - 边：左侧一缕金色竖线，替代原来的整块金渐变
 * - 主数字：**主题正文色**（`onSurface`），不再反白 —— 对比度反而更高更耐看
 *
 * ## 筛选时主账怎么办（少爷 2026-09-27 要求「筛选结果要反映在主账上」）
 *
 * 主数字**保持是全部账**，筛选生效时在卡里多显示一行筛选后的金额。
 * 为什么不把主数字直接换成筛选结果：那个数字是天天看的基准。
 * 它跟着筛选漂移的话，用户今天看到 −86、筛一下变成 +38，
 * 第一反应是「我记错账了」而不是「哦这是筛选后的」。
 * 两行并在，既有基准又有变化 —— 这比二选一信息量更大，而且不会看错。
 *
 * §15：待确认金额 > 0 时**不给净收支数字**，否则等于用 0 元冒充一等奖。
 *
 * @param filterSummary 生效条件的可读摘要，如「双色球 · 未中奖 · 近 3 个月」
 * @param filtered 筛选后票的聚合；没筛选时为 null（那一段就不渲染）
 */
@Composable
private fun HeroCard(
    purchase: Double,
    prizeConfirmed: Double,
    redeemed: Double,
    toRedeem: Double,
    expired: Double,
    pendingPrizeCount: Int,
    /** 待确认金额的票编号清单（少爷 2026-09-27 要求），形如 `#12、#9` */
    pendingConfirmCodes: String = "",
    loading: Boolean,
    filterSummary: String = "",
    filtered: TicketStats? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    // 主数字不再反白，用正文色 —— 见上面第 3 点
    val mainText = MaterialTheme.colorScheme.onSurface
    val subText = MaterialTheme.colorScheme.onSurfaceVariant
    // 极淡渐变：靠它做"这是一张有质感的卡"，不是靠颜色深浅做对比
    val wash = Brush.linearGradient(
        listOf(primary.copy(alpha = 0.07f), primary.copy(alpha = 0.02f))
    )

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // 左侧一缕金线：替代原来的整块金渐变，留给"这是账本"一点仪式感
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(
                            listOf(LedgerColors.Gold, LedgerColors.Gold.copy(alpha = 0.25f))
                        )
                    )
            )
            Column(
                Modifier
                    .background(wash)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Text(
                    "净收支",
                    style = MaterialTheme.typography.labelMedium,
                    color = subText
                )
                Spacer(Modifier.height(4.dp))

                if (loading) {
                    Text("—", style = MaterialTheme.typography.displaySmall, color = mainText)
                } else if (pendingPrizeCount > 0) {
                    // §15 硬要求：不给最终盈亏
                    Text("待确认", style = MaterialTheme.typography.headlineMedium, color = mainText)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "有 $pendingPrizeCount 笔中奖金额还没填，" +
                            "一、二等奖不自动抓取金额，填好后才计入" +
                            if (pendingConfirmCodes.isNotEmpty())
                                "\n编号：$pendingConfirmCodes" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = subText
                    )
                } else {
                    val net = prizeConfirmed - purchase
                    Text(
                        signedYuan(net),
                        style = MaterialTheme.typography.displaySmall,
                        // 净收支的正负用**语义色**：红=赚（中国习惯）、
                        // 亏用正文色而不是绿 —— 绿色在墨金皮肤里是"支出色"，
                        // 但语义上"没回本"不该被涂成一种"状态色"，压暗反而更克制
                        color = if (net >= 0) LedgerColors.Prize else mainText,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (net >= 0) "已回本并盈利" else "还没回本",
                        style = MaterialTheme.typography.bodySmall,
                        color = subText
                    )
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth()) {
                    HeroStat("花出去", purchase, mainText, Modifier.weight(1f))
                    HeroStat("中奖", prizeConfirmed, mainText, Modifier.weight(1f))
                }

                if (redeemed > 0 || toRedeem > 0 || expired > 0) {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        HeroStat("已兑", redeemed, mainText, Modifier.weight(1f))
                        HeroStat("待兑", toRedeem, mainText, Modifier.weight(1f))
                        // 过期是**白扔的钱**，不是资产 —— 前面必须带负号（少爷 2026-09-27 第 1 条）。
                        // 不带负号的话，它跟「已兑」「待兑」并排显示时会被当成「还有这么多钱」。
                        // 颜色走 [LedgerColors.Expired]（必须的紫），跟负号一起双重提示。
                        HeroStat(
                            "过期",
                            expired,
                            LedgerColors.Expired,
                            Modifier.weight(1f),
                            negative = true
                        )
                    }
                }

                // ---- 筛选生效时：把筛选结果的金额摊在这里（少爷 2026-09-27 要求）----
                if (filtered != null) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "当前筛选 · ${filtered.count} 张",
                        style = MaterialTheme.typography.labelSmall,
                        color = subText
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        // 条件摘要放小字，别占主视线
                        filterSummary.ifBlank { "已筛选" },
                        style = MaterialTheme.typography.labelSmall,
                        color = subText.copy(alpha = 0.75f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        HeroStat("花出去", filtered.purchase, mainText, Modifier.weight(1f))
                        HeroStat("中奖", filtered.prize, mainText, Modifier.weight(1f))
                        // 这里的净收支**带正负号**：它是要跟上面主数字对照着看的，
                        // 不带号的话「+38」和「38」看起来一样，对照就失去意义
                        HeroStat("净收支", filtered.net, mainText, Modifier.weight(1f), signed = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroStat(
    label: String,
    value: Double,
    /** **值**的颜色。标签一律用弱化的正文色，不跟值抢。 */
    textColor: Color,
    modifier: Modifier = Modifier,
    /** true 时金额前显示 `−`（用于「过期」这类**白扔的钱**，少爷 2026-09-27 第 1 条）。 */
    negative: Boolean = false,
    /**
     * true 时强制带 `+` / `−`。
     *
     * 用于筛选后的「净收支」—— 它是要跟上面主数字对照看的，
     * 不带号的话 `+38` 和 `38` 长得一样，对照就白做了。
     */
    signed: Boolean = false
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            // 标签固定用弱化色，跟值的颜色解耦 ——
            // 传进来的 textColor 是给**金额**的，标签跟着变会让它对不上号
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        )
        Text(
            when {
                signed -> signedYuan(value)
                negative && value > 0 -> "−${yuan(value)}"
                else -> yuan(value)
            },
            style = MaterialTheme.typography.titleMedium,
            color = textColor
        )
    }
}

/** 次要指标小卡：数字大、标签小。 */
@Composable
private fun MetricTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    accent: Color? = null
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = accent ?: MaterialTheme.colorScheme.onSurface
                )
                if (unit.isNotEmpty()) {
                    Text(
                        unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 提示条。
 *
 * [action] 是右侧的动作按钮 —— 只在「有事可做」时才给（比如有待核验的票时给「立即核验」）。
 * 纯通知的提示条不要塞按钮，否则用户会以为必须点。
 */
@Composable
private fun InfoBanner(
    text: String,
    action: (@Composable () -> Unit)? = null
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(LedgerColors.Warn, RoundedCornerShape(3.dp))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                action()
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(vertical = 32.dp, horizontal = 20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Outlined.ConfirmationNumber,
                null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            )
            Spacer(Modifier.height(14.dp))
            Text("还没有入账的彩票", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "点右下角「录入彩票」。\n" +
                    "可以拍票让 AI 读，也可以不拍照、直接手工填写。\n" +
                    "想用拍照识别的话，先到「设置 → AI 平台」填一个 Key。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 筛选面板。
 *
 * **默认收起** —— 首页的主角是「净收支 + 票列表」，常驻一排筛选控件会抢视觉。
 * 需要时点右上角漏斗图标展开。
 *
 * 面板里写明「筛选只影响下面列表，上方统计是全部票」——
 * 因为这句话不说的话，用户会以为净收支数字也跟着变了。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(filter: TicketFilter, onChange: (TicketFilter) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "彩种",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = filter.type == null,
                    onClick = { onChange(filter.copy(type = null)) },
                    label = { Text("全部") }
                )
                LotteryType.entries.forEach { t ->
                    FilterChip(
                        selected = filter.type == t,
                        onClick = { onChange(filter.copy(type = t)) },
                        label = { Text(t.display) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "状态",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusFilter.entries.forEach { st ->
                    FilterChip(
                        selected = filter.status == st,
                        onClick = { onChange(filter.copy(status = st)) },
                        label = { Text(st.display) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            DateFilterSection(filter, onChange)

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = filter.keyword,
                onValueChange = { onChange(filter.copy(keyword = it)) },
                label = { Text("期号或票面编号") },
                placeholder = { Text("如 2026111、验票码") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (filter.keyword.isNotBlank()) {
                        IconButton(onClick = { onChange(filter.copy(keyword = "")) }) {
                            Icon(Icons.Default.Close, "清空关键词")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "筛选只影响下面的列表；上方主账仍是全部账，" +
                    "筛选后的金额会额外显示在主账卡里。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (filter.active) {
                Spacer(Modifier.height(2.dp))
                TextButton(
                    onClick = { onChange(TicketFilter.NONE) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("清除全部筛选") }
            }
        }
    }
}

/**
 * 「按日期」筛选（少爷 2026-09-27 要求）。
 *
 * ## 设计要点：快捷段是"帮你填日期框"，不是另一套条件
 *
 * 点「近 3 个月」以后，下面两个日期框会**真的变成** 2026-06-27 / 2026-09-27。
 * 好处是只有一个真相来源 —— 不会出现「选了快捷段，但我又手改了日期，
 * 到底听谁的」这种必须靠猜的状态。
 *
 * 判据用的是「当前区间**恰好等于**某个快捷段算出来的区间」，
 * 所以手改日期之后，快捷段的选中态会自动消失，不需要额外的标记位。
 *
 * ## 筛的是开奖日期，不是录入时间
 *
 * 列表本来就按开奖日期倒序排。用录入时间筛会出现「筛出来一片票，
 * 但顺序跟日期对不上」的割裂感 —— 而且补录往期老票很常见。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DateFilterSection(filter: TicketFilter, onChange: (TicketFilter) -> Unit) {
    // 记住"今天是哪天"——不 remember 的话每次重组都重新取，
    // 跨零点时（用户挂了一晚上）界面会突然跳，虽然罕见但没必要留这个坑
    val today = remember { LocalDate.now() }
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }

    Text(
        "开奖日期",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))

    // 当前区间命中哪个快捷段（没命中 = 手改的，全不选中）
    val hit = DateQuickPick.entries.firstOrNull { it.resolve(today) == filter.dateRange }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = !filter.dateRange.active,
            onClick = { onChange(filter.copy(dateRange = DateRange.NONE)) },
            label = { Text("不限") }
        )
        DateQuickPick.entries.forEach { q ->
            FilterChip(
                selected = hit == q,
                onClick = { onChange(filter.copy(dateRange = q.resolve(today))) },
                label = { Text(q.display) }
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 两个日期框：点开系统日期选择器。空值时显示提示文字而不是空白，
        // 否则用户看不出这里能点。
        DateBox(
            value = filter.dateRange.from,
            placeholder = "开始日期",
            modifier = Modifier.weight(1f)
        ) { pickFrom = true }
        Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
        DateBox(
            value = filter.dateRange.to,
            placeholder = "结束日期",
            modifier = Modifier.weight(1f)
        ) { pickTo = true }
    }

    if (filter.dateRange.active) {
        Spacer(Modifier.height(4.dp))
        Text(
            // 把解析出来的区间写清楚 —— 用户点「今年下半年」时得知道实际框了哪一段
            "已按 ${filter.dateRange.display()} 的开奖日期筛",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }

    if (pickFrom) {
        DatePickerDialog(
            initial = filter.dateRange.from ?: filter.dateRange.to,
            fallback = today,
            onDismiss = { pickFrom = false }
        ) { picked ->
            pickFrom = false
            onChange(filter.copy(dateRange = filter.dateRange.copy(from = picked.toString())))
        }
    }
    if (pickTo) {
        DatePickerDialog(
            initial = filter.dateRange.to ?: filter.dateRange.from,
            fallback = today,
            onDismiss = { pickTo = false }
        ) { picked ->
            pickTo = false
            onChange(filter.copy(dateRange = filter.dateRange.copy(to = picked.toString())))
        }
    }
}

/** 一个日期框。没值时显示灰提示，有值时显示日期并可点清空。 */
@Composable
private fun DateBox(
    value: String?,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.DateRange,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                value ?: placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = if (value != null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 系统日期选择器。
 *
 * 用 Material3 的 [DatePickerDialog] 而不是自己拼一个 —— 日期选择器的
 * 月份翻页、年份切换、闰年这些细节自己写必出错，系统组件还自带无障碍支持。
 *
 * [initial] 是 ISO 字符串或 null；解析不出来时用 [fallback]（今天）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialog(
    initial: String?,
    fallback: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit
) {
    val start = remember(initial) {
        initial?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: fallback
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = start.toEpochDay() * 86_400_000L
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                // 用户可能完全没动日期就点确定 —— 那也算选了这个初始日期，
                // 比"确定点了没反应"合理
                val millis = state.selectedDateMillis ?: (start.toEpochDay() * 86_400_000L)
                onPick(LocalDate.ofEpochDay(millis / 86_400_000L))
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    ) {
        DatePicker(state = state)
    }
}

/** 筛选之后一张都不剩时的提示 —— 必须带上「清除筛选」出口，否则像进了死胡同。 */
@Composable
private fun NoMatchHint(summary: String, onClear: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(vertical = 28.dp, horizontal = 20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.SearchOff,
                null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(12.dp))
            Text("没有符合条件的票", style = MaterialTheme.typography.titleMedium)
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "当前条件：$summary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onClear) { Text("清除筛选") }
        }
    }
}

/**
 * 票据卡片。
 *
 * 左侧那条竖色标是**彩种标识**：双色球红、大乐透蓝。
 * 一屏好几张票时，靠颜色比靠文字快得多。
 *
 * @param seq 同一期同一彩种买了多张时的序号 `(第几张, 共几张)`；
 *            同组只有一张时为 null，不显示 —— 标「1/1」纯属噪音
 */
@Composable
private fun TicketCard(
    t: TicketEntity,
    seq: Pair<Int, Int>?,
    /** 展示编号（少爷 2026-09-27 要求）。为 null 时不显示徽章 */
    code: Int?,
    onClick: () -> Unit
) {
    val type = LotteryType.from(t.lotteryType)
    val status = TicketStatus.from(t.ticketStatus)
    val accent = when (type) {
        LotteryType.SSQ -> LedgerColors.BallRed
        LotteryType.DLT -> LedgerColors.BallBlue
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // 彩种色标
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Column(Modifier.padding(14.dp).weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        // 展示编号放在最前面 —— 少爷原话是「在每个彩票**前面**加一个编号」。
                        // 放最左最容易扫、也最符合「这是这张票的门牌号」的直觉。
                        code?.let {
                            TicketCodeBadge(it)
                            Spacer(Modifier.width(7.dp))
                        }
                        Text(
                            "${type?.display ?: t.lotteryType} · ${t.issue} 期",
                            style = MaterialTheme.typography.titleMedium
                        )
                        // 同一期买了多张 → 标「2/5」。不标的话，
                        // 「一张 5 注的票」和「同一期买的 5 张票」在列表里长得一样。
                        seq?.let { (index, total) ->
                            Spacer(Modifier.width(6.dp))
                            IssueSeqBadge(index, total)
                        }
                    }
                    StatusChip(status)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("开奖 ")
                        append(t.drawDate)
                        append(" · ")
                        append(t.effectiveUnits)
                        append(" 注")
                        // ⚠️ 倍数**无条件显示**（少爷 2026-09-27 第 3 条）。
                        // 以前只在 > 1 时显示，于是「5 注」和「1 注 × 5 倍」并存 ——
                        // 金额一样、写法两样，扫列表时得停下来想一下。
                        // 「5 注 × 1 倍」虽然多两个字，但整列格式一致、不用想。
                        append(" × ${t.multiple} 倍")
                        if (t.additional) append(" · 追加")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        yuan(t.purchaseAmount),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    t.prizeAmount?.let { prize ->
                        // 过期未兑的票：**中奖金额前加负号 + 紫色**（少爷 2026-09-27 要求）。
                        // 这笔钱没拿到，不是收入 —— 不给负号的话，
                        // 一眼扫过去会以为它是收益。
                        val expired = status == TicketStatus.EXPIRED_UNCLAIMED
                        Text(
                            if (expired) "过期 −${yuan(prize)}" else "中奖 ${yuan(prize)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (expired) LedgerColors.Expired else LedgerColors.Prize
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: TicketStatus) {
    // 配色统一在 statusColor 里（以前首页和详情页各写一份，加状态时必漏一处）
    val color = statusColor(status)
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            status.display,
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

internal fun yuan(v: Double): String = "¥" + String.format("%.2f", v)

internal fun signedYuan(v: Double): String =
    (if (v >= 0) "+" else "-") + "¥" + String.format("%.2f", kotlin.math.abs(v))

/**
 * 顶栏图标按钮 —— 方案 2（少爷 2026-09-27 定稿）。
 *
 * ## 为什么不用 `IconButton`
 *
 * `IconButton` 的触摸区是固定的 48dp 且**没有可见底片**，图标直接躺在背景上。
 * 少爷的原话是「首行右边这三个按钮是不是辨识度太低了」—— 问题就出在这：
 * 浅色主题下默认 tint 是 `onSurfaceVariant`（灰），四颗灰图标挤在一条上，
 * 既没有边界感，也没有"这里能点"的暗示。
 *
 * ## 三处改动，缺一不可
 *
 * 1. **38dp 圆角底片**：给图标一个"托底"，划出可点区域。
 *    色值从当前皮肤 / 深浅色组里取（`TopBarChip`），都是极淡的一层。
 * 2. **容器 38dp + 图标 22dp**：图标占容器比例比默认（48/24）更大，
 *    视觉上线显得更重 —— 这是在不重绘 path 的前提下最干净的"加粗"手段。
 *    （Material Icons 是**填充式** path，没有 `strokeWidth` 可调；
 *    硬做描边外扩会让细小笔画糊成一团，得不偿失。）
 * 3. **tint 提亮**：从 `onSurfaceVariant` 提到 `TopBarIcon`（正文级），
 *    对比度大致从 4.5:1 提到 11:1 级别。
 *
 * @param tint 传非空值时覆盖默认（筛选生效时用 primary 变色）
 */
@Composable
private fun TopBarIcon(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .padding(horizontal = 3.dp)
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(LedgerColors.TopBarChip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint ?: LedgerColors.TopBarIcon,
            modifier = Modifier.size(22.dp)
        )
    }
}
