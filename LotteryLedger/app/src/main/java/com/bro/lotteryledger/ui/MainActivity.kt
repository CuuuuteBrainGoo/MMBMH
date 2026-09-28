package com.bro.lotteryledger.ui

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bro.lotteryledger.core.JackpotTease
import com.bro.lotteryledger.core.Notice
import com.bro.lotteryledger.core.NoticeKind
import com.bro.lotteryledger.core.ThemeMode
import kotlinx.coroutines.delay

/**
 * 单 Activity + Compose 导航。
 *
 * 使用一个手写的简易路由，不引入 Navigation 库 ——
 * 本阶段页面只有 4 个，用 sealed class + when 比配 NavHost 图更短更直观。
 * ponytail: 页面一旦超过 8 个（第三阶段会加批量导入、备份等）就换成 Navigation Compose。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ⚠️ 崩溃屏**必须在 setContent 之前、且用原生 View** 弹。
        //
        // 起因（2026-09-27）：v1.5.0 点图标闪退，而拿不到任何日志。
        // 如果崩溃原因跟 Compose 主题/组合有关（1.5.0 恰好大改了主题系统），
        // 那么把崩溃屏写在 Compose 里就会**跟着一起崩**，等于没做。
        // 原生 AlertDialog 不碰 MaterialTheme、不碰我们的 LedgerColors，
        // 是这条链路上最不容易被同一原因带崩的选择。
        showLastCrashIfAny()

        setContent {
            // 主题要等 ViewModel 把用户选的 [ThemeMode] 读出来再定，
            // 所以这里先渲染，主题本身在 [LedgerRoot] 里包（它才拿得到 vm）。
            LedgerRoot()
        }
    }

    /**
     * 上次启动崩过的话，把堆栈摆到用户面前。
     *
     * 三个动作：**复制 / 分享 / 知道了**。少爷要的是「发给我」，
     * 所以「复制」和「分享」都得给 —— 分享可以甩进微信，复制可以贴进对话。
     *
     * ## 2026-09-27 第 2 轮修正：必须说清「这是哪次崩的」
     *
     * 少爷升级到修复版后反馈「**每次打开都弹这个**」——
     * 实际是升级前那次崩溃留下的记录文件还在（他看没点按钮就退出了，
     * 而按钮才会清文件），更关键的是**弹窗里完全没写这条记录来自哪个版本**，
     * 所以他没法判断「这是刚崩的还是老账」。
     *
     * 现在顶部按版本号分两种说法：
     *  - **记录版本 == 当前版本** → 「刚崩了」，语气按真问题来
     *  - **记录版本 != 当前版本** → 「这是 xx 的旧记录，当前版本已修复」，
     *    并说明可以直接关掉。既不丢线索，也不制造恐慌。
     */
    private fun showLastCrashIfAny() {
        val crash = (application as? com.bro.lotteryledger.LedgerApp)?.lastCrash ?: return
        val recordVer = com.bro.lotteryledger.core.CrashBeacon.versionOf(crash)
        val nowVer = com.bro.lotteryledger.BuildConfig.VERSION_NAME
        val isOld = recordVer != null && recordVer != nowVer

        // 消息很长，必须可滚动，否则末尾的关键堆栈看不到。
        // 所以不用 setMessage（它在长文本时会被截断且不能滚），自己塞一个 ScrollView。
        val content = android.widget.ScrollView(this).apply {
            setPadding(dp(18), dp(8), dp(18), dp(8))
            addView(
                android.widget.TextView(this@MainActivity).apply {
                    text = crash
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = 10f
                    setTextIsSelectable(true)
                }
            )
        }

        val tip = android.widget.TextView(this).apply {
            text = if (isOld) {
                "⚠️ 这是【旧版本 $recordVer】留下的记录，当前版本 $nowVer 已经修好了这个问题。\n" +
                    "点「知道了」关掉即可，之后不会再弹。\n" +
                    "（留着是为了万一还需要它。想发给开发者就点「分享」或「复制」。）"
            } else {
                "上面这段就是崩溃原因。请「分享」或「复制」后发给开发者，" +
                    "能直接定位到代码哪一行。关掉这个框后 App 可以正常用。"
            }
            setPadding(dp(18), dp(14), dp(18), 0)
        }

        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle(if (isOld) "旧版本的崩溃记录" else "上次打开时出了点问题")
            .setView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(tip)
                addView(
                    content,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(320)
                    )
                )
            })
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("崩溃日志", crash))
                android.widget.Toast.makeText(this, "已复制，去粘贴给开发者", android.widget.Toast.LENGTH_LONG).show()
                com.bro.lotteryledger.core.CrashBeacon.clear(this)
            }
            .setNeutralButton("分享") { _, _ ->
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "彩票账本崩溃记录")
                    putExtra(android.content.Intent.EXTRA_TEXT, crash)
                }
                try {
                    startActivity(android.content.Intent.createChooser(intent, "发送崩溃记录"))
                } catch (_: Exception) {
                }
                com.bro.lotteryledger.core.CrashBeacon.clear(this)
            }
            .setNegativeButton(if (isOld) "知道了，不再提示" else "知道了") { _, _ ->
                com.bro.lotteryledger.core.CrashBeacon.clear(this)
            }
            .setCancelable(false)
            .create()

        try {
            dlg.show()
        } catch (_: Exception) {
            // 弹不出来也不能影响 App 启动 —— 至少日志里已经有记录了
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

sealed interface Route {
    data object Home : Route
    data object Settings : Route
    data object Capture : Route
    data object Logs : Route
    /** 分彩种 / 分期号统计报表（少爷 2026-09-27 第 5 条） */
    data object Reports : Route

    /** AI 配置帮助页（少爷 2026-09-27 第 3 条）。 */
    data object Help : Route
}

/**
 * [Route] 的保存器 —— 只为扛住 **Activity 重建**（系统切深浅色、语言、屏幕旋转）。
 *
 * 存的是**名字字符串**而不是序号：将来往枚举中间插一个新页面时，
 * 序号方案会让「恢复出来的下标」指到别的页面；名字方案不会。
 * 认不出来的名字一律落回 [Route.Home]（比如降级安装后老进程恢复）。
 */
private val RouteSaver = Saver<Route, String>(
    save = { r ->
        when (r) {
            Route.Home -> "home"
            Route.Settings -> "settings"
            Route.Capture -> "capture"
            Route.Logs -> "logs"
            Route.Reports -> "reports"
            Route.Help -> "help"
        }
    },
    restore = { name ->
        when (name) {
            "settings" -> Route.Settings
            "capture" -> Route.Capture
            "logs" -> Route.Logs
            "reports" -> Route.Reports
            "help" -> Route.Help
            else -> Route.Home
        }
    }
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerRoot(vm: MainViewModel = viewModel()) {
    // 主题模式（浅色 / 深色 / 跟随系统）—— 少爷 2026-09-27 第 6 条。
    // 「跟随系统」要靠 isSystemInDarkTheme() 才定得下来，所以最终深浅在这里算。
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    // 皮肤（墨金账本 / 支付宝 / 微信）—— 少爷 2026-09-27。
    // 它跟深浅色是**两个正交维度**，所以各自一个状态，不合并成一个列表。
    val skin by vm.skin.collectAsStateWithLifecycle()
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    LedgerTheme(darkTheme = dark, skin = skin) {
        LedgerRootContent(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerRootContent(vm: MainViewModel) {
    // ⚠️ 必须用 `rememberSaveable`，不能用 `remember`。
    //
    // 起因（2026-09-27）：少爷要求「深浅色跟随系统要做成实际的功能」。
    // 跟随系统的实现是 `isSystemInDarkTheme()`，它会随系统切深浅色而变 ——
    // 而**系统切深浅色时会重建 Activity**（manifest 里没声明 `configChanges="uiMode"`）。
    // Activity 一重建，`remember` 的值全丢：用户在**设置页**点系统深色开关，
    // 会被直接弹回首页。那看起来就是"跟随系统有 bug"，而不是"功能正常"。
    //
    // Route 全是 data object，没有自带 Saver，所以按下标存：
    // 存的是**枚举序号**，将来往 Route 中间插新页面会让老进程恢复时跳错页 ——
    // 但这个窗口只在"进程被杀后重建"时存在，且跳错也只是落回某个页面不是崩溃，
    // 比"切个深色就丢当前页"划算得多。
    var route by rememberSaveable(stateSaver = RouteSaver) {
        mutableStateOf<Route>(Route.Home)
    }

    // ⛔ 首页列表的滚动位置必须**在这里**持有，不能放进 HomeScreen 内部。
    //
    // 起因（2026-09-27 少爷报）：从首页下滑 → 点一张票进详情 → 返回，
    // 位置被弹回顶部，得重新滑一遍。
    //
    // 根因：详情页**不是 Route**，而是 `detail != null` 这个独立状态，
    // 在下面那个 `when` 里优先级高于 route。所以进详情时 HomeScreen
    // 会被**整个移出组合树** —— 它内部的 `rememberLazyListState()` 跟着销毁，
    // 返回时重新创建，位置自然归零。
    //
    // 修法：state 提到这一层（组合树里**始终存在**的地方），HomeScreen 只负责渲染。
    //
    // 用 rememberSaveable + LazyListState.Saver：既扛住「进详情返回」，
    // 也扛住「Activity 重建」（系统切深浅色 / 旋转屏）——
    // 跟 [route] 用 rememberSaveable 是同一个理由。
    val homeListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }
    val home by vm.home.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    // 「号码撞大奖」彩蛋（平时为 null）
    val tease by vm.tease.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val redeeming by vm.redeeming.collectAsStateWithLifecycle()
    val wipe by vm.wipe.collectAsStateWithLifecycle()
    val backupState by vm.backupState.collectAsStateWithLifecycle()
    val batch by vm.batch.collectAsStateWithLifecycle()

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val activity = ctx as? android.app.Activity

    // 相册选择（§3.1：只读，不删用户原照片）
    val imageOnlyRequest = remember {
        androidx.activity.result.PickVisualMediaRequest(
            ActivityResultContracts.PickVisualMedia.ImageOnly
        )
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            vm.scanUri(uri)
            route = Route.Home
        }
    }

    // 批量导入（§8）：一次选多张，逐张识别。
    // 上限 30 是拍的：一次选太多核不过来，而且识别本身要几分钟。
    // 不用 getPickImagesMaxLimit() 查系统上限 —— 它在当前 activity 版本里取不到，
    // 而 30 远低于任何平台的上限（一般 100~150），安全。
    val batchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris ->
        if (uris.isNotEmpty()) {
            vm.scanUris(uris)
            route = Route.Home
        }
    }

    // 导出备份：用系统文件选择器让用户决定存哪（可以存到网盘，这样手机丢了也还在）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }

    // 恢复备份：选文件。mime 给 */* —— 有些文件管理器把 .json 报成 octet-stream，
    // 限死 application/json 会让备份文件在选择器里变灰选不中。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.previewImport(uri)
    }

    // ---- 返回键统一处理 ----
    // 顺序：核对页 → 关掉；子页面 → 回首页；已在首页 → 双击退出
    var lastBackAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = true) {
        when {
            // 清空确认框开着 → 返回键 = 取消这次清空。
            // 放最前面：确认框盖在整个设置页上，返回键的直觉含义是「关掉这个框」，
            // 不该顺势把设置页也一起退掉。
            wipe.step > 0 -> vm.cancelWipe()
            // 核对页有未保存内容，先问一句（由 ReviewScreen 内部分支处理不了，这里统一挡）
            pending != null -> vm.askDiscard()
            // 票详情 → 关掉详情回首页
            detail != null -> vm.closeTicket()
            // 正在识别 → 返回键当「取消」用，别让它走到「再按一次退出」
            scan is MainViewModel.ScanState.Working -> vm.cancelScan()
            route != Route.Home -> route = Route.Home
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000) {
                    activity?.finish()
                } else {
                    lastBackAt = now
                    vm.notify("再按一次返回退出")
                }
            }
        }
    }

    LaunchedEffect(route) {
        if (route == Route.Settings) vm.loadProviders()
    }

    // 有识别结果 → 直接切到核对界面
    LaunchedEffect(pending) {
        if (pending != null) route = Route.Home
    }

    // 提示条：**居中**显示 + 按类型上色（少爷 2026-09-27 报的问题）
    //
    // 以前用的是 Snackbar，贴着屏幕底部 ——
    //   1. 会盖住「继续核验」「录入彩票」这些底部按钮，点不着；
    //   2. 中奖 / 未中奖 / 过期都是同一个灰黑块，只能逐字读。
    // 现在挪到屏幕正中，并且按 NoticeKind 上色。它**不吃点击事件**
    // （没有一个 pointerInput），所以就算弹着也不挡底下任何按钮。
    LaunchedEffect(toast) {
        val n = toast ?: return@LaunchedEffect
        delay(if (n.kind == NoticeKind.NEUTRAL) 1_800L else 2_600L)
        vm.clearToast()
    }

    Box(Modifier.fillMaxSize()) {
        when {
            // 核对界面优先（有 pending 时盖住其它页面）
            pending != null -> {
                ReviewScreen(
                    entry = pending!!,
                    onChange = { vm.updatePending(it) },
                    onConfirmDistinct = { vm.confirmDistinct() },
                    onCommit = { vm.commitPending { vm.refreshAll() } },
                    onDiscard = { vm.discardPending() },
                    // 判重提示里的「#47」是数据库 id，用户看不懂 ——
                    // 换成首页列表上的展示编号，用户能直接对上号。
                    formatReason = { r, id -> vm.duplicateReasonText(r, id) },
                    onBack = { vm.askDiscard() }
                )
            }

            // 票详情
            detail != null -> {
                TicketDetailScreen(
                    detail = detail!!,
                    checking = checking,
                    onBack = { vm.closeTicket() },
                    onCheck = { vm.checkTicketNow(detail!!.ticket.id) },
                    onConfirmAmount = { amt -> vm.confirmPrizeAmount(detail!!.ticket.id, amt) },
                    onRedeem = { amt -> vm.markRedeemed(detail!!.ticket.id, amt) },
                    onMarkNotWon = { vm.markNotWonManually(detail!!.ticket.id) },
                    onMarkWon = { amt -> vm.markWonManually(detail!!.ticket.id, amt) }
                )
            }

            route == Route.Capture -> {
                CaptureScreen(
                    onCaptured = { bmp: Bitmap ->
                        vm.scanBitmap(bmp)
                        route = Route.Home
                    },
                    onPickGallery = { galleryLauncher.launch(imageOnlyRequest) },
                    onBatch = { batchLauncher.launch(imageOnlyRequest) },
                    onManual = {
                        vm.startManualEntry()
                        route = Route.Home
                    },
                    onBack = { route = Route.Home }
                )
            }

            route == Route.Settings -> {
                val testState by vm.test.collectAsStateWithLifecycle()
                val thinkingLevel by vm.thinkingLevel.collectAsStateWithLifecycle()
                val themeMode by vm.themeMode.collectAsStateWithLifecycle()
                val skin by vm.skin.collectAsStateWithLifecycle()
                // 只在「跟随系统」时需要它，但无条件读也没成本 ——
                // isSystemInDarkTheme() 本来就是配置变化的订阅者
                val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
                SettingsScreen(
                    providers = providers,
                    hasKey = { vm.hasKey(it) },
                    maskedKey = { vm.maskedKey(it) },
                    versionName = com.bro.lotteryledger.BuildConfig.VERSION_NAME,
                    versionCode = com.bro.lotteryledger.BuildConfig.VERSION_CODE,
                    thinkingLevel = thinkingLevel,
                    onThinkingLevel = { vm.setThinkingLevel(it) },
                    themeMode = themeMode,
                    onThemeMode = { vm.setThemeMode(it) },
                    systemDark = systemDark,
                    skin = skin,
                    onSkin = { vm.setSkin(it) },
                    testState = testState,
                    onTest = { e, k, u, m, t, key -> vm.testProvider(e, k, u, m, t, key) },
                    onClearTest = { vm.clearTest() },
                    onSave = { e, n, k, u, m, r, key, t, ret, q, temp ->
                        vm.saveProvider(e, n, k, u, m, r, key, t, ret, q, temp)
                    },
                    onDelete = { vm.deleteProvider(it) },
                    onOpenLogs = { route = Route.Logs },
                    onOpenHelp = { route = Route.Help },
                    onExportBackup = {
                        exportLauncher.launch("彩票账本备份-${java.time.LocalDate.now()}.json")
                    },
                    onImportBackup = { importLauncher.launch(arrayOf("*/*")) },
                    backupWorking = backupState is MainViewModel.BackupState.Working,
                    ticketCount = home.tickets.size,
                    wipe = wipe,
                    onWipeTap = { vm.tapWipeEntry() },
                    onWipeAdvance = { vm.advanceWipe() },
                    onWipeConfirm = { vm.confirmWipe() },
                    onWipeCancel = { vm.cancelWipe() },
                    onBack = { route = Route.Home }
                )
            }

            route == Route.Logs -> {
                LogScreen(onBack = { route = Route.Home })
            }

            route == Route.Reports -> {
                ReportsScreen(
                    tickets = home.tickets,
                    onBack = { route = Route.Home }
                )
            }

            route == Route.Help -> {
                HelpScreen(onBack = { route = Route.Settings })
            }

            else -> {
                HomeScreen(
                    state = home,
                    checking = checking,
                    redeeming = redeeming,
                    listState = homeListState,
                    onScan = {
                        // 拍照页里还有「手工填写」，那条路不需要 AI。
                        // 所以没配平台也**必须放行** —— 之前一律跳设置页，
                        // 等于把唯一不依赖 AI 的功能也一起锁了。
                        val noProvider = providers.none {
                            it.role == "primary" || it.role == "both"
                        }
                        route = Route.Capture
                        if (noProvider) {
                            vm.notify("拍照识别需先配 AI 平台；也可直接用「手工填写」")
                        }
                    },
                    onSettings = { route = Route.Settings },
                    onLogs = { route = Route.Logs },
                    onReports = { route = Route.Reports },
                    onCheckAll = { vm.checkAllPending() },
                    onRedeemAll = { vm.redeemAllChecked() },
                    onFilterChange = { vm.setFilter(it) },
                    onReviewDrafts = { vm.startReviewingDrafts() },
                    onTicketClick = { vm.openTicket(it.id) }
                )
            }
        }

        // 识别中的遮罩
        if (scan is MainViewModel.ScanState.Working) {
            WorkingOverlay(
                step = (scan as MainViewModel.ScanState.Working).step,
                onCancel = { vm.cancelScan() }
            )
        }

        // 批量识别的进度 / 结果（§8）
        when (val b = batch) {
            is MainViewModel.BatchState.Running -> BatchProgressOverlay(b) { vm.cancelBatch() }
            is MainViewModel.BatchState.Done -> BatchResultDialog(
                st = b,
                onAutoCommit = { vm.autoCommitClean() },
                onReview = { vm.startBatchReview() },
                onDismiss = { vm.dismissBatch() }
            )
            MainViewModel.BatchState.Idle -> Unit
        }

        if (scan is MainViewModel.ScanState.Failed) {
            val msg = (scan as MainViewModel.ScanState.Failed).message
            AlertDialog(
                onDismissRequest = { vm.resetScan() },
                title = { Text("识别失败") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(msg)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "详细原因已记入日志，可在首页右上角「日志」里查看或导出。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.resetScan()
                        route = Route.Logs
                    }) { Text("看日志") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.resetScan() }) { Text("知道了") }
                }
            )
        }

        // 丢弃核对待入账的票的二次确认
        val confirmDiscard by vm.confirmDiscard.collectAsStateWithLifecycle()
        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { vm.cancelDiscard() },
                title = { Text("放弃这张票？") },
                text = { Text("刚识别/修改的内容还没入账。放弃后需要重新拍照识别。") },
                confirmButton = {
                    TextButton(onClick = { vm.discardPending() }) { Text("放弃") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.cancelDiscard() }) { Text("继续核对") }
                }
            )
        }

        // ---- 备份与恢复（§17）----

        // 处理中：不给取消按钮。写到一半中断可能留下半个文件，
        // 而这个操作本来就只要一两秒。
        if (backupState is MainViewModel.BackupState.Working) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("正在处理备份…") },
                text = { Text("请稍等，别退出 App。数据量不大，通常一两秒就好。") },
                confirmButton = { }
            )
        }

        // 恢复前的预览：先让用户看清「会新增几张、跳过几张」，再动手
        if (backupState is MainViewModel.BackupState.Preview) {
            val info = (backupState as MainViewModel.BackupState.Preview).info
            AlertDialog(
                onDismissRequest = { vm.cancelImport() },
                title = { Text("恢复这个备份？") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text("备份里有 ${info.total} 张票", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "· 会新增 ${info.newTickets} 张",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LedgerColors.Prize
                        )
                        Text(
                            "· 本机已有、会跳过 ${info.duplicates} 张",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "· 开奖结果 ${info.drawResults} 期",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "备份来自 App ${info.appVersion}，导出时间 ${fmtTs(info.exportedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "恢复是「合并」：不会删除、也不会覆盖你本机已有的账目。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (info.newTickets == 0 && info.total > 0) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "这些票本机全都有了，恢复不会带来任何变化。",
                                style = MaterialTheme.typography.bodySmall,
                                color = LedgerColors.Warn
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { vm.confirmImport() },
                        enabled = info.newTickets > 0
                    ) { Text("开始恢复") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.cancelImport() }) { Text("取消") }
                }
            )
        }

        // 提示条画在最上层，但不参与点击命中 —— 底下的按钮照样能按
        toast?.let { NoticeOverlay(it) }

        // 「号码撞大奖」彩蛋 —— 平时永远是 null，只有往期号码撞上本期开奖号码才会出现
        tease?.let { TeaseOverlay(it) { vm.dismissTease() } }
    }
}

