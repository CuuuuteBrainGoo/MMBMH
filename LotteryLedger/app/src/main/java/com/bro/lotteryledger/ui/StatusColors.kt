package com.bro.lotteryledger.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.bro.lotteryledger.core.TicketStatus

/**
 * 票状态 → 色标颜色。
 *
 * ## 为什么要抽出来
 *
 * 首页的票卡片和详情页的头部各写了一份一模一样的 `when (status)`。
 * 2026-09-27 加 `RESULT_UNAVAILABLE` 时，编译器在**两个地方**都报了
 * "when must be exhaustive" —— 说明这两份迟早会改漏一份。
 *
 * 抽成一处之后，加新状态只会在**这一个**地方报错。
 *
 * ## 配色约定
 *
 * - 红 [LedgerColors.Prize]：中奖、有收获
 * - 墨绿 [LedgerColors.Spend]：已兑奖 / 支出
 * - 紫 [LedgerColors.Expired]：过期未兑（少爷 2026-09-27 指定用紫色 —— 白扔的钱）
 * - 金 [LedgerColors.Warn]：待确认、需要留意
 * - 灰 [MaterialTheme.colorScheme.onSurfaceVariant]：平淡结果（没中）
 * - 红 [MaterialTheme.colorScheme.error]：需要人工介入的异常
 */
@Composable
fun statusColor(status: TicketStatus): Color = when (status) {
    TicketStatus.PENDING_DRAW -> MaterialTheme.colorScheme.primary
    TicketStatus.AWAITING_RESULT -> LedgerColors.Warn
    // 查不到结果是个**需要你动手**的异常状态，用告警色，别让它看着像正常等待
    TicketStatus.RESULT_UNAVAILABLE -> MaterialTheme.colorScheme.error
    TicketStatus.NOT_WON -> MaterialTheme.colorScheme.onSurfaceVariant
    TicketStatus.PRIZE_PENDING -> LedgerColors.Warn
    TicketStatus.TO_REDEEM -> LedgerColors.Prize
    TicketStatus.REDEEMED -> LedgerColors.Spend
    TicketStatus.EXPIRED_UNCLAIMED -> LedgerColors.Expired
}
