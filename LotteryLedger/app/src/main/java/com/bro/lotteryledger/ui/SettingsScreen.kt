package com.bro.lotteryledger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bro.lotteryledger.R
import com.bro.lotteryledger.core.AiProviderConfig
import com.bro.lotteryledger.core.AiProviderKind
import com.bro.lotteryledger.core.AiRole
import com.bro.lotteryledger.core.EndpointNormalizer
import com.bro.lotteryledger.core.LedgerSkin
import com.bro.lotteryledger.core.ProviderDefaults
import com.bro.lotteryledger.core.ThinkingLevel
import com.bro.lotteryledger.core.ThemeMode
import com.bro.lotteryledger.db.AiProviderEntity

/**
 * AI 平台设置（§1.2 / §18）。
 *
 * 原则：平台、模型名、接口地址**全部可改**，不写死任何一家。
 * API Key 走 Keystore，界面上只显示「已配置 / 未配置」，不回显明文。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    providers: List<AiProviderEntity>,
    hasKey: (String) -> Boolean,
    /** 已保存 Key 的掩码（头尾各 3 字符），用于编辑框预填。 */
    maskedKey: (String) -> String?,
    versionName: String,
    versionCode: Int,
    thinkingLevel: ThinkingLevel,
    onThinkingLevel: (ThinkingLevel) -> Unit,
    /** 主题模式：浅色 / 深色 / 跟随系统（少爷 2026-09-27 第 6 条） */
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    /**
     * **此刻系统**是深色吗。
     *
     * 只用于在设置页显示「系统现在是深色」——
     * 少爷要求「跟随系统要做成实际的功能，不能只做成一个按钮」，
     * 那么用户得能**在设置页当场看到**它正跟着系统走，而不是切完系统
     * 再回到这儿猜「到底生效了没」。
     */
    systemDark: Boolean,
    /** 皮肤：墨金账本 / 支付宝 / 微信（少爷 2026-09-27） */
    skin: LedgerSkin,
    onSkin: (LedgerSkin) -> Unit,
    testState: MainViewModel.TestState,
    onTest: (
        existing: AiProviderEntity?, kind: AiProviderKind,
        baseUrl: String, model: String, timeout: Int, apiKey: String?
    ) -> Unit,
    onClearTest: () -> Unit,
    onSave: (
        existing: AiProviderEntity?, name: String, kind: AiProviderKind,
        baseUrl: String, model: String, role: AiRole, apiKey: String?,
        timeout: Int, retries: Int, quality: AiProviderConfig.ImageQuality, temp: Double
    ) -> Unit,
    onDelete: (Long) -> Unit,
    onOpenLogs: () -> Unit,
    /** 打开 AI 配置帮助页（少爷 2026-09-27 第 3 条） */
    onOpenHelp: () -> Unit,
    /** 导出备份（由外部弹系统文件选择器） */
    onExportBackup: () -> Unit,
    /** 从备份恢复（由外部弹文件选择器，选完先预览再确认） */
    onImportBackup: () -> Unit,
    backupWorking: Boolean,
    ticketCount: Int,
    /** 「拜拜财神」清空入口的状态（点够 13 次才解锁，然后 3 次确认） */
    wipe: MainViewModel.WipeState,
    onWipeTap: () -> Unit,
    onWipeAdvance: () -> Unit,
    onWipeConfirm: () -> Unit,
    onWipeCancel: () -> Unit,
    onBack: () -> Unit
) {
    var editing by remember { mutableStateOf<AiProviderEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true; editing = null }) {
                Icon(Icons.Default.Add, "添加平台")
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(14.dp, 14.dp, 14.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("AI 识别平台", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "App 不绑定任何一家平台。你可以随时换平台、换模型，账本数据不受影响。\n" +
                        "「主识别」负责读票；「复核」在结果有疑问时独立再看一遍（它看不到主识别的答案）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 帮助入口。放在平台列表**之前** —— 第一次来的人正是最需要它的时候，
            // 排在列表后面等于让人先看一屏看不懂的东西再去找说明书。
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = LedgerColors.Spend.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenHelp() }
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "不知道怎么填？看这里",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = LedgerColors.Spend
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "六个平台的官网跳转、官方手册、以及每个格子该填什么，" +
                                    "都写在配置帮助里。第一次配大约两分钟。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Default.ArrowForward,
                            "查看",
                            tint = LedgerColors.Spend
                        )
                    }
                }
            }

            if (providers.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "还没有配置任何平台。点右下角 + 添加一个开始使用。",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            items(providers, key = { it.id }) { p ->
                ProviderCard(
                    p = p,
                    keyOk = hasKey(p.apiKeyRef),
                    onEdit = { editing = p; creating = false },
                    onDelete = { onDelete(p.id) }
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "说明：识别用的图片只在你这台手机上临时存在，识别完立即删除，不会长期保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- 外观（少爷 2026-09-27 第 6 条）----
            item {
                Spacer(Modifier.height(4.dp))
                Text("外观", style = MaterialTheme.typography.titleMedium)
            }

            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("皮肤", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.weight(1f))
                            Text(
                                skin.display,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = LedgerColors.Spend
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "换的是整套配色（底色、主色、卡片）。深浅色是另一件事，见下面一项。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LedgerSkin.entries.forEach { s ->
                                FilterChip(
                                    selected = skin == s,
                                    onClick = { onSkin(s) },
                                    label = {
                                        Text(s.display, style = MaterialTheme.typography.bodySmall)
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            skin.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- 深浅色 ----
            item {
                // 「跟随系统」到底跟随得怎么样，这里给一句**实时**的话。
                // 少爷 2026-09-27：「跟随系统要做成实际的功能，不能只做成一个按钮」。
                // 用户改了系统开关再回到这一页，能直接看到「系统现在是深色」，
                // 就不用靠在两个界面之间来回切换去验证了。
                val effective = when (themeMode) {
                    ThemeMode.SYSTEM -> if (systemDark) "深色" else "浅色"
                    ThemeMode.LIGHT -> "浅色"
                    ThemeMode.DARK -> "深色"
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("深浅色", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "$effective（当前生效）",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = LedgerColors.Spend
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "以前这项只能跟着手机系统走。现在可以自己定：" +
                                "白天想亮、晚上想暗，随时切。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ThemeMode.entries.forEach { m ->
                                FilterChip(
                                    selected = themeMode == m,
                                    onClick = { onThemeMode(m) },
                                    label = {
                                        Text(m.display, style = MaterialTheme.typography.bodySmall)
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            themeMode.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 只在「跟随系统」时补这一句 —— 其它两种模式下系统状态无所谓，
                        // 多写一行反而是噪音
                        if (themeMode == ThemeMode.SYSTEM) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "你的手机现在是${if (systemDark) "深色" else "浅色"}模式。" +
                                    "去手机的「显示」设置里切换，这个 App 会跟着一起变。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---- 识别选项 ----
            item {
                Spacer(Modifier.height(4.dp))
                Text("识别选项", style = MaterialTheme.typography.titleMedium)
            }

            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("思考强度", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.weight(1f))
                            Text(
                                thinkingLevel.display,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = LedgerColors.Spend
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "AI 要不要「先想再答」。想得越多越慢，但对读票面这件事「没有帮助」 ——" +
                                "实测快速档 3 秒、深度档 24 秒，读出来的内容长度几乎一样。\n" +
                                "读不准的票可以临时切到「深度」再试一次。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ThinkingLevel.entries.forEach { lv ->
                                FilterChip(
                                    selected = thinkingLevel == lv,
                                    onClick = { onThinkingLevel(lv) },
                                    label = {
                                        Text(lv.display, style = MaterialTheme.typography.bodySmall)
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            thinkingLevel.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = LedgerColors.Warn
                        )
                    }
                }
            }

            // ---- 数据备份（§17）----
            item {
                Spacer(Modifier.height(4.dp))
                Text("数据备份", style = MaterialTheme.typography.titleMedium)
            }

            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "账目现在「只存在这台手机里」。导出一个文件存到网盘或电脑，" +
                                "换手机、重装 App 时能导回来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onExportBackup,
                                enabled = !backupWorking,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (backupWorking) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text("导出备份")
                            }
                            OutlinedButton(
                                onClick = onImportBackup,
                                enabled = !backupWorking,
                                modifier = Modifier.weight(1f)
                            ) { Text("恢复备份") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "当前 $ticketCount 张票。" +
                                "恢复是「合并」：本机已有的票会跳过，不会重复，也不会删你的数据。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "⚠️ API Key 不进备份（§17 要求），换手机后需要重新填一次。",
                            style = MaterialTheme.typography.labelSmall,
                            color = LedgerColors.Warn
                        )
                    }
                }
            }

            // ---- 诊断区 ----
            item {
                Spacer(Modifier.height(4.dp))
                Text("诊断", style = MaterialTheme.typography.titleMedium)
            }

            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLogs)
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("运行日志", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "出问题时进这里，右上角可直接导出分享",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("›", style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("版本", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "$versionName ($versionCode)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "包名 com.bro.lotteryledger · minSdk 26 · targetSdk 34",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- 「拜拜财神」隐藏入口（少爷 2026-09-27 要求）----
            //
            // 刻意长得像一行普通信息，没有任何按钮样式 —— 这是个危险操作，
            // 不该让人"看着就想点"。要连点 13 次才解锁（模仿安卓开发者模式）。
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().clickable { onWipeTap() }
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 应用图标（用前景图 + 浅色圆角底 —— 自适应图标在 Compose 里
                        // 直接 painterResource 不一定能正确渲染，前景 PNG 是稳的）
                        //
                        // ⚠️ 这个米色底**故意不跟皮肤走**：图标本身是墨金风格的水墨画，
                        // 垫一块宣纸米色才对味。换成支付宝蓝/微信绿反而跟图标打架。
                        // 但**必须跟深浅色走** —— 近黑背景上戳一块亮米色会很刺眼。
                        Box(
                            Modifier
                                .size(34.dp)
                                .background(LedgerColors.IconPad, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "拜拜财神，从头来过。",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }

    // ---- 清空的 3 次确认（少爷要求：连着确认 3 次，每次都不同）----
    //
    // 风格上是「俏皮 + 慎重」：第 1 次给你反悔和备份的机会，
    // 第 2 次摆出具体数字（要清掉多少），第 3 次是最后一道门。
    // 三次都点了才真清 —— 这个操作不可逆，拦得久一点比事后后悔好。
    WipeDialogs(
        state = wipe,
        onAdvance = onWipeAdvance,
        onConfirm = onWipeConfirm,
        onCancel = onWipeCancel
    )

    if (creating || editing != null) {
        // 已保存 Key 的掩码（头尾各 3 字符）。只在打开编辑器时算一次 ——
        // 底层要解密 Keystore 密文，放进每次重组会卡。
        val existingMask = remember(editing) {
            editing?.let { p -> maskedKey(p.apiKeyRef) }
        }
        ProviderEditorDialog(
            existing = editing,
            keyConfigured = editing?.let { hasKey(it.apiKeyRef) } ?: false,
            maskedKey = existingMask,
            testState = testState,
            onTest = { k, u, m, t, key -> onTest(editing, k, u, m, t, key) },
            onClearTest = onClearTest,
            onDismiss = { creating = false; editing = null; onClearTest() },
            onSave = { n, k, u, m, r, key, t, ret, q, temp ->
                onSave(editing, n, k, u, m, r, key, t, ret, q, temp)
                creating = false
                editing = null
                onClearTest()
            }
        )
    }
}

/**
 * 「拜拜财神」的三次确认。
 *
 * 三次文案各不相同，语气从轻松到郑重：
 *  1. 给一次反悔的机会，并提示先导出备份
 *  2. 摆出**具体数字**（到底要清掉多少）—— 只看"会清空所有数据"没有分量
 *  3. 最后一道门，确认按钮用警示红
 *
 * 三次都点了才真清。这个操作不可逆，拦久一点比事后后悔好。
 */
@Composable
private fun WipeDialogs(
    state: MainViewModel.WipeState,
    onAdvance: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    if (state.step <= 0) return

    when (state.step) {
        1 -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("真要跟财神说再见？") },
            text = {
                Column {
                    Text("清空之后，所有彩票、投注号码、中奖记录都会消失，而且找不回来。")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "这些账目要是还想留着，建议先到上面的「数据备份 → 导出备份」" +
                            "存一份 —— 十秒钟的事。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onAdvance) { Text("想清楚了，继续") }
            },
            dismissButton = {
                TextButton(onClick = onCancel) { Text("手滑了，算了") }
            }
        )

        2 -> {
            val c = state.counts
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text("第二次确认") },
                text = {
                    Column {
                        Text("这一下要清掉的是：")
                        Spacer(Modifier.height(8.dp))
                        if (c == null) {
                            Text("· 全部账目数据", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text("· ${c.tickets} 张票", style = MaterialTheme.typography.bodyMedium)
                            Text("· ${c.bets} 注", style = MaterialTheme.typography.bodyMedium)
                            if (state.purchase > 0) {
                                Text(
                                    "· 购彩合计 ¥${"%.2f".format(state.purchase)}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            if (c.drafts > 0) {
                                Text(
                                    "· ${c.drafts} 张还没核对完的草稿",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "财神记性很好，但他不会帮你把这些记回来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = LedgerColors.Warn
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onAdvance) { Text("我知道，继续") }
                },
                dismissButton = {
                    TextButton(onClick = onCancel) { Text("还是算了") }
                }
            )
        }

        else -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("最后一次了") },
            text = {
                Column {
                    Text("按下去，账本归零，从头来过。")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "平台配置和 API Key 会留着（不用重配），" +
                            "但账目数据永久删除，没有回收站。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("真的确定吗？", fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirm,
                    enabled = !state.busy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(if (state.busy) "正在清空…" else "拜拜财神") }
            },
            dismissButton = {
                TextButton(onClick = onCancel) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ProviderCard(
    p: AiProviderEntity,
    keyOk: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                // 火山方舟那一栏填的是接入点 ID 而不是模型名，卡片上照实显示
                (if (AiProviderKind.from(p.provider) == AiProviderKind.DOUBAO) "接入点：" else "模型：")
                    + p.model.ifBlank { "未填写" },
                style = MaterialTheme.typography.bodySmall
            )
            Text("角色：${AiRole.from(p.role).display}", style = MaterialTheme.typography.bodySmall)
            Text(
                "API Key：${if (keyOk) "已配置" else "未配置"}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (keyOk) LedgerColors.Spend else MaterialTheme.colorScheme.error
            )
            Text(
                p.baseUrl.ifBlank { "接口地址未填写" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditorDialog(
    existing: AiProviderEntity?,
    keyConfigured: Boolean,
    /** 已保存 Key 的掩码（如 `ark***e9b`）。null 表示没配过。 */
    maskedKey: String?,
    testState: MainViewModel.TestState,
    onTest: (AiProviderKind, String, String, Int, String?) -> Unit,
    onClearTest: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (
        String, AiProviderKind, String, String, AiRole, String?,
        Int, Int, AiProviderConfig.ImageQuality, Double
    ) -> Unit
) {
    val initKind = existing?.let { AiProviderKind.from(it.provider) } ?: AiProviderKind.DOUBAO
    val initTemplate = ProviderDefaults.template(initKind)

    var name by remember(existing, initKind) { mutableStateOf(existing?.name ?: initKind.display) }
    var kind by remember(existing, initKind) { mutableStateOf(initKind) }
    var baseUrl by remember(existing, initKind) { mutableStateOf(existing?.baseUrl ?: initTemplate.baseUrl) }
    var model by remember(existing, initKind) { mutableStateOf(existing?.model ?: initTemplate.model) }
    var role by remember(existing) {
        mutableStateOf(existing?.let { AiRole.from(it.role) } ?: AiRole.PRIMARY)
    }
    // 编辑已有配置时预填**掩码**（头尾各 3 字符），而不是空白。
    // 空白看不出「这里到底存过 Key 没有」，多平台时也认不出自己填的是哪把。
    // 保存/测试时会识别「框里就是掩码 → 用户没改」，不会拿它去覆盖真密钥。
    var apiKey by remember(existing) { mutableStateOf(maskedKey.orEmpty()) }

    /** 框里显示的正是掩码本身 → 用户还没动过它。 */
    val showingMask = maskedKey != null && apiKey == maskedKey
    var timeout by remember(existing) { mutableStateOf((existing?.timeoutSeconds ?: 240).toString()) }
    var retries by remember(existing) { mutableStateOf((existing?.maxRetries ?: 1).toString()) }
    var quality by remember(existing) {
        mutableStateOf(existing?.let { AiProviderConfig.ImageQuality.from(it.imageQuality) }
            ?: AiProviderConfig.ImageQuality.HIGH)
    }

    val templateNote = ProviderDefaults.template(kind).note
    // 每个输入框下方的短说明。换平台时跟着换，让新用户知道「这一格去哪抄」。
    val template = ProviderDefaults.template(kind)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "添加 AI 平台" else "编辑 AI 平台") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 平台选择
                Text("平台", style = MaterialTheme.typography.bodySmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AiProviderKind.entries.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = {
                                val oldTemplate = ProviderDefaults.template(kind)
                                kind = k
                                val t = ProviderDefaults.template(k)
                                // 换平台时只在用户没手改过的情况下替换预填值
                                if (baseUrl.isBlank() || baseUrl == oldTemplate.baseUrl) {
                                    baseUrl = t.baseUrl
                                }
                                if (model.isBlank() || model == oldTemplate.model) {
                                    model = t.model
                                }
                                if (name.isBlank() || name == oldTemplate.display) name = k.display
                            },
                            label = { Text(k.display, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }

                if (templateNote.isNotBlank()) {
                    Text(templateNote, style = MaterialTheme.typography.bodySmall,
                        color = LedgerColors.Warn)
                }

                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("配置名称") }, singleLine = true,
                    placeholder = { Text(kind.display) },
                    supportingText = { Hint(ProviderDefaults.Hints.NAME) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("接口地址") },
                    placeholder = { Text("https://…/v1") },
                    supportingText = {
                        Hint(template.baseUrlHint.ifBlank { ProviderDefaults.Hints.BASE_URL })
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                // 实时告诉用户「实际会请求哪个地址」，避免填了基础地址而不自知
                val normalized = EndpointNormalizer.normalize(baseUrl)
                if (baseUrl.isNotBlank() && normalized != baseUrl.trim()) {
                    Text(
                        "实际请求：$normalized",
                        style = MaterialTheme.typography.bodySmall,
                        color = LedgerColors.Warn
                    )
                }
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = {
                        // 火山方舟必须填接入点 ID（填模型名会 404），标签得跟着改，否则少爷会困惑
                        Text(if (kind == AiProviderKind.DOUBAO) "接入点 ID" else "模型名")
                    },
                    placeholder = {
                        if (kind == AiProviderKind.DOUBAO) Text("ep-xxxxxxxxxxxxxxxx")
                        else Text(template.model.ifBlank { "模型的名称" })
                    },
                    supportingText = {
                        Hint(template.modelHint.ifBlank { ProviderDefaults.Hints.MODEL })
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("角色", style = MaterialTheme.typography.bodySmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AiRole.entries.forEach { r ->
                        FilterChip(
                            selected = role == r, onClick = { role = r },
                            label = { Text(r.display, style = MaterialTheme.typography.bodySmall) }
                        )
                    }
                }

                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = {
                        Text(
                            when {
                                showingMask -> "API Key（已保存）"
                                keyConfigured -> "API Key（留空表示不修改）"
                                else -> "API Key"
                            }
                        )
                    },
                    singleLine = true,
                    // 掩码本身就是要让人**看见**的（头尾各 3 字符），
                    // 所以这种情况下不能用密码圆点，否则等于什么都没显示。
                    // 用户点右边的「重填」清空后，自动切回圆点模式。
                    visualTransformation = if (showingMask) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = if (showingMask) {
                        {
                            IconButton(onClick = { apiKey = "" }) {
                                Icon(Icons.Default.Edit, "重新填写")
                            }
                        }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = {
                        Hint(template.keyHint.ifBlank { ProviderDefaults.Hints.API_KEY })
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (keyConfigured) {
                    Text(
                        if (showingMask) {
                            "这是已保存 Key 的缩略显示（中间打码，不是完整密钥）。" +
                                "想换一把就点右边铅笔图标重填；只是改模型名的话不用动它。"
                        } else {
                            "将替换已保存的 Key；留空则保持原样。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp)
                    )
                }

                Text("图片质量", style = MaterialTheme.typography.bodySmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AiProviderConfig.ImageQuality.entries.forEach { q ->
                        FilterChip(
                            selected = quality == q, onClick = { quality = q },
                            label = {
                                Text(
                                    when (q) {
                                        AiProviderConfig.ImageQuality.HIGH -> "高"
                                        AiProviderConfig.ImageQuality.MEDIUM -> "中"
                                        AiProviderConfig.ImageQuality.LOW -> "省流量"
                                    }
                                )
                            }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = timeout, onValueChange = { timeout = it.filter { c -> c.isDigit() } },
                        label = { Text("超时(秒)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = retries, onValueChange = { retries = it.filter { c -> c.isDigit() } },
                        label = { Text("重试次数") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    ProviderDefaults.Hints.TIMEOUT + " " + ProviderDefaults.Hints.RETRIES,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ---- 连通性测试 ----
                HorizontalDivider(Modifier.padding(top = 4.dp))
                Text(
                    "连通性测试（真发一次请求，约 1500 token —— 图片占大头，花费可忽略）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            onTest(
                                kind,
                                baseUrl,
                                model,
                                timeout.toIntOrNull() ?: 30,
                                // 显示的是掩码 → 当「没填」，让测试回落到已保存的真 Key
                                if (showingMask) null else apiKey.ifBlank { null }
                            )
                        },
                        enabled = testState !is MainViewModel.TestState.Running
                    ) {
                        if (testState is MainViewModel.TestState.Running) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("测试中…")
                        } else {
                            Text("测试连通性")
                        }
                    }
                    if (testState is MainViewModel.TestState.Done) {
                        TextButton(onClick = onClearTest) { Text("清除结果") }
                    }
                }
                if (apiKey.isBlank() && keyConfigured) {
                    Text(
                        "将使用已保存的 API Key 测试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (apiKey.isBlank() && !keyConfigured) {
                    Text(
                        "还没填 API Key，测试会直接告诉你缺这个。",
                        style = MaterialTheme.typography.bodySmall,
                        color = LedgerColors.Warn
                    )
                }

                when (val ts = testState) {
                    is MainViewModel.TestState.Done -> TestReportCard(ts.report)
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    name, kind, baseUrl, model, role,
                    // 显示的是掩码 → 用户没改过这个框，传 null 表示「不修改」。
                    // （ViewModel 里还会再判一次，双保险：万一哪天改了调用方式，
                    //  也不会把 `ark***e9b` 这种掩码串当成真 Key 存进去。）
                    if (showingMask) null else apiKey.ifBlank { null },
                    timeout.toIntOrNull() ?: 240,
                    retries.toIntOrNull() ?: 1,
                    quality, 0.0
                )
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 输入框下方的短说明。
 *
 * 统一走这个组件，是为了让所有说明的**字号、颜色、左边距**保持一致 ——
 * `supportingText` 默认会自带 Material 的缩进和配色，但手写的地方（如超时那一行）
 * 如果不手动对齐就会跟它错位，看起来像两套界面。
 * 左边距 14dp 是 Material 3 输入框内文本的起始位置，加 `onSurfaceVariant` 灰，
 * 视觉上就自然地成了「这一格」的注解。
 */
@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 测试结果卡片。
 *
 * 展示原则：**先给结论，再给过程**。
 * 少爷不需要读一堆 HTTP 细节才知道通没通，所以最上面一行就是大字「可用 / 有问题」，
 * 下面才是分级步骤（地址 / 网络 / 模型）和原始报错。
 */
@Composable
private fun TestReportCard(report: com.bro.lotteryledger.ai.ConnectivityProbe.Report) {
    val okColor = LedgerColors.Spend
    val badColor = MaterialTheme.colorScheme.error

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (report.ok) okColor.copy(alpha = 0.12f) else badColor.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                if (report.ok) "✅ 配置可用" else "❌ 配置有问题",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (report.ok) okColor else badColor
            )

            report.steps.forEach { s ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        if (s.ok) "·" else "×",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (s.ok) okColor else badColor,
                        modifier = Modifier.width(14.dp)
                    )
                    Text(
                        "[${s.stage.display}] ${s.detail}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Text(
                buildString {
                    append("HTTP ${report.httpStatus ?: "—"} · ${report.latencyMs} ms")
                    report.tokens?.let { append(" · ${it} token") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!report.replyPreview.isNullOrBlank()) {
                Text(
                    "模型回应：${report.replyPreview.take(120)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!report.error.isNullOrBlank()) {
                HorizontalDivider()
                Text(
                    report.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = badColor
                )
            }
        }
    }
}