/**
 * 「号码撞大奖」调侃浮层。
 *
 * 少爷的原话：
 * > 提示用户，以前购买过的一个号码意外和本期的大奖号码一样，
 * > **并不是真的中了本期的大奖**。跟用户开个小玩笑。
 *
 * 所以这个界面**通篇不许出现任何像"中奖"的表述** ——
 * 它必须一眼就看得出是玩笑，否则就成了假好消息，比不弹还糟。
 * 配色也刻意用紫调（不是中奖红、不是待确认金）。
 *
 * ## 少爷 2026-09-27 第 4 条的四个改动
 *
 *  1. **更醒目** —— 底部铺满紫色光晕 + 抖动的大 emoji + 更重的标题字号，
 *     不再是「一个圆角方块静静躺在中间」。
 *  2. **更宽大** —— 最大宽度从 460dp 提到 520dp，内边距加大，
 *     号码那一行单独做成一块高对比的「票根」。
 *  3. **3 次确认才关** —— 以前点哪儿都关，玩笑一眼就滑过去了。
 *     现在必须**连点 3 次**同一颗按钮，第 3 次才真的关；按钮文案也跟着数数
 *     （第 1、2 次是「再想想」「还不服？」，第 3 次才放他走）。
 *  4. **话术更夸张** —— 池子从 5 条扩到 20 条，随机取（见 [JackpotTease]）。
 *
 * 注意：点浮层**空白处不再关闭** —— 想关就必须去按那颗按钮按满 3 次。
 * 这是「3 次确认」的应有之义：随手一碰就走，等于没有确认。
 */
