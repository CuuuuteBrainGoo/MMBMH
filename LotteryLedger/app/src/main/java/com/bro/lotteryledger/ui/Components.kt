package com.bro.lotteryledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bro.lotteryledger.core.GroupName

/** 一个号码球。复式里被选中的号与未选中的视觉区分开。 */
@Composable
fun NumberBall(
    number: String,
    group: GroupName,
    selected: Boolean = true,
    size: Int = 30
) {
    val base = when (group) {
        GroupName.RED, GroupName.FRONT -> LedgerColors.BallRed
        GroupName.BLUE, GroupName.BACK -> LedgerColors.BallBlue
    }
    val bg = if (selected) base else Color.Transparent
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(bg)
            .then(
                if (selected) Modifier else Modifier.border(
                    1.dp, MaterialTheme.colorScheme.outline, CircleShape
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = number,
            color = fg,
            fontSize = (size * 0.42f).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 候选号码球：虚线感边框 + 问号样式，提示「模型不确定」。 */
@Composable
fun CandidateBall(number: String, group: GroupName, size: Int = 30) {
    val base = when (group) {
        GroupName.RED, GroupName.FRONT -> LedgerColors.BallRed
        GroupName.BLUE, GroupName.BACK -> LedgerColors.BallBlue
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .border(2.dp, base, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(number, color = base, fontSize = (size * 0.40f).sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 一组号码横向排列。 */
@Composable
fun NumberRow(
    group: GroupName,
    numbers: List<String>,
    modifier: Modifier = Modifier,
    ballSize: Int = 30
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        numbers.forEach { n -> NumberBall(n, group, size = ballSize) }
    }
}
