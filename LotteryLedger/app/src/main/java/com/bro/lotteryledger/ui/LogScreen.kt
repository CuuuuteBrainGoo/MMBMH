package com.bro.lotteryledger.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bro.lotteryledger.core.LedgerLog
import com.bro.lotteryledger.core.LogFileSink

/**
 * 日志页：看日志 / 复制 / 分享导出。
 *
 * 用途（少爷的原话）：出问题时能方便地把日志发给 Bro。
 * 所以主按钮是「分享」，走系统分享面板，可以直接甩到微信/QQ/邮件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf(LedgerLog.snapshotNewestFirst()) }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val shown = remember(entries, filter) {
        entries.filter { filter.accepts(it.level) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运行日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = LogFileSink(ctx).exportAll()
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "彩票账本日志 ${LedgerLog.fileStamp()}")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        ctx.startActivity(Intent.createChooser(intent, "导出日志"))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "导出")
                    }
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {

            // 级别筛选
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LogFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.display, fontSize = 12.sp) }
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("${shown.size}/${entries.size}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()

            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (entries.isEmpty()) "还没有日志\n（用一次识别再来看看）" else "当前筛选下没有日志",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(shown, key = { it.ts.toString() + it.message.hashCode() }) { e ->
                        LogRow(e)
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空内存日志？") },
            text = { Text("只清掉当前显示的内存缓冲。已经写到文件里的历史日志仍在，「导出」时还会带上。") },
            confirmButton = {
                TextButton(onClick = {
                    LedgerLog.clear()
                    entries = LedgerLog.snapshotNewestFirst()
                    showClearConfirm = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}

private enum class LogFilter(val display: String) {
    ALL("全部"), WARN_UP("警告+"), ERROR_ONLY("仅错误");

    fun accepts(level: LedgerLog.Level): Boolean = when (this) {
        ALL -> true
        WARN_UP -> level == LedgerLog.Level.WARN || level == LedgerLog.Level.ERROR
        ERROR_ONLY -> level == LedgerLog.Level.ERROR
    }
}

@Composable
private fun LogRow(e: LedgerLog.Entry) {
    val accent = when (e.level) {
        LedgerLog.Level.ERROR -> MaterialTheme.colorScheme.error
        LedgerLog.Level.WARN -> LedgerColors.Warn
        LedgerLog.Level.INFO -> MaterialTheme.colorScheme.primary
        LedgerLog.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row {
            // 左侧级别色条，扫一眼就能定位错误
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        hhmmss(e.ts),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        e.level.name,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        e.tag,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    e.message,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun hhmmss(ts: Long): String {
    val c = java.util.Calendar.getInstance().apply { time = java.util.Date(ts) }
    return "%02d:%02d:%02d".format(
        c.get(java.util.Calendar.HOUR_OF_DAY),
        c.get(java.util.Calendar.MINUTE),
        c.get(java.util.Calendar.SECOND)
    )
}