@Composable
private fun TeaseOverlay(t: JackpotTease.Tease, onDismiss: () -> Unit) {
    // 点了几次关闭按钮（满 3 次才真的关）
    var taps by remember(t) { mutableStateOf(0) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = LedgerColors.Tease,
            contentColor = Color.White,
            shape = RoundedCornerShape(26.dp),
            shadowElevation = 24.dp,
            tonalElevation = 8.dp,
            modifier = Modifier
                .padding(horizontal = 18.dp)
                .widthIn(max = 520.dp)
        ) {
            Column(
                Modifier.padding(horizontal = 26.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 大 emoji + 轻微摇摆，纯装饰，不吃点击
                val wobble = remember { Animatable(0f) }
                LaunchedEffect(t) {
                    repeat(6) {
                        wobble.animateTo(1f, tween(90))
                        wobble.animateTo(0f, tween(90))
                    }
                }
                Text(
                    "😏",
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.graphicsLayer {
                        rotationZ = (wobble.value - 0.5f) * 24f
                        scaleX = 1.7f
                        scaleY = 1.7f
                    }
                )

                Spacer(Modifier.height(26.dp))
                Text(
                    t.punchline,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    lineHeight = MaterialTheme.typography.headlineMedium.fontSize * 1.35
                )

                Spacer(Modifier.height(22.dp))
                // 号码做成一块"票根"。
                //
                // ⚠️ 这里的底色**故意不走主题**：整个浮层的底是深紫（[LedgerColors.Tease]），
                // 浮层本身就不跟皮肤/深浅色走 —— 它是彩蛋，不是业务界面。
                // 所以票根用**淡紫纸**#EFE9F7 配深紫字，跟浮层同色系；
                // 用纯白会在深紫底上过曝，像贴了块反光板。
                Surface(
                    color = Color(0xFFEFE9F7),
                    contentColor = Color(0xFF241B4A),
                    shape = RoundedCornerShape(14.dp),
                    shadowElevation = 6.dp
                ) {
                    Column(
                        Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            t.numberLine,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "第 ${t.latestIssue} 期的开奖号码",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.92f)
                )

                Spacer(Modifier.height(20.dp))
                Text(
                    buildString {
                        append("而你在第 ${t.pastIssue} 期买过一模一样的号码")
                        if (t.daysBetween > 0) append("（${t.daysBetween} 天前）")
                        append("。\n")
                        append("那一期它什么也没中 —— 账本上的状态一个字都没变，这只是个玩笑。")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.92f)
                )

                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = {
                        if (taps >= 2) {
                            // 第 3 次：放他走
                            onDismiss()
                        } else {
                            taps += 1
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = LedgerColors.Tease
                    ),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(
                        when (taps) {
                            0 -> "扎心（1/3）"
                            1 -> "再想想（2/3）"
                            else -> "好吧，我走（3/3）"
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "连点 3 次才放你走 —— 这种玩笑值得多看一眼。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * 屏幕居中的提示条。
 *
 * ⚠️ 这里**故意不给它任何点击/手势修饰符** —— Compose 里没有 pointerInput 的组件
 * 不会参与命中测试，事件会直接穿透到下面的按钮。要是手滑加了 `clickable {}`，
 * 它就会重新变成一堵挡按钮的墙，那就白改了。
 */
@Composable
private fun NoticeOverlay(notice: Notice) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(notice) { appear.animateTo(1f, tween(durationMillis = 130)) }

    Box(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = noticeBackground(notice.kind),
            contentColor = LedgerColors.NoticeText,
            shape = RoundedCornerShape(14.dp),
            shadowElevation = 12.dp,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .graphicsLayer { alpha = appear.value; scaleX = 0.94f + 0.06f * appear.value; scaleY = 0.94f + 0.06f * appear.value }
        ) {
            Text(
                notice.text,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)
            )
        }
    }
}

/**
 * 提示类型 → 底色。红=中奖 / 绿=未中奖 / 黄=过期待处理 / 暗橙=失败 / 深灰=普通。
 *
 * 2026-09-27：`LedgerColors.X` 从常量改成了 `@Composable` 转发（皮肤/深浅色要靠它
 * 随组合变化），所以这个函数必须带 `@Composable` —— 否则编译期就报
 * "Functions which invoke @Composable functions must be marked with the @Composable annotation"。
 * 凡是「常量表 → Color」的转换函数，都要跟着带上标注。
 */
@Composable
private fun noticeBackground(kind: NoticeKind): Color = when (kind) {
    NoticeKind.WIN -> LedgerColors.Prize
    NoticeKind.LOSE -> LedgerColors.Expired
    NoticeKind.WARN -> LedgerColors.Warn
    NoticeKind.ERROR -> LedgerColors.NoticeError
    NoticeKind.NEUTRAL -> LedgerColors.NoticeNeutral
}

/** 时间戳 → `yyyy-MM-dd HH:mm`（备份导出时间用）。 */
private fun fmtTs(ts: Long): String {
    if (ts <= 0L) return "未知"
    val c = java.util.Calendar.getInstance().apply { time = java.util.Date(ts) }
    return "%04d-%02d-%02d %02d:%02d".format(
        c.get(java.util.Calendar.YEAR),
        c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.DAY_OF_MONTH),
        c.get(java.util.Calendar.HOUR_OF_DAY),
        c.get(java.util.Calendar.MINUTE)
    )
}

/**
 * 批量识别进度（§8）。
 *
 * 必须**可见地**显示「第 N/M 张」和成功/失败数 —— 13 张票要跑一分多钟，
 * 没有进度的等待就是折磨（这个教训在单张识别上已经吃过一次：
 * 用户看到一句静态文字，分不清在跑还是卡死）。
 */
@Composable
private fun BatchProgressOverlay(
    st: MainViewModel.BatchState.Running,
    onCancel: () -> Unit
) {
    val fraction = if (st.total > 0) st.index.toFloat() / st.total else 0f
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("正在批量识别", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(18.dp))

            if (st.index > 0) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(14.dp))
            Text(
                if (st.index > 0) "第 ${st.index} / ${st.total} 张" else "准备中…",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "成功 ${st.ok}",
                    style = MaterialTheme.typography.titleMedium,
                    color = LedgerColors.Prize
                )
                Text(
                    "失败 ${st.failed}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 「跳过」单独显示：这些多半是选错的照片（桌面、地面），
                // 混进「失败」里会让人以为功能坏了
                if (st.skipped > 0) {
                    Text(
                        "跳过 ${st.skipped}",
                        style = MaterialTheme.typography.titleMedium,
                        color = LedgerColors.Warn
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "逐张识别，每张几秒到几十秒。\n" +
                    "中途退出也不丢 —— 已识别的票会留着等你核对。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))
            TextButton(onClick = onCancel) { Text("取消（保留已识别的）") }
        }
    }
}

/**
 * 批量识别结果（§8 的「批量完成页」）。
 *
 * 按交接稿的要求分三类显示：**正常 / 疑似重复 / 需确认**，
 * 并给出「正常票直接入账」的快捷路径 —— 11 张干净的票不该逼用户点 11 次。
 */
@Composable
private fun BatchResultDialog(
    st: MainViewModel.BatchState.Done,
    onAutoCommit: () -> Unit,
    onReview: () -> Unit,
    onDismiss: () -> Unit
) {
    val tri = st.triage
    val hasClean = tri.clean > 0
    val nothingRecognized = st.ok == 0

    // 主操作是「直接入账」（有干净票时），次操作才是逐张核对
    val dismissBtn: (@Composable () -> Unit)? = if (hasClean) {
        { TextButton(onClick = onReview) { Text("逐张核对") } }
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    st.canceled -> "已取消批量识别"
                    nothingRecognized -> "这批都没识别出来"
                    else -> "批量识别完成"
                }
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("共选了 ${st.total} 张")
                Spacer(Modifier.height(8.dp))
                Text(
                    "· 识别成功 ${st.ok} 张",
                    color = if (st.ok > 0) LedgerColors.Prize
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (st.failed > 0) {
                    Text("· 识别失败 ${st.failed} 张", color = MaterialTheme.colorScheme.error)
                }
                // 「不像彩票」单独一条：这些是选错的图（桌面/地面），
                // 不用重拍、也不用管，说清楚免得用户去折腾
                if (st.skipped > 0) {
                    Text(
                        "· 看着不像彩票 ${st.skipped} 张（已跳过，没花调用）",
                        color = LedgerColors.Warn
                    )
                }

                if (st.ok > 0) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    if (tri.clean > 0) {
                        Text("· 可以直接入账 ${tri.clean} 张", color = LedgerColors.Prize)
                    }
                    if (tri.review > 0) {
                        Text("· 需要你确认 ${tri.review} 张", color = LedgerColors.Warn)
                    }
                    if (tri.duplicate > 0) {
                        Text(
                            "· 好像重复 ${tri.duplicate} 张",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when {
                            tri.review == 0 && tri.duplicate == 0 ->
                                "这一批都没问题，直接入账就行。"
                            hasClean ->
                                "「直接入账」只处理没问题的那 ${tri.clean} 张，" +
                                    "其余留给你逐张看。"
                            else ->
                                "这一批都需要你看一眼，点「逐张核对」开始。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (st.canceled) "已识别好的票会留着 —— 首页那条提示可以点进去继续核对。"
                        else "可以重拍这几张，或者用「手工填写」直接补录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (hasClean) {
                TextButton(onClick = onAutoCommit) { Text("直接入账 ${tri.clean} 张") }
            } else {
                TextButton(onClick = if (st.ok > 0) onReview else onDismiss) {
                    Text(if (st.ok > 0) "逐张核对" else "知道了")
                }
            }
        },
        dismissButton = dismissBtn
    )
}

/**
 * 识别中的遮罩。
 *
 * 为什么要显示「已用秒数」：读票用的是推理模型，它要先生成思维链再给答案，
 * 单次 15~60 秒是**正常**的。之前只有一句静态的「正在识别票面…」，
 * 用户完全分不清「在跑」还是「卡死」，就会以为 App 出问题了。
 * 有了秒数，等待变成可感知的，并且能在真的太久时自己按「取消」。
 */
@Composable
private fun WorkingOverlay(step: String, onCancel: () -> Unit) {
    // 从进入遮挡层开始计时。key 用 Unit 保证跨步骤（加载图片 → 识别票面）计时不重置
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            kotlinx.coroutines.delay(1000)
            elapsed = (System.currentTimeMillis() - start) / 1000
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.88f),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(step, style = MaterialTheme.typography.bodyLarge)

            if (elapsed >= 2) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "已用 ${elapsed} 秒",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    elapsed >= 90 -> "比平时久。超时会自动报错，也可以点下面「取消」"
                    elapsed >= 30 -> "票面复杂时会慢一些，请再等等…"
                    else -> "正在读票面，通常 10～30 秒"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "照片仅在本机临时保存，识别完成后立即删除",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}
