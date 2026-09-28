package com.bro.lotteryledger.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bro.lotteryledger.ai.OpenAiCompatibleClient
import com.bro.lotteryledger.ai.RecognitionOrchestrator
import com.bro.lotteryledger.core.*
import com.bro.lotteryledger.db.AiProviderEntity
import com.bro.lotteryledger.db.StatsProjection
import com.bro.lotteryledger.db.TicketBetEntity
import com.bro.lotteryledger.db.TicketEntity
import com.bro.lotteryledger.image.ImagePipeline
import com.bro.lotteryledger.repo.LedgerRepository
import com.bro.lotteryledger.repo.PrizeNotice
import com.bro.lotteryledger.repo.TicketCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主 ViewModel。UI 的全部状态都在这里，Compose 只做渲染。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LedgerRepository(app)
    private val keys = com.bro.lotteryledger.security.ApiKeyStore(app)
    private val pipeline = ImagePipeline(app)
    private val client = OpenAiCompatibleClient()

    /** 对奖服务。声明位置必须在 init 之前 —— Kotlin 按声明顺序跑初始化器。 */
    private val prizeService = com.bro.lotteryledger.repo.PrizeService(app)

    // ---------------- 首页状态 ----------------

    data class HomeState(
        val loading: Boolean = true,
        val stats: StatsProjection? = null,
        /** 全部票。列表筛选在它上面做 */
        val tickets: List<TicketEntity> = emptyList(),
        /** 按 [filter] 过滤后的票，列表直接渲染它 */
        val visible: List<TicketEntity> = emptyList(),
        val filter: TicketFilter = TicketFilter.NONE,
        /**
         * [visible]（也就是筛选结果）的金额汇总（少爷 2026-09-27 要求）。
         *
         * 用它在主账卡上补一行「当前筛选：花 ¥X · 中 ¥Y · 净 ¥Z」——
         * 这样筛选的效果一眼可见，而主数字（[stats]）仍然是不变的全部账。
         *
         * 没筛选时它是 null（跟 [stats] 完全等价，多显示一行纯属噪音）。
         */
        val visibleStats: TicketStats? = null,
        val draftCount: Int = 0,
        val needsCheckCount: Int = 0,
        /**
         * 有几张票「查不到开奖结果」需要人工处理。
         *
         * 它们**不在** [needsCheckCount] 里（那个只算还会自动重试的），
         * 所以必须单独提示 —— 否则用户根本不知道有票需要自己去核对。
         */
        val unavailableCount: Int = 0,
        /** 有几张票已中奖、还没兑（首页「一键兑奖」用它决定要不要显示入口） */
        val toRedeemCount: Int = 0,
        /** 待兑奖金额合计（确认框里要让用户看到将入账多少，避免蒙着眼点） */
        val toRedeemAmount: Double = 0.0,
        val totalCalls: Long = 0,
        val totalTokens: Long = 0,
        /**
         * 「同一期同一彩种买了多张」的序号：`票 id → (第几张, 共几张)`。
         *
         * 同组只有一张的**不在**这个 map 里 —— 界面上只有需要区分时才标序号。
         */
        val issueSeq: Map<Long, Pair<Int, Int>> = emptyMap(),
        /**
         * 彩票**展示编号**：`票 id → 编号`（少爷 2026-09-27 要求）。
         *
         * 编号**越大越新**，列表最下面那张是 1、最上面那张等于总张数。
         *
         * ⚠️ 它一定按 [tickets]（**全量**）算，**不是** [visible]。
         * 少爷明确要求「这个编号在筛选时不变」—— 筛一次编号全变，
         * 用户刚记下的「第 12 张」就对不上了。
         */
        val ticketCodes: Map<Long, Int> = emptyMap(),

        /**
         * 几条提示里要带的编号清单（少爷 2026-09-27 要求：
         * 「如果其他功能有提示涉及具体某个彩票，可以附带编码，让用户更好定位」）。
         *
         * 存的是**已经算好的编号串**（形如 `#12、#9、#4`），不是 id 列表 ——
         * 让 UI 直接显示，不用再关心怎么从 id 换算编号。
         *
         * 每条都用 [TicketCode.join] 生成：编号从大到小排（跟列表顺序一致），
         * 超过 6 个就省略 —— 提示条塞不下几十个编号。
         */
        val pendingRedeemCodes: String = "",
        val unavailableCodes: String = "",
        val toConfirmCodes: String = ""
    )

    private val _home = MutableStateFlow(HomeState())
    val home: StateFlow<HomeState> = _home.asStateFlow()

    // ---------------- 识别状态 ----------------

    sealed interface ScanState {
        data object Idle : ScanState
        data class Working(val step: String, val progress: Pair<Int, Int>? = null) : ScanState
        data class Ready(
            val ticket: RawTicket,
            val validation: ValidationResult,
            val conflicts: List<String>,
            val reviewed: Boolean,
            val imageQuality: AiProviderConfig.ImageQuality
        ) : ScanState
        data class Failed(val message: String) : ScanState
    }

    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    /** 当前待入账的票（核对界面直接改它） */
    private val _pending = MutableStateFlow<PendingEntry?>(null)
    val pending: StateFlow<PendingEntry?> = _pending.asStateFlow()

    data class PendingEntry(
        val ticket: RawTicket,
        val validation: ValidationResult,
        val duplicate: Fingerprints.DuplicateVerdict,
        val conflicts: List<String>,
        /**
         * 冲突字段的实际值：路径 → (主模型值, 复核模型值)。
         * 用来把 `bets` 这种人看不懂的内部路径渲染成「注数：5 注 / 3 注」。
         *
         * 只在内存里传递 —— 草稿表存的是路径列表，恢复草稿时这里为空，
         * 那种情况下界面降级成只显示人话名称（没有对比值），不影响使用。
         */
        val conflictValues: Map<String, Pair<String?, String?>> = emptyMap(),
        val inputMethod: InputMethod,
        val imageHash: String?,
        /** 用户是否已确认「这是另一张实体票」 */
        val distinctConfirmed: Boolean = false,
        val recognizedStatus: RecognitionStatus,
        /**
         * 这张票对应的草稿 id。入账或放弃时据此**从草稿队列里删掉它**。
         *
         * 以前没有这个字段，导致草稿只进不出 —— 每次识别入账后草稿都留着，
         * 首页那条「有 N 笔草稿还没确认」永远不消失，而且越攒越多。
         */
        val draftId: Long? = null,
        /**
         * 是否来自批量核对队列。
         *
         * true 时，入账或放弃后**自动弹出下一张** ——
         * 少爷一次导入 13 张，不该每核对完一张就退出去再点一次。
         */
        val fromBatch: Boolean = false
    )

    // ---------------- 平台配置状态 ----------------

    private val _providers = MutableStateFlow<List<AiProviderEntity>>(emptyList())
    val providers: StateFlow<List<AiProviderEntity>> = _providers.asStateFlow()

    private val _toast = MutableStateFlow<Notice?>(null)
    val toast: StateFlow<Notice?> = _toast.asStateFlow()

    fun clearToast() { _toast.value = null }

    /**
     * 弹一条提示。
     *
     * [kind] 决定界面上的颜色（红=中奖 / 绿=未中奖 / 黄=过期待处理 / 暗橙=失败 / 深灰=普通）。
     * 不传就是普通提示 —— 老的调用点不用改。
     */
    fun notify(msg: String, kind: NoticeKind = NoticeKind.NEUTRAL) {
        _toast.value = Notice(msg, kind)
    }

    // ---------------- 「号码撞大奖」彩蛋（少爷 2026-09-27 第 4 条）----------------

    /**
     * 当前要调侃的那次「撞号」，null 表示没有。
     *
     * **平时永远是 null** —— 只有「往期买过的号码恰好等于本期一等奖号码」时才会被赋值。
     */
    private val _tease = MutableStateFlow<JackpotTease.Tease?>(null)
    val tease: StateFlow<JackpotTease.Tease?> = _tease.asStateFlow()

    fun dismissTease() { _tease.value = null }

    /**
     * 检查有没有「往期号码撞上本期大奖」的巧合，有就损他一句。
     *
     * ⚠️ **这不是中奖提示**。少爷的原话：
     * > 以前购买过的一个号码意外和本期的大奖号码一样，**并不是真的中了本期的大奖**。
     *
     * 所以本方法**不改任何票的状态、不动任何金额** —— 它只拿最新一期的开奖号码，
     * 跟库里往期的注比一比号码，撞上了就弹一句玩笑话。
     * 那张往期票在账上是什么状态就还是什么状态。
     *
     * 去重：同一个（彩种 + 本期期号）只调侃一次 —— 第一次是彩蛋，第二次就是烦。
     * 注意**只有真弹了才记**，没撞上不记 —— 这样以后新录入的老票还能再撞。
     *
     * ## 对比范围（少爷 2026-09-27 第 4 条）
     *
     * 只用 `latestDraw(type)` 拿到的**最新一期**开奖号码做靶子，
     * 然后跟**本机全部往期记录**比（`repo.allBetsForTease()` 不限期数）。
     * 本机存了多少期就比多少期 —— 跟「官方接口只返回 200 期」**无关**。
     */
    fun checkJackpotTease() {
        viewModelScope.launch {
            try {
                val bets = repo.allBetsForTease()
                if (bets.isEmpty()) return@launch
                // 只查「库里确实买过的彩种」，不为一个彩蛋白跑网络
                for (type in bets.map { it.lotteryType }.distinct()) {
                    val latest = prizeService.latestDraw(type) ?: continue
                    val seenKey = TEASE_SEEN_PREFIX + type.code
                    if (settings.getString(seenKey, "") == latest.issue) continue
                    val t = JackpotTease.find(latest, bets) ?: continue
                    settings.putString(seenKey, latest.issue)
                    _tease.value = t
                    LedgerLog.i(
                        "Tease",
                        "撞号：第 ${t.pastIssue} 期买的第 ${t.pastBetIndex} 注，" +
                            "和第 ${t.latestIssue} 期的开奖号码一样（纯玩笑，票的状态不变）"
                    )
                    return@launch   // 一次只弹一个
                }
            } catch (e: Exception) {
                // 彩蛋失败绝不能影响任何功能
                LedgerLog.w("Tease", "撞号检查失败（忽略）：${e.message}")
            }
        }
    }

    private companion object {
        /** 「已经调侃过哪一期」的设置键前缀（每个彩种一个）。 */
        const val TEASE_SEEN_PREFIX = "jackpot_tease_seen_"
    }

    // ---------------- 连通性测试状态 ----------------

    sealed interface TestState {
        data object Idle : TestState
        data object Running : TestState
        data class Done(val report: com.bro.lotteryledger.ai.ConnectivityProbe.Report) : TestState
    }

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()

    fun clearTest() { _test.value = TestState.Idle }

    // ---------------- 识别选项 ----------------

    private val settings = com.bro.lotteryledger.repo.SettingsStore(app)

    /**
     * AI 的思考强度（快速 / 均衡 / 深度）。
     *
     * 这是少爷要的「深度思考开关」。默认 `FAST`：实测同一张票面，
     * 快速档 3.4 秒、平台默认的深度档 23.9 秒（慢 7 倍），而两者正文长度几乎一致。
     * 万一遇到读不准的票，可以切到「均衡」或「深度」再试。
     */
    private val _thinkingLevel = MutableStateFlow(ThinkingLevel.FAST)
    val thinkingLevel: StateFlow<ThinkingLevel> = _thinkingLevel.asStateFlow()

    fun setThinkingLevel(level: ThinkingLevel) {
        _thinkingLevel.value = level
        viewModelScope.launch {
            settings.putString(
                com.bro.lotteryledger.repo.SettingsStore.KEY_THINKING_LEVEL, level.code
            )
            LedgerLog.i("Settings", "思考强度 → ${level.display}（effort=${level.effort}）")
        }
    }

    // ---------------- 主题模式 + 皮肤 ----------------
    //
    // ⚠️⚠️ 这两个字段**必须声明在下面 init 块之前**。Kotlin 按**声明顺序**跑初始化器：
    // 声明在 init 之后 → init 里调 loadThemeMode()/loadSkin() 时它们**还是 null**。
    //
    // 而 viewModelScope 用的是 Dispatchers.Main.immediate —— 在主线程调用 launch
    // 会**立即执行**协程体，于是 `_themeMode.value` 在 null 上取 value → NPE。
    //
    // 2026-09-27 v1.5.0 真机闪退就是这个（少爷录屏截图拿到的堆栈：
    // MainViewModel$loadThemeMode$1.invokeSuspend(MainViewModel.kt:317) NPE）。
    // 平时不崩是因为协程通常排队执行、等到那时 init 早跑完了 —— 典型的竞态，
    // 所以表现是"有时候崩有时候不崩"，极难靠读代码发现。
    //
    // 【教训】init 块里用到的所有字段，声明一律放它上面。改这个文件时别把它们挪下去。

    /**
     * 用户选的浅色/深色/跟随系统。
     *
     * 默认 [ThemeMode.SYSTEM] —— 加这个设置之前 App 就是跟随系统，
     * 老用户升级上来不该被突然换成别的主题。
     *
     * ⚠️ 这里**只管「用户选了什么」**，真正「现在该用浅色还是深色」
     * 由 `LedgerRoot` 结合 `isSystemInDarkTheme()` 算 —— 因为 [ThemeMode.SYSTEM]
     * 需要系统值才能定，而系统值只在 Compose 环境里读得到。
     */
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /**
     * 用户选的皮肤：墨金账本 / 支付宝 / 微信（少爷 2026-09-27）。
     *
     * 默认 [LedgerSkin.MOJIN]。**皮肤和深浅色是两个独立维度**，
     * 所以这里只存「哪套配色」，深浅色仍归 [themeMode] 管。
     */
    private val _skin = MutableStateFlow(LedgerSkin.MOJIN)
    val skin: StateFlow<LedgerSkin> = _skin.asStateFlow()

    /**
     * ⛔ 往这个 init 块里加东西之前，先读这条：
     *
     * Kotlin **按声明顺序**跑初始化器。init 里调用的任何函数，只要它访问了
     * **声明在 init 之后**的字段，那个字段在协程立即执行时**就是 null** ——
     * 而 `viewModelScope` 用 `Dispatchers.Main.immediate`，在主线程 launch 会
     * **同步跑**协程体。于是 `_someFlow.value = x` 变成在 null 上调 —— NPE。
     *
     * 2026-09-27 真机闪退就是这么来的（`_themeMode` 声明晚于 init）。
     *
     * **所以：init 用到的字段，一律声明在它上面。** 加新字段时别图省事塞在下面。
     */
    init {
        refreshAll()
        // 启动就加载平台配置。
        // 不加载的话首页那栏是空的，点「录入彩票」会被误判成「还没配平台」而跳去设置页 ——
        // 少爷报的「每次重进都跳设置页」就是这个原因。
        loadProviders()
        loadThinkingLevel()
        loadThemeMode()
        loadSkin()
        // 顺手扫一遍「已过兑奖截止日但还没兑」的票。
        // 过期是个事实不是事件，早标晚标不影响判断，所以挂在启动时做，不用单独定时。
        viewModelScope.launch {
            try {
                val n = prizeService.sweepExpired()
                if (n > 0) refreshAll()
            } catch (e: Exception) {
                LedgerLog.e("Prize", "扫描过期票失败", e)
            }
        }
        // 彩蛋：往期号码撞上本期大奖就损一句（纯玩笑，不碰任何账目）
        checkJackpotTease()
    }

    private fun loadThinkingLevel() {
        viewModelScope.launch {
            _thinkingLevel.value = ThinkingLevel.from(
                settings.getString(
                    com.bro.lotteryledger.repo.SettingsStore.KEY_THINKING_LEVEL,
                    ThinkingLevel.FAST.code
                )
            )
        }
    }

    // ---------------- 主题模式（少爷 2026-09-27 第 6 条）----------------

    // ---------------- 主题模式（少爷 2026-09-27 第 6 条）----------------
    // 字段声明已上移到 init 块之前（顺序要求见那里的注释）。

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        viewModelScope.launch {
            settings.putString(com.bro.lotteryledger.repo.SettingsStore.KEY_THEME_MODE, mode.code)
            LedgerLog.i("Settings", "主题模式 → ${mode.display}")
        }
    }

    private fun loadThemeMode() {
        viewModelScope.launch {
            _themeMode.value = ThemeMode.from(
                settings.getString(
                    com.bro.lotteryledger.repo.SettingsStore.KEY_THEME_MODE,
                    ThemeMode.SYSTEM.code
                )
            )
        }
    }

    // ---------------- 皮肤（少爷 2026-09-27）----------------
    // 字段声明已上移到 init 块之前（顺序要求见那里的注释）。

    fun setSkin(s: LedgerSkin) {
        _skin.value = s
        viewModelScope.launch {
            settings.putString(com.bro.lotteryledger.repo.SettingsStore.KEY_SKIN, s.code)
            LedgerLog.i("Settings", "皮肤 → ${s.display}")
        }
    }

    private fun loadSkin() {
        viewModelScope.launch {
            _skin.value = LedgerSkin.from(
                settings.getString(
                    com.bro.lotteryledger.repo.SettingsStore.KEY_SKIN,
                    LedgerSkin.MOJIN.code
                )
            )
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _home.value = _home.value.copy(loading = true)
            val stats = repo.stats()
            val tickets = repo.allTickets()
            val draftCount = repo.drafts().size
            val needsCheck = repo.needsCheck().size
            val unavailable = repo.ticketsByStatus(TicketStatus.RESULT_UNAVAILABLE.name).size
            // 「一键兑奖」要看的两项。金额也要算出来 —— 确认框里得让用户看到将入账多少，
            // 否则等于蒙着眼点一个不可逆的操作。
            val pendingRedeem = repo.ticketsByStatus(TicketStatus.TO_REDEEM.name)
            val (calls, tokens) = repo.apiStats()
            // 刷新数据时**保留用户当前的筛选条件** —— 核验完一张票回来发现筛选被重置，
            // 是要骂人的
            val f = _home.value.filter
            // 同一期买了多张 → 给它们编个序（少爷 2026-09-27 第 2 条）。
            // 序号跟着 tickets 的顺序走，所以「1」永远是这一期最早买的那张。
            val issueSeq = IssueSeq.build(
                items = tickets,
                keyOf = { IssueSeq.key(it.lotteryType, it.issue) },
                idOf = { it.id }
            )
            val visible = tickets.filter { f.matches(it) }
            // 展示编号（少爷 2026-09-27 要求）。
            // ⚠️ 必须用 `tickets`（全量）而不是 `visible` —— 少爷要求「编号在筛选时不变」。
            // 列表已按开奖日期倒序，所以这里算出来天然是「越大越新」。
            val ticketCodes = TicketCode.assign(tickets) { it.id }
            // 几条提示要带的编号清单（少爷 2026-09-27 要求）。
            // 先查票、再按 id 映射成编号 —— 编号表已经算好了，这里只是取值。
            val unavailableTickets =
                repo.ticketsByStatus(TicketStatus.RESULT_UNAVAILABLE.name)
            val toConfirmTickets =
                repo.ticketsByStatus(TicketStatus.PRIZE_PENDING.name)
            fun codesOf(list: List<TicketEntity>) =
                TicketCode.join(list.map { ticketCodes[it.id] })
            _home.value = HomeState(
                loading = false, stats = stats, tickets = tickets,
                visible = visible,
                filter = f,
                // 只在真筛过的时候才算 —— 没筛选时这组数跟总账一模一样，算了也没人看
                visibleStats = if (f.active) TicketStats.of(visible) else null,
                draftCount = draftCount, needsCheckCount = needsCheck,
                unavailableCount = unavailable,
                toRedeemCount = pendingRedeem.size,
                toRedeemAmount = pendingRedeem.sumOf { it.prizeAmount ?: 0.0 },
                totalCalls = calls, totalTokens = tokens,
                issueSeq = issueSeq,
                ticketCodes = ticketCodes,
                pendingRedeemCodes = codesOf(pendingRedeem),
                unavailableCodes = codesOf(unavailableTickets),
                toConfirmCodes = codesOf(toConfirmTickets)
            )
        }
    }

    // ---------------- 列表筛选（§15）----------------

    /**
     * 改筛选条件。
     *
     * 只重算列表，**不重新查库** —— 票已经在内存里了，筛选是纯计算。
     *
     * 统计口径（少爷 2026-09-27 改）：主数字 [HomeState.stats] 永远是**全部账**，
     * 但同时算出筛选结果那一组金额放进 [HomeState.visibleStats]，
     * 由主账卡多显示一行 —— 这样既看得到筛选效果，主数字也还有个不变的基准。
     *
     * ponytail: 内存过滤在几百~几千张票的量级完全够用。
     * 票数上万时应该把条件下推到 SQL（DAO 那边加动态 WHERE），现在不值得。
     */
    fun setFilter(f: TicketFilter) {
        val h = _home.value
        val visible = h.tickets.filter { f.matches(it) }
        _home.value = h.copy(
            filter = f,
            visible = visible,
            visibleStats = if (f.active) TicketStats.of(visible) else null
        )
    }

    fun clearFilter() = setFilter(TicketFilter.NONE)

    fun loadProviders() {
        viewModelScope.launch { _providers.value = repo.providers() }
    }

    // ---------------- 图片 → 识别 ----------------

    /** 当前识别任务。用于支持「取消」——识别可能要跑 15~60 秒，用户得能退出。 */
    private var scanJob: kotlinx.coroutines.Job? = null

    /**
     * 完整识别流程（§2.2）。
     * 无论成功失败，临时图片都会在 finally 里删除（§3.1）。
     */
    fun scanUri(uri: Uri) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _scan.value = ScanState.Working("正在加载图片")
            val prepared = try {
                pipeline.prepare(uri, primaryQuality())
            } catch (e: Exception) {
                null
            }
            if (prepared == null) {
                _scan.value = ScanState.Failed("图片读取失败，请换一张试试")
                return@launch
            }
            try {
                runRecognition(prepared, InputMethod.IMAGE)
            } finally {
                pipeline.discard(prepared)
            }
        }
    }

    fun scanBitmap(bmp: Bitmap) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _scan.value = ScanState.Working("正在处理照片")
            val prepared = try {
                pipeline.prepare(bmp, primaryQuality())
            } catch (e: Exception) {
                null
            }
            if (prepared == null) {
                _scan.value = ScanState.Failed("照片处理失败")
                return@launch
            }
            try {
                runRecognition(prepared, InputMethod.IMAGE)
            } finally {
                pipeline.discard(prepared)
            }
        }
    }

    /**
     * 用户主动取消识别。
     *
     * 说明：底层的 HTTP 请求是阻塞式的，协程取消不会立刻中断它，
     * 但**结果会被丢弃**，界面立即恢复。代价是那个请求会在后台跑到自然结束
     * （最多一个超时周期），用户不必知道这个细节。
     */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        LedgerLog.w("Scan", "用户主动取消了识别")
        _scan.value = ScanState.Idle
    }

    /**
     * 解析好的识别平台（含 Key）。
     *
     * 批量导入时**只解析一次** —— 每张都重查库 + 解一次 Keystore 密文是纯浪费，
     * 而且 13 张票的情况下这个开销不小。
     */
    private data class ResolvedProviders(
        val primary: AiProviderEntity,
        val primaryConfig: AiProviderConfig,
        val primaryKey: String,
        val reviewerConfig: AiProviderConfig?,
        val reviewerKey: String
    )

    /**
     * 解析识别要用的平台与 Key。
     *
     * @param onProblem 出问题时把提示交给调用方 —— 单张路径显示到核对页，
     *                  批量路径显示到批量结果里
     * @return null 表示没法继续，调用方应直接返回
     */
    private suspend fun resolveProviders(onProblem: (String) -> Unit): ResolvedProviders? {
        val all = repo.enabledProviders()
        val primary = all.firstOrNull { it.role == "primary" || it.role == "both" }
        if (primary == null) {
            LedgerLog.w("Scan", "没有可用的主识别平台")
            onProblem("还没有可用的识别平台。请先到「设置 → AI 平台」添加一个并填写 API Key。")
            return null
        }
        val primaryKey = keys.get(primary.apiKeyRef)
        if (primaryKey.isNullOrBlank()) {
            LedgerLog.w("Scan", "平台「${primary.name}」的 API Key 读不到（Keystore 解密失败或未保存）")
            onProblem("平台「${primary.name}」的 API Key 没读到，请重新填写。")
            return null
        }

        val reviewer = all.firstOrNull { it.role == "reviewer" || it.role == "both" }

        // 把这次识别的关键参数写进日志 —— 出问题时这几行就是诊断依据
        LedgerLog.i(
            "Scan",
            "识别参数：平台=${primary.name} 模型=${primary.model} " +
                "超时=${primary.timeoutSeconds}s 重试=${primary.maxRetries} " +
                "质量=${primary.imageQuality} 思考=${_thinkingLevel.value.display} " +
                "复核=${reviewer?.name ?: "无"}"
        )

        return ResolvedProviders(
            primary = primary,
            primaryConfig = primary.toConfig(),
            primaryKey = primaryKey,
            reviewerConfig = reviewer?.toConfig(),
            reviewerKey = reviewer?.let { keys.get(it.apiKeyRef) }.orEmpty()
        )
    }

    /** 识别一张图并记录调用。失败返回 null（异常已被吞掉并记日志）。 */
    private suspend fun recognizeOne(
        prepared: ImagePipeline.Prepared,
        rp: ResolvedProviders
    ): RecognitionOrchestrator.Outcome? = try {
        val outcome = RecognitionOrchestrator(client).run(
            primary = rp.primaryConfig,
            reviewer = rp.reviewerConfig,
            primaryKey = rp.primaryKey,
            reviewerKey = rp.reviewerKey,
            imageBase64 = prepared.base64
        )
        // 记录调用（§1.2）—— 失败也要记，否则统计不到失败的消耗
        outcome.runs.forEach { repo.saveRun(it) }
        outcome
    } catch (e: Exception) {
        LedgerLog.e("Scan", "识别流程抛出异常", e)
        null
    }

    private suspend fun runRecognition(prepared: ImagePipeline.Prepared, inputMethod: InputMethod) {
        // ---- 本地预筛（少爷 2026-09-27 要求）----
        // 几乎全黑、整片同色的图**不可能是彩票**，在发请求之前就拦掉，省一次调用。
        // 注意这是「宁可漏拦不可误拦」的一层：判据保守，拿不准就放行。
        val guard = ImageGuard.judge(prepared.stats)
        if (guard.blocks) {
            LedgerLog.w(
                "Scan",
                "本地预筛拦下（${guard.verdict}）：亮度=${"%.1f".format(prepared.stats.brightnessMean)} " +
                    "方差=${"%.1f".format(prepared.stats.brightnessStd)} " +
                    "饱和=${"%.2f".format(prepared.stats.saturationMean)}，未发请求"
            )
            _scan.value = ScanState.Failed(guard.message)
            return
        }
        if (guard.warns) {
            LedgerLog.i("Scan", "本地预筛提醒（${guard.verdict}）：${guard.message}")
        }

        val imageHash = Fingerprints.imageFingerprint(prepared.sha256Bytes())
        LedgerLog.i(
            "Scan",
            "图片就绪：${prepared.width}x${prepared.height}, ${prepared.bytes / 1024}KB, " +
                "base64 ${prepared.base64.length / 1024}KB, 方式=$inputMethod"
        )

        val rp = resolveProviders { msg -> _scan.value = ScanState.Failed(msg) } ?: return
        _scan.value = ScanState.Working("正在识别票面…")

        val outcome = recognizeOne(prepared, rp)
        if (outcome == null) {
            _scan.value = ScanState.Failed("识别过程出错，具体原因已记入日志")
            refreshAll()
            return
        }

        val ticket = outcome.ticket
        if (ticket == null) {
            val err = outcome.fatalError ?: "识别失败"
            LedgerLog.e("Scan", "识别未产出可入账票据：$err")
            // 模型明确没读出彩种时，多半是误拍（桌面/地面），
            // 跟「票面拍糊了」是两回事 —— 分开说，否则用户会对着桌面反复重拍。
            _scan.value = ScanState.Failed(
                if (BatchTriage.looksLikeNotTicket(err)) {
                    "模型没在这张照片里找到彩票信息。\n" +
                        "可能是误拍（桌面、地面之类），也可能是票面拍得不清楚。\n" +
                        "可以重拍一张，或者用拍照页的「手工填写」直接录入。"
                } else {
                    err
                }
            )
            refreshAll()
            return
        }
        LedgerLog.i("Scan", "识别完成：状态=${ticket.recognitionStatus}，冲突=${outcome.conflicts.size} 项")

        val validation = outcome.validation ?: BetRules.validate(ticket)
        val dup = repo.judgeDuplicate(ticket)

        // 少爷 2026-09-28：单张扫描识别无异常时**也直接入账**，不再多一次手动确认。
        //
        // 判据**与批量导入完全同一套** —— 无错误、无警告、无字段冲突、
        // 非重复、非疑似重复。任一条不满足仍然落到下面的核对页：
        // 金额对不上这类问题自动入账会把错账固化进账目，这条边界不能松。
        if (BatchTriage.classify(validation, outcome.conflicts, dup.status) == DraftTriage.CLEAN) {
            if (commitCleanScan(ticket, inputMethod, imageHash) != null) {
                _scan.value = ScanState.Idle
                refreshAll()
                return
            }
            // 入账抛异常才会走到这里 —— 退回核对页，票不能丢
        }

        // 先存草稿再进核对页：用户中途退出/闪退也不丢（§16），
        // 拿到 id 后挂在 PendingEntry 上，入账或放弃时好删
        val draftId = repo.saveDraft(
            ticket, ticket.recognitionStatus, inputMethod, imageHash, outcome.conflicts
        )

        _pending.value =         PendingEntry(
            ticket = ticket,
            validation = validation,
            duplicate = dup,
            conflicts = outcome.conflicts,
            conflictValues = outcome.conflictValues,
            inputMethod = inputMethod,
            imageHash = imageHash,
            distinctConfirmed = false,
            recognizedStatus = ticket.recognitionStatus,
            draftId = draftId,
            fromBatch = false
        )

        _scan.value = ScanState.Ready(
            ticket = ticket,
            validation = validation,
            conflicts = outcome.conflicts,
            reviewed = outcome.reviewed,
            imageQuality = primaryQuality()
        )
        refreshAll()
    }

    /**
     * 单张扫描的「识别无异常 → 直接入账」（少爷 2026-09-28）。
     *
     * 判据与批量导入**完全同一套**（[BatchTriage.classify]）：无错误、**无警告**、
     * 无字段冲突、非重复、非疑似重复。任何一条不满足都还是落到人工核对页。
     *
     * 返回新票 id；入账失败返回 null（调用方退回核对页，票不能丢）。
     *
     * ⚠️ 两个不能省的收尾：
     *  1. **必须 toast** —— 用户没经过核对页，不说一声他会以为票没录进去，
     *     回头重扫一张 → 变成重复票。
     *  2. **必须 [autoCheckAfterCommit]** —— 跟核对页入账同一待遇；
     *     漏了就成了「手动入账的票当场出结果，自动入账的要等明天」。
     */
    private suspend fun commitCleanScan(
        ticket: RawTicket,
        inputMethod: InputMethod,
        imageHash: String?
    ): Long? {
        val newId = try {
            val id = repo.commit(ticket, inputMethod, DuplicateStatus.UNIQUE, imageHash)
            LedgerLog.i(
                "Ledger",
                "直接入账（单张·识别无异常）：${ticket.lotteryType?.display} 期 ${ticket.issue}，" +
                    "${ticket.bets.size} 注 ${ticket.amountYuan ?: "?"} 元"
            )
            id
        } catch (e: Exception) {
            LedgerLog.e("Ledger", "直接入账失败，转为人工核对", e)
            null
        }
        if (newId != null) {
            _toast.value = Notice("已直接入账：" + describeForToast(ticket))
            autoCheckAfterCommit(newId)
        }
        return newId
    }

    /**
     * 直接入账的提示文案。
     *
     * 用户没经过核对页，**这行字是他唯一的核对依据** —— 必须把
     * 彩种 / 期号 / 注数 / 金额 都带上，他扫一眼就知道读对没有。
     */
    private fun describeForToast(t: RawTicket): String {
        val amt = t.amountYuan?.let {
            if (it == kotlin.math.floor(it)) it.toLong().toString() else String.format("%.2f", it)
        } ?: "金额未知"
        return "${t.lotteryType?.display ?: "?"} ${t.issue} 期 · ${t.bets.size} 注 · $amt 元"
    }

    private suspend fun primaryQuality(): AiProviderConfig.ImageQuality {
        val all = repo.enabledProviders()
        val primary = all.firstOrNull { it.role == "primary" || it.role == "both" }
        return AiProviderConfig.ImageQuality.from(primary?.imageQuality)
    }

    // ---------------- 批量导入（§8）----------------

    /**
     * 批量识别状态。
     *
     * 为什么 `Running` 要带 total/index：13 张票就算 5 秒一张也要一分多钟，
     * 没有进度用户根本不知道是在跑还是死了 —— 这个教训在单张识别上已经吃过一次。
     */
    sealed interface BatchState {
        data object Idle : BatchState
        data class Running(
            val total: Int,
            /** 正在处理第几张，从 1 开始（给人看的） */
            val index: Int,
            val ok: Int,
            val failed: Int,
            /**
             * 已经跳过的张数：本地预筛判定「确定不是彩票」的 + 模型明确读不出彩种的。
             *
             * 跟 [failed] 分开算 —— 误拍是用户选错了图，不是功能出问题。
             * 混在一起会让「失败 5 张」看起来很吓人，实际只是选错了照片。
             */
            val skipped: Int = 0
        ) : BatchState
        data class Done(
            val total: Int,
            val ok: Int,
            val failed: Int,
            val canceled: Boolean,
            /**
             * 分诊结果（§8 的批量完成页要显示「11 张正常 / 1 张疑似重复 / 1 张需确认」）。
             *
             * 只有跑完的批次才算得出来；取消的批次这里是全 0，
             * 因为那时用户还没想好要不要继续。
             */
            val triage: BatchTriage.Summary = BatchTriage.Summary(0, 0, 0),
            /** 见 [Running.skipped] */
            val skipped: Int = 0
        ) : BatchState
    }

    private val _batch = MutableStateFlow<BatchState>(BatchState.Idle)
    val batch: StateFlow<BatchState> = _batch.asStateFlow()

    private var batchJob: kotlinx.coroutines.Job? = null
    private var batchCanceled = false

    /**
     * 最近一批识别产生的草稿 id。
     *
     * 「正常票直接入账」**只处理这一批** —— 不去碰之前遗留的草稿。
     * 否则少爷可能没看清就把历史遗留的一起入账了，那是个很糟的意外。
     */
    private var lastBatchDraftIds: List<Long> = emptyList()

    /**
     * 批量识别一组图片（§8）。
     *
     * ## 串行，不做并发
     *
     * 交接稿说「可限制并发，例如 2 张同时」，我选择**串行**，理由：
     *  - 单张本身就要几秒~几十秒，并发省的时间有限，却显著提高触发平台
     *    QPS 限流的概率 —— 一旦限流，整批都可能失败
     *  - 串行下「第 3/13 张」才有确定含义，进度界面才说得清
     *  - 出错时定位是哪张的问题也更直接
     *
     * 真要做并发，得先有「按平台限流 + 失败退避」，那是另一个量级的工作。
     * 这段循环改成 `coroutineScope { uris.map { async { ... } } }` 配 Semaphore 即可。
     *
     * ## 单张失败不中断整批（§8 明确要求）
     *
     * 每张独立 try/catch，失败的计入 failed 继续下一张。
     *
     * ## 存草稿 = 入队
     *
     * 每张识别成功就存一条草稿，草稿表在这里当**队列**用：
     *  - 中途退出/闪退不丢（已识别的票已经落库，钱没白花）
     *  - 核对时按 id 升序取（先进先出，顺序和拍照顺序一致）
     *  - 核对入账后删掉（消费掉）
     */
    fun scanUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        batchJob?.cancel()
        batchCanceled = false

        batchJob = viewModelScope.launch {
            val total = uris.size
            var ok = 0
            var failed = 0
            var skipped = 0
            _batch.value = BatchState.Running(total, 0, 0, 0)

            val rp = resolveProviders { msg ->
                _batch.value = BatchState.Done(total, 0, total, canceled = false)
                _toast.value = Notice(msg)
            } ?: return@launch

            LedgerLog.i("Batch", "开始批量识别 $total 张")
            val started = System.currentTimeMillis()
            val newDraftIds = mutableListOf<Long>()

            for ((i, uri) in uris.withIndex()) {
                if (batchCanceled) break
                _batch.value = BatchState.Running(total, i + 1, ok, failed, skipped)

                val prepared = try {
                    pipeline.prepare(uri, primaryQuality())
                } catch (e: Exception) {
                    LedgerLog.w("Batch", "第 ${i + 1} 张读取失败：${e.message}")
                    null
                }
                if (prepared == null) {
                    failed++
                    continue
                }

                // ---- 本地预筛：确定不是票的图，跳过（不算失败，且不发请求）----
                val guard = ImageGuard.judge(prepared.stats)
                if (guard.blocks) {
                    skipped++
                    pipeline.discard(prepared)
                    LedgerLog.w(
                        "Batch",
                        "第 ${i + 1} 张被本地预筛跳过（${guard.verdict}），未发请求"
                    )
                    continue
                }

                try {
                    val imageHash = Fingerprints.imageFingerprint(prepared.sha256Bytes())
                    val outcome = recognizeOne(prepared, rp)
                    val ticket = outcome?.ticket
                    if (ticket == null) {
                        val err = outcome?.fatalError
                        if (BatchTriage.looksLikeNotTicket(err)) {
                            // 模型明确没读出彩种 —— 多半是误拍，归「跳过」而不是「失败」
                            skipped++
                            LedgerLog.w("Batch", "第 ${i + 1} 张看着不像彩票，跳过：$err")
                        } else {
                            failed++
                            LedgerLog.w("Batch", "第 ${i + 1} 张识别失败：${err ?: "异常"}")
                        }
                    } else {
                        val draftId = repo.saveDraft(
                            ticket, ticket.recognitionStatus,
                            InputMethod.IMAGE, imageHash, outcome.conflicts
                        )
                        newDraftIds += draftId
                        // 实时同步：中途被取消时，已识别的这些票也要能用「直接入账」
                        lastBatchDraftIds = newDraftIds.toList()
                        ok++
                        LedgerLog.i(
                            "Batch",
                            "第 ${i + 1}/$total 张完成：${ticket.lotteryType?.display} 期 ${ticket.issue}"
                        )
                    }
                } catch (e: Exception) {
                    LedgerLog.e("Batch", "第 ${i + 1} 张处理异常", e)
                    failed++
                } finally {
                    pipeline.discard(prepared)
                }
            }

            val elapsed = (System.currentTimeMillis() - started) / 1000
            lastBatchDraftIds = newDraftIds
            // 分诊：数清楚这一批里「能直接入账 / 要人看 / 是重复」各多少（只读，不改数据）
            val summary = triageDrafts(newDraftIds)
            LedgerLog.i(
                "Batch",
                "批量识别结束：成功 $ok，失败 $failed，跳过 $skipped（不像彩票/预筛拦下），用时 ${elapsed}s；" +
                    "分诊 → 可直接入账 ${summary.clean}，待核对 ${summary.review}，重复 ${summary.duplicate}"
            )
            _batch.value = BatchState.Done(total, ok, failed, batchCanceled, summary, skipped)
            refreshAll()
            // 这里**不自动**进核对队列：界面会先弹出结果对话框，
            // 让少爷选「直接入账 N 张」还是「逐张核对」。见 MainActivity 的两个按钮。
        }
    }

    /**
     * 对一批草稿分诊。**只读**，不改任何数据。
     *
     * 判据全部来自纯逻辑（[BatchTriage]）+ 已有的去重判定，
     * 所以这里只是把两边拼起来，不含新的业务规则。
     */
    private suspend fun triageDrafts(ids: List<Long>): BatchTriage.Summary {
        var clean = 0
        var review = 0
        var dup = 0
        for (id in ids) {
            val d = repo.draft(id) ?: continue
            val raw = TicketCodec.decode(d.ticketJson) ?: continue
            val conflicts = d.conflicts.split("|").filter { it.isNotBlank() }
            val verdict = BatchTriage.classify(
                validation = BetRules.validate(raw),
                conflicts = conflicts,
                duplicate = repo.judgeDuplicate(raw).status
            )
            when (verdict) {
                DraftTriage.CLEAN -> clean++
                DraftTriage.DUPLICATE -> dup++
                DraftTriage.NEEDS_REVIEW -> review++
            }
        }
        return BatchTriage.Summary(clean, review, dup)
    }

    /**
     * 把这一批里「完全没问题」的票直接入账（§8「正常票直接入账」）。
     *
     * 三件事一起做，避免让少爷点三次：
     *  - 干净的票 → 入账
     *  - 完全重复的票 → 跳过并删草稿（它已经入账过，草稿留着毫无用处）
     *  - 需要人看的 → 留在队列里
     *
     * **只处理 [lastBatchDraftIds] 里的票**，不碰历史遗留草稿。
     */
    fun autoCommitClean() {
        val ids = lastBatchDraftIds
        if (ids.isEmpty()) {
            _batch.value = BatchState.Idle
            return
        }
        viewModelScope.launch {
            _batch.value = BatchState.Idle
            var committed = 0
            var skippedDup = 0
            var left = 0

            for (id in ids) {
                val d = repo.draft(id) ?: continue
                val raw = TicketCodec.decode(d.ticketJson)
                if (raw == null) {
                    repo.deleteDraft(d.id)
                    continue
                }
                val conflicts = d.conflicts.split("|").filter { it.isNotBlank() }
                when (
                    BatchTriage.classify(
                        validation = BetRules.validate(raw),
                        conflicts = conflicts,
                        duplicate = repo.judgeDuplicate(raw).status
                    )
                ) {
                    DraftTriage.CLEAN -> {
                        try {
                            repo.commit(raw, InputMethod.IMAGE, DuplicateStatus.UNIQUE, d.imageHash)
                            repo.deleteDraft(d.id)
                            committed++
                        } catch (e: Exception) {
                            LedgerLog.e("Batch", "自动入账失败（草稿 #${d.id}）", e)
                            left++
                        }
                    }
                    DraftTriage.DUPLICATE -> {
                        repo.deleteDraft(d.id)
                        skippedDup++
                    }
                    DraftTriage.NEEDS_REVIEW -> left++
                }
            }

            LedgerLog.i(
                "Batch",
                "直接入账完成：入账 $committed，跳过重复 $skippedDup，留下待核对 $left"
            )
            _toast.value = Notice(
                buildList {
                    add("已直接入账 $committed 张")
                    if (skippedDup > 0) add("跳过重复 $skippedDup 张")
                    if (left > 0) add("还有 $left 张待核对")
                }.joinToString("，")
            )
            refreshAll()
            // 剩下的（需要人看的）接着逐张核对
            if (left > 0) loadNextDraft(fromBatch = true)
        }
    }

    /** 批量结果对话框的「逐张核对」按钮。 */
    fun startBatchReview() {
        _batch.value = BatchState.Idle
        viewModelScope.launch { loadNextDraft(fromBatch = true) }
    }

    /**
     * 取消批量识别。
     *
     * 已识别好的草稿**保留** —— 那些 API 调用已经花过钱了，
     * 取消只是不再处理剩下的图，不该把成果一起丢掉。
     */
    fun cancelBatch() {
        val cur = _batch.value as? BatchState.Running ?: return
        batchCanceled = true
        batchJob?.cancel()
        batchJob = null
        LedgerLog.w("Batch", "用户取消批量识别（已完成 ${cur.ok} 张）")
        _batch.value = BatchState.Done(
            cur.total, cur.ok, cur.failed, canceled = true,
            triage = BatchTriage.Summary(0, 0, 0), skipped = cur.skipped
        )
        viewModelScope.launch {
            // 取消时也补一次分诊 —— 已识别的票是花过 API 钱的，
            // 「直接入账」对它们同样可用，没道理因为取消就少这个选项
            val summary = triageDrafts(lastBatchDraftIds)
            _batch.value = BatchState.Done(
                cur.total, cur.ok, cur.failed, canceled = true,
                triage = summary, skipped = cur.skipped
            )
            refreshAll()
        }
    }

    fun dismissBatch() { _batch.value = BatchState.Idle }

    // ---------------- 核对队列 ----------------

    /** 从草稿队列取下一张进入核对。返回是否取到了。 */
    private suspend fun loadNextDraft(fromBatch: Boolean): Boolean {
        while (true) {
            val d = repo.oldestDraft() ?: return false
            val raw = TicketCodec.decode(d.ticketJson)
            if (raw == null) {
                // 解不开的草稿直接丢掉，否则会永远卡在队首，整个队列再也走不动
                LedgerLog.w("Draft", "草稿 #${d.id} 解不开，已丢弃")
                repo.deleteDraft(d.id)
                continue
            }
            val validation = BetRules.validate(raw)
            _pending.value = PendingEntry(
                ticket = raw,
                validation = validation,
                duplicate = repo.judgeDuplicate(raw),
                conflicts = d.conflicts.split("|").filter { it.isNotBlank() },
                inputMethod = InputMethod.from(d.inputMethod),
                imageHash = d.imageHash,
                distinctConfirmed = false,
                recognizedStatus = RecognitionStatus.from(d.recognitionStatus)
                    ?: RecognitionStatus.VERIFIED,
                draftId = d.id,
                fromBatch = fromBatch
            )
            return true
        }
    }

    /** 首页「继续核对」入口：把还没核对的草稿续上。 */
    fun startReviewingDrafts() {
        viewModelScope.launch {
            if (!loadNextDraft(fromBatch = true)) {
                _toast.value = Notice("没有待核对的票了")
                refreshAll()
            }
        }
    }

    /** 核对完一张后推进队列。 */
    private suspend fun advanceQueue() {
        if (!loadNextDraft(fromBatch = true)) {
            _toast.value = Notice("这批票都核对完了")
            refreshAll()
        }
    }

    // ---------------- 手工录入（§5）----------------

    /**
     * 开一张空白票，走人工填写。
     *
     * ## 为什么需要这条路
     *
     * 之前所有票都必须经过拍照识别。一旦 AI 读不出来（票拍糊了、复式号码太多太密，
     * 或者平台欠费/网络不通），用户**只能反复重拍** —— 没有任何别的入口。
     * 而**复式票恰恰是最容易读错的一类**（十几个号码挤在一行）。
     *
     * 这条路径复用核对页的全部编辑能力（选号面板、倍数、追加、票面编号），
     * 所以「复式 / 倍投录入」不需要另做一套界面。
     */
    fun startManualEntry(type: LotteryType = LotteryType.SSQ) {
        val blank = RawTicket(
            lotteryType = type,
            issue = null,
            drawDate = null,
            purchaseTime = null,
            betType = BetType.SINGLE,
            multiple = 1,
            additional = false,
            amountYuan = null,
            bets = listOf(Bets.blank(type, 1)),
            recognitionStatus = RecognitionStatus.MANUAL_VERIFIED
        )
        _pending.value = PendingEntry(
            ticket = blank,
            validation = BetRules.validate(blank),
            // 空白票没有任何编号和号码，跟谁都比不出重复，直接放行
            duplicate = Fingerprints.DuplicateVerdict(
                status = DuplicateStatus.UNIQUE,
                matchedTicketId = null,
                reason = "手工录入的新票"
            ),
            conflicts = emptyList(),
            inputMethod = InputMethod.MANUAL,
            imageHash = null,
            distinctConfirmed = false,
            recognizedStatus = RecognitionStatus.MANUAL_VERIFIED
        )
        _scan.value = ScanState.Idle
        LedgerLog.i("Manual", "开始手工录入：${type.display}")
    }

    /**
     * 单式 / 复式自动判定。
     *
     * 规则本身在 `core/Bets.inferType` 里（纯函数，能单测）：
     * 只做「确凿的升级」，反向不自动降级。
     */
    private fun inferBetType(t: RawTicket): BetType {
        val type = t.lotteryType ?: return t.betType
        return Bets.inferType(type, t.bets, t.betType)
    }

    // ---------------- 核对界面编辑 ----------------

    fun updatePending(transform: (RawTicket) -> RawTicket) {
        val cur = _pending.value ?: return
        val edited = transform(cur.ticket)
        // 每次修改都顺手纠正一次投注方式 —— 用户选完复式号码不用自己去改那个开关
        val normalized = edited.copy(betType = inferBetType(edited))
        _pending.value = cur.copy(
            ticket = normalized,
            validation = BetRules.validate(normalized)
        )
    }

    fun confirmDistinct() {
        val cur = _pending.value ?: return
        _pending.value = cur.copy(distinctConfirmed = true, duplicate = cur.duplicate.copy(
            status = DuplicateStatus.CONFIRMED_DISTINCT,
            reason = "你已确认这是另一张实体票"
        ))
    }

    /** 入账（§9 + §16：只有走到这一步才产生购彩支出）。 */
    fun commitPending(onDone: () -> Unit) {
        val cur = _pending.value ?: return
        if (cur.validation.hasError) {
            _toast.value = Notice("还有 ${cur.validation.errors.size} 处必须修正的问题")
            return
        }
        if (cur.duplicate.status == DuplicateStatus.EXACT_DUPLICATE) {
            _toast.value = Notice("这张票已入账（${cur.duplicate.reason}）")
            return
        }

        viewModelScope.launch {
            try {
                val status = when {
                    cur.inputMethod == InputMethod.IMAGE && cur.conflicts.isEmpty() -> InputMethod.IMAGE
                    cur.inputMethod == InputMethod.IMAGE -> InputMethod.IMAGE_EDITED
                    else -> cur.inputMethod
                }
                val dupStatus = if (cur.distinctConfirmed) DuplicateStatus.CONFIRMED_DISTINCT
                else cur.duplicate.status

                val newTicketId = try {
                    val id = repo.commit(cur.ticket, status, dupStatus, cur.imageHash)
                    LedgerLog.i("Ledger", "入账成功：${cur.ticket.lotteryType?.display} 期 ${cur.ticket.issue}，${cur.ticket.bets.size} 注 ${cur.ticket.amountYuan ?: "?"} 元，去重=$dupStatus")
                    id
                } catch (e: Exception) {
                    LedgerLog.e("Ledger", "入账失败", e)
                    _toast.value = Notice("入账失败：${e.message}", NoticeKind.ERROR)
                    null
                }
                if (newTicketId != null) {
                    // 草稿在这里是「队列」：入账即消费掉，删了才算真的处理完。
                    // 不删的话首页那条「有 N 笔草稿还没确认」永远不消失（这是个老 bug）。
                    cur.draftId?.let { repo.deleteDraft(it) }
                    _pending.value = null
                    _scan.value = ScanState.Idle
                    refreshAll()
                    onDone()
                    // 少爷的要求：入账时如果这张票的开奖时间已经过了，当场核验一次，
                    // 别让用户等到晚上 22:30 的定时任务。
                    autoCheckAfterCommit(newTicketId)
                    // 批量核对：自动弹出下一张，不用退出去再点进来
                    if (cur.fromBatch) advanceQueue()
                }
            } catch (e: Exception) {
                _toast.value = Notice("入账失败：${e.message}", NoticeKind.ERROR)
            }
        }
    }

    /**
     * 返回键在核对页时调用：不直接丢，先问一句。
     * （声明放在 discardPending 之前，避免 Kotlin 前向引用问题）
     */
    private val _confirmDiscard = MutableStateFlow(false)
    val confirmDiscard: StateFlow<Boolean> = _confirmDiscard.asStateFlow()

    fun askDiscard() {
        _confirmDiscard.value = _pending.value != null
    }

    fun cancelDiscard() { _confirmDiscard.value = false }

    fun discardPending() {
        val cur = _pending.value
        _pending.value = null
        _scan.value = ScanState.Idle
        _confirmDiscard.value = false
        if (cur == null) return
        viewModelScope.launch {
            // 放弃 = 这张票不要了，草稿一并删掉。
            // 草稿存在的意义是「防闪退丢票」，不是当回收站 —— 用户明确不要了就该清掉，
            // 否则它会一直躺在队列里被反复弹出来。
            cur.draftId?.let { repo.deleteDraft(it) }
            // 批量核对：放弃一张后接着下一张，别让队列卡住
            if (cur.fromBatch) advanceQueue()
        }
    }

    fun resetScan() {
        _scan.value = ScanState.Idle
        _pending.value = null
    }

    // ---------------- 票详情 ----------------

    /**
     * 票详情。
     *
     * [deadline] 是**实时算的**（没读数据库里那个 claimDeadline 字段）——
     * 那些字段是入账时写的，而顺延规则以后可能调整，实时算才能保证和当前规则一致。
     * 代价是每次打开算一次，但这个计算是纯日期运算，可以忽略。
     */
    data class TicketDetail(
        val ticket: TicketEntity,
        val bets: List<TicketBetEntity>,
        val deadline: RedemptionDeadline.Info?,
        /** 这一期的开奖号码（库里查到的；没有就说明还没查到） */
        val draw: DrawNumbers? = null,
        /**
         * 这张票的展示编号（少爷 2026-09-27 要求）。
         *
         * 打开详情时从 [_home] 的编号表里取一次**快照** —— 不实时的原因：
         * 详情页开着的时候列表一般不会变；就算变了（比如核验改了状态），
         * 编号也不该在用户眼皮底下跳，那才是真的让人迷惑。
         */
        val code: Int? = null
    )

    private val _detail = MutableStateFlow<TicketDetail?>(null)
    val detail: StateFlow<TicketDetail?> = _detail.asStateFlow()

    fun openTicket(id: Long) {
        viewModelScope.launch {
            val t = repo.ticket(id)
            if (t == null) {
                _toast.value = Notice("找不到这张票")
                return@launch
            }
            _detail.value = TicketDetail(
                ticket = t,
                bets = repo.betsOf(id),
                deadline = RedemptionDeadline.compute(t.drawDate),
                draw = loadStoredDraw(t),
                // 编号取自当前首页状态。拿不到就 null（界面上不显示编号，不报错）。
                code = TicketCode.codeOf(_home.value.ticketCodes, id)
            )
        }
    }

    /**
     * 统一取编号 —— 各处提示文案都用它，别各自去翻 `home.ticketCodes`。
     *
     * 取不到返回 null（比如票刚被删），[TicketCode.label] 会自然渲染成「不显示编号」。
     */
    fun codeOf(id: Long): Int? = TicketCode.codeOf(_home.value.ticketCodes, id)

    /**
     * 给「针对某一张票」的提示挂上编号（少爷 2026-09-27 要求）。
     *
     * > 「如果其他功能有提示涉及具体某个彩票，可以附带编码，让用户更好定位。」
     *
     * 形如 `#12 · 这张票没中奖`。
     *
     * ## 为什么在这里拼而不是改 `PrizeNotice`
     *
     * `PrizeNotice` 在 core 里是**纯函数、可单测**的，它压根不知道「列表编号」这回事
     * （编号依赖列表顺序，是 UI 层才有的上下文）。把编号塞进去会让它没法单测，
     * 也会让「文案」和「展示位置」混在一起。
     *
     * 所以：**core 负责说什么，这里负责挂在谁的头上。**
     *
     * 拿不到编号就原样返回 —— 提示不能因为编号缺失就不显示了。
     */
    private fun withCode(ticketId: Long, n: Notice): Notice {
        val c = codeOf(ticketId) ?: return n
        return n.copy(text = "#$c · ${n.text}")
    }

    /**
     * 把判重文案里的「数据库 id」换成「展示编号」（少爷 2026-09-27 要求）。
     *
     * ## 为什么需要它
     *
     * `Fingerprints` 判出重复时，reason 里写的是
     * 「票面唯一编号与已有彩票 **#47** 完全一致」—— 那个 `47` 是**数据库主键**
     * （`TicketEntity.id`），用户在任何界面上都看不到它，等于给了个没用的指路牌。
     *
     * 换成展示编号（`#12` 这种），用户就能直接在首页列表里对上号。
     *
     * ## 为什么用替换而不是改 Fingerprints 的入参
     *
     * `Fingerprints` 是 core 里的**纯函数、有完整单测**，它不知道「列表编号」这回事
     * （编号依赖排序，是 UI 才有的上下文）。硬塞进去会让它没法单测。
     *
     * 所以在这里做一次**定向替换**：只把 `#<matchedId>` 这个具体串换掉，
     * 别的一概不动 —— 不会误伤文案里其它数字。
     *
     * 拿不到编号就原样返回（票可能刚被删）。
     */
    fun duplicateReasonText(reason: String, matchedTicketId: Long?): String {
        val mid = matchedTicketId ?: return reason
        val c = codeOf(mid) ?: return reason
        return reason.replace("#$mid", "#$c")
    }

    /** 把库里存的开奖结果还原成 [DrawNumbers]（主区在前、次区在后）。 */
    private suspend fun loadStoredDraw(t: TicketEntity): DrawNumbers? {
        val type = LotteryType.from(t.lotteryType) ?: return null
        val row = repo.drawResult(type, t.issue) ?: return null
        val nums = row.numbers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (nums.size < 3) return null
        val spec = LotterySpecs.of(type)
        val k = spec.groupPickSingle
        if (nums.size <= k) return null
        return DrawNumbers(
            type = type,
            issue = row.issue,
            drawDate = row.drawDate,
            main = nums.take(k),
            second = nums.drop(k)
        )
    }

    fun closeTicket() { _detail.value = null }

    // ---------------- 对奖（§12 / §13）----------------

    /**
     * 批量核验进行中。
     *
     * 两层作用：**防重复点击**（核验要发网络请求，连点会在几秒内打出好几轮），
     * 以及让首页按钮能显示「核验中…」，用户知道点了有反应。
     */
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** 一键兑奖进行中（防重复点击 —— 兑奖不可逆，连点两次会重复入账）。 */
    private val _redeeming = MutableStateFlow(false)
    val redeeming: StateFlow<Boolean> = _redeeming.asStateFlow()

    // ---------------- 「拜拜财神」清空台账（少爷 2026-09-27 要求）----------------

    /**
     * 清空入口的状态。
     *
     * [taps] 是连续点击次数 —— **13 次**是少爷指定的（模仿安卓开发者模式那套
     * 「点够次数才出现隐藏入口」）。门槛必须这么高：这一行就摆在设置页上，
     * 误触概率不低，点两下就弹确认框迟早出事。
     */
    data class WipeState(
        val taps: Int = 0,
        /** 上一次点击的时间，用来判断是不是「一次性连续点击」 */
        val lastTapAt: Long = 0L,
        /** 0 = 未开始；1 / 2 / 3 = 正在第几次确认 */
        val step: Int = 0,
        /** 清空前的现状（第 2 次确认时显示「要清掉多少」） */
        val counts: com.bro.lotteryledger.repo.LedgerRepository.WipeResult? = null,
        /** 购彩合计，同上 */
        val purchase: Double = 0.0,
        /** 正在清空（防重复点击） */
        val busy: Boolean = false
    )

    private val _wipe = MutableStateFlow(WipeState())
    val wipe: StateFlow<WipeState> = _wipe.asStateFlow()

    /**
     * 点这一行多少次才解锁（少爷指定 13 次，模仿安卓开发者模式那套隐藏入口）。
     *
     * 门槛必须这么高：这一行就摆在设置页上，误触概率不低，
     * 点两下就弹确认框迟早出事。
     *
     * 注意「连续」这个语义由 [TapSequence] 保证 —— 中途停太久会从头数，
     * 否则今天点 6 次、明天点 7 次也能凑够。
     */
    private val wipeTapTarget = 13

    /** 点到第几次开始给提示。前几次什么都不显示 —— 真·隐藏入口。 */
    private val wipeHintFrom = 7

    /** 点了一下「拜拜财神」那一行。 */
    fun tapWipeEntry() {
        val cur = _wipe.value
        if (cur.step > 0 || cur.busy) return   // 确认流程已经开着

        val now = System.currentTimeMillis()
        val n = TapSequence.next(cur.taps, cur.lastTapAt, now)

        if (n >= wipeTapTarget) {
            _wipe.value = WipeState(taps = 0, lastTapAt = 0L, step = 1)
            LedgerLog.w("Wipe", "清空入口已解锁（连点 $wipeTapTarget 次），等待 3 次确认")
        } else {
            _wipe.value = cur.copy(taps = n, lastTapAt = now)
            // 前几次不提示：随手点两下不该冒提示。
            // 到第 7 次开始说「还差几次」，用户才知道自己在做什么。
            if (n >= wipeHintFrom) {
                _toast.value = Notice("还差 ${wipeTapTarget - n} 次")
            }
        }
    }

    /** 取消整个流程（任何一步取消都回到原点）。 */
    fun cancelWipe() {
        _wipe.value = WipeState()
    }

    /** 第 1、2 次确认点「继续」——进入下一步。 */
    fun advanceWipe() {
        val cur = _wipe.value.step
        if (cur <= 0 || cur >= 3 || _wipe.value.busy) return
        viewModelScope.launch {
            // 进第 2 步之前把「要清掉多少」查出来 —— 让用户看到具体数字，
            // 而不是只看一句"会清空所有数据"
            if (cur == 1) {
                val c = try {
                    repo.ledgerCounts()
                } catch (e: Exception) {
                    LedgerLog.e("Wipe", "统计待清空数据失败", e)
                    null
                }
                _wipe.value = _wipe.value.copy(
                    step = 2,
                    counts = c,
                    purchase = _home.value.stats?.purchase ?: 0.0
                )
            } else {
                _wipe.value = _wipe.value.copy(step = cur + 1)
            }
        }
    }

    /** 第 3 次确认点「拜拜财神」——真清。 */
    fun confirmWipe() {
        if (_wipe.value.busy || _wipe.value.step != 3) return
        viewModelScope.launch {
            _wipe.value = _wipe.value.copy(busy = true)
            val r = try {
                repo.clearLedger()
            } catch (e: Exception) {
                LedgerLog.e("Wipe", "清空台账失败", e)
                _toast.value = Notice("清空失败：${e.message}", NoticeKind.ERROR)
                cancelWipe()
                return@launch
            }
            // 先写日志再清界面 —— 这条记录是「用户主动清空过」的唯一凭据，
            // 以后账目看着少，能从日志里找到原因
            LedgerLog.w("Wipe", "用户清空了台账：${r.describe()}")

            // 临时图片兜底清一遍（本来识别完就删，这里收拾残留）
            try {
                pipeline.sweepOrphans()
            } catch (_: Exception) {
            }

            _toast.value = Notice(
                if (r.isEmpty) "本来就没记什么，财神还没走远" else "账本已归零，拜拜财神"
            )

            _wipe.value = WipeState()

            // 界面状态整体复位：首页统计、详情页、核对页、识别遮罩
            _detail.value = null
            _pending.value = null
            _scan.value = ScanState.Idle
            _home.value = HomeState()
            refreshAll()
        }
    }

    /**
     * 入账后自动核验。
     *
     * 少爷的要求：**入账时如果这张票的开奖时间已经过了，当场核验一次**，
     * 不用等到晚上 22:30 的定时任务。
     * 反过来，还没到开奖时间的票**不查** —— 白跑一趟网络，而且官方也还没出结果。
     */
    private suspend fun autoCheckAfterCommit(ticketId: Long) {
        val t = repo.ticket(ticketId) ?: return
        if (!com.bro.lotteryledger.repo.PrizeService.isDrawTimePassed(t.drawDate)) {
            LedgerLog.i(
                "Prize",
                "第 ${t.issue} 期开奖日 ${t.drawDate} 还没到，跳过即时核验（等定时任务）"
            )
            return
        }
        _toast.value = Notice("这张票已经开奖了，正在核验…")
        runCheck(ticketId)
    }

    /** 手动核验（详情页 / 首页下拉时用）。 */
    fun checkTicketNow(ticketId: Long) {
        viewModelScope.launch {
            _toast.value = Notice("正在核验…")
            runCheck(ticketId)
        }
    }

    /**
     * 批量核验所有到期未开的票（首页「一键核验」）。
     *
     * 没有待核验的票时**一个网络请求都不发** —— `checkAllPending` 内部先查库，
     * 空列表直接返回。这是少爷明确的要求：没票待开奖就不做核对动作。
     */
    fun checkAllPending() {
        if (_checking.value) return
        viewModelScope.launch {
            _checking.value = true
            _toast.value = Notice("正在核验所有已到开奖日的票…")
            val outcomes = try {
                prizeService.checkAllPending()
            } catch (e: Exception) {
                LedgerLog.e("Prize", "批量核验异常", e)
                emptyList()
            } finally {
                _checking.value = false
            }
            // 汇总文案在 PrizeService.summarize 里（纯函数，能单测）。
            // 以前这里把「官方还没公布」「查不到这一期」都算成「都没中」，
            // 少爷看到「3 张都没中」但列表还挂着「等待开奖结果」，就是这么来的。
            _toast.value = PrizeNotice.summarize(outcomes.map { it.second })
            refreshAll()
            outcomes.filter { it.second is com.bro.lotteryledger.repo.PrizeService.Outcome.Won }
                .forEach { refreshDetailIfOpen(it.first) }
        }
    }

    /**
     * 确认一、二等奖金额（§15 的人工确认环节）。
     *
     * 确认之后状态从 `PRIZE_PENDING` 转 `TO_REDEEM`，
     * 首页 hero 卡的「待确认」就会变成真实的净收支数字。
     */
    fun confirmPrizeAmount(ticketId: Long, amountYuan: Double) {
        viewModelScope.launch {
            try {
                prizeService.confirmAmount(ticketId, amountYuan)
                _toast.value = withCode(ticketId, PrizeNotice.amountConfirmed(amountYuan))
            } catch (e: Exception) {
                LedgerLog.e("Prize", "确认金额失败", e)
                _toast.value = Notice("确认失败：${e.message}", NoticeKind.ERROR)
            }
            refreshAll()
            refreshDetailIfOpen(ticketId)
        }
    }

    /**
     * 标记已兑奖。
     *
     * [amountYuan] 传 null 就用票上的 `prizeAmount`（绝大多数情况）。
     * 传具体值是为了应对「实际到手和应得不一样」的情况。
     */
    fun markRedeemed(ticketId: Long, amountYuan: Double? = null) {
        viewModelScope.launch {
            try {
                prizeService.markRedeemed(ticketId, amountYuan)
                _toast.value = withCode(ticketId, PrizeNotice.markedRedeemed())
            } catch (e: Exception) {
                LedgerLog.e("Prize", "标记兑奖失败", e)
                _toast.value = Notice("标记失败：${e.message}", NoticeKind.ERROR)
            }
            refreshAll()
            refreshDetailIfOpen(ticketId)
        }
    }

    /**
     * 手动标记「未中奖」。
     *
     * 出口是给「查不到开奖结果」的票用的 —— 官方接口里没有这一期时，
     * 用户对着票和公告确认没中，就该能把它从悬而未决里拿出来。
     */
    fun markNotWonManually(ticketId: Long) {
        viewModelScope.launch {
            try {
                prizeService.markNotWon(ticketId)
                _toast.value = withCode(ticketId, PrizeNotice.markedNotWon())
            } catch (e: Exception) {
                LedgerLog.e("Prize", "手动标记未中奖失败", e)
                _toast.value = Notice("标记失败：${e.message}", NoticeKind.ERROR)
            }
            refreshAll()
            refreshDetailIfOpen(ticketId)
        }
    }

    /**
     * 手动认定中奖并记金额。
     *
     * 查不到开奖结果、但用户自己在官方公告里核对出中了，走这条。
     */
    fun markWonManually(ticketId: Long, amountYuan: Double) {
        viewModelScope.launch {
            try {
                prizeService.markWonManually(ticketId, amountYuan)
                _toast.value = withCode(ticketId, PrizeNotice.markedWonManually(amountYuan))
            } catch (e: Exception) {
                LedgerLog.e("Prize", "手动记中奖失败", e)
                _toast.value = Notice("标记失败：${e.message}", NoticeKind.ERROR)
            }
            refreshAll()
            refreshDetailIfOpen(ticketId)
        }
    }

    /**
     * 一键兑奖（少爷 2026-09-27 要求）。
     *
     * 把**所有待兑奖**的票标成已兑奖。适合"去网点一次全兑了"的场景。
     * 不可逆，所以界面上必须二次确认 —— 误点一次就要手工改回每一张。
     */
    fun redeemAllChecked() {
        if (_redeeming.value) return
        viewModelScope.launch {
            _redeeming.value = true
            try {
                val r = prizeService.redeemAll()
                _toast.value = PrizeNotice.redeemedAll(r.count, r.amount)
            } catch (e: Exception) {
                LedgerLog.e("Prize", "一键兑奖失败", e)
                _toast.value = Notice("一键兑奖失败：${e.message}", NoticeKind.ERROR)
            } finally {
                _redeeming.value = false
            }
            refreshAll()
        }
    }

    private suspend fun runCheck(ticketId: Long) {
        val outcome = try {
            prizeService.check(ticketId)
        } catch (e: Exception) {
            LedgerLog.e("Prize", "核验异常", e)
            com.bro.lotteryledger.repo.PrizeService.Outcome.Failed(e.message ?: "未知错误")
        }
        _toast.value = withCode(ticketId, PrizeNotice.describe(outcome))
        refreshAll()
        // 详情页正开着的话把它刷新，让用户立刻看到结果
        refreshDetailIfOpen(ticketId)
    }

    /** 详情页正打开着同一张票时才刷新，避免无谓的查询。 */
    private fun refreshDetailIfOpen(ticketId: Long) {
        if (_detail.value?.ticket?.id == ticketId) openTicket(ticketId)
    }

    // ---------------- 平台配置编辑 ----------------

    fun saveProvider(
        existing: AiProviderEntity?,
        name: String,
        kind: AiProviderKind,
        baseUrl: String,
        model: String,
        role: AiRole,
        apiKey: String?,
        timeoutSeconds: Int,
        maxRetries: Int,
        imageQuality: AiProviderConfig.ImageQuality,
        temperature: Double
    ) {
        viewModelScope.launch {
            val ref = existing?.apiKeyRef ?: "key_${System.currentTimeMillis()}"

            // 防「掩码覆盖真密钥」：编辑框里现在预填的是头尾各 3 字符的掩码，
            // 用户如果没动它（只是改了模型名就点保存），必须当「不修改」处理。
            // 不防的话密钥会被静默改成 `ark***e9b`，下次识别报 401 且完全看不出原因。
            val savedPlain = existing?.let { keys.get(it.apiKeyRef) }
            val effectiveKey = when {
                apiKey.isNullOrBlank() -> null
                savedPlain != null && ApiKeyMask.isUnchangedMask(apiKey, savedPlain) -> null
                else -> apiKey
            }
            if (!effectiveKey.isNullOrBlank()) keys.put(ref, effectiveKey)

            // 落库前补齐 /chat/completions：用户填基础地址也能跑
            val fullUrl = EndpointNormalizer.normalize(baseUrl)

            val entity = AiProviderEntity(
                id = existing?.id ?: 0,
                name = name.ifBlank { kind.display },
                provider = kind.code,
                enabled = true,
                baseUrl = fullUrl,
                model = model.trim(),
                apiKeyRef = ref,
                role = role.code,
                timeoutSeconds = timeoutSeconds,
                maxRetries = maxRetries,
                imageQuality = imageQuality.name,
                temperature = temperature
            )
            repo.saveProvider(entity)
            loadProviders()
            _toast.value = Notice("已保存「$name」")
        }
    }

    fun deleteProvider(id: Long) {
        viewModelScope.launch {
            repo.deleteProvider(id)
            loadProviders()
        }
    }

    fun hasKey(ref: String): Boolean = keys.has(ref)

    /**
     * 已保存 Key 的掩码（头尾各 3 字符，中间 3 个星号），没配过返回 null。
     *
     * ⚠️ 这里会**解密** Keystore 里的密文，有开销。调用方必须 `remember` 住，
     * 不要在每次重组时调 —— 每帧解一次会卡。
     */
    fun maskedKey(ref: String): String? = ApiKeyMask.mask(keys.get(ref))

    /**
     * 连通性测试。
     *
     * 三处细节：
     *  - [apiKeyOverride] 优先用编辑框里刚输入的 Key —— 用户改完 Key 不保存就想先测，是最高频场景；
     *    为空则回落到已保存的（[apiKeyRef] 指向的 Keystore 密文）。
     *  - [existing] 为 null 表示新建，此时没有 Key 可回落，必须用户当场填。
     *  - 测试产生的调用**不写 recognition_runs**（§19 只统计真实识别），只进 App 日志。
     */
    fun testProvider(
        existing: AiProviderEntity?,
        kind: AiProviderKind,
        baseUrl: String,
        model: String,
        timeoutSeconds: Int,
        apiKeyOverride: String?
    ) {
        viewModelScope.launch {
            _test.value = TestState.Running

            val savedKey = existing?.let { keys.get(it.apiKeyRef) }
            // 用户点「测试」时，编辑框里可能还显示着掩码（他只是想确认一下配置还通不通）。
            // 拿掩码去测必然 401，且会让人以为「Key 坏了」—— 这里当「没填」处理。
            val override = apiKeyOverride
                ?.takeIf { it.isNotBlank() }
                ?.takeUnless { savedKey != null && ApiKeyMask.isUnchangedMask(it, savedKey) }
            val key = override ?: savedKey.orEmpty()

            val report = com.bro.lotteryledger.ai.ConnectivityProbe.test(
                baseUrl = baseUrl,
                model = model,
                apiKey = key,
                timeoutSeconds = timeoutSeconds
            )

            // 附一条该平台专属排错贴士（只在失败时才有价值）
            val finalReport = if (!report.ok) {
                val tip = com.bro.lotteryledger.ai.FailureText.platformTip(kind)
                if (tip != null) report.copy(error = (report.error.orEmpty() + "\n\n【本平台提示】" + tip).trim())
                else report
            } else report

            _test.value = TestState.Done(finalReport)
        }
    }

    // ---------------- 备份与恢复（§17）----------------

    private val backupManager = com.bro.lotteryledger.repo.BackupManager(app)

    /**
     * 备份流程状态。
     *
     * `Preview` 里握着已解析好的快照，用户点「确认恢复」时直接用 ——
     * 不重新读文件。因为用户可能在预览期间把文件挪走/删掉，
     * 重读会失败；而且同一个文件读两次也没必要。
     */
    sealed interface BackupState {
        data object Idle : BackupState
        data object Working : BackupState
        data class Preview(val info: com.bro.lotteryledger.repo.BackupManager.Preview) : BackupState
    }

    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()

    private var pendingImport: com.bro.lotteryledger.repo.BackupCodec.Snapshot? = null

    /** 导出到用户选定的位置。 */
    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            _backupState.value = BackupState.Working
            try {
                val n = backupManager.exportTo(uri)
                _toast.value = Notice("已导出 $n 张票的备份")
            } catch (e: Exception) {
                LedgerLog.e("Backup", "导出失败", e)
                _toast.value = Notice("导出失败：${e.message}", NoticeKind.ERROR)
            }
            _backupState.value = BackupState.Idle
        }
    }

    /** 读取备份文件并算出差量，**不写库**，只弹预览让用户确认。 */
    fun previewImport(uri: Uri) {
        viewModelScope.launch {
            _backupState.value = BackupState.Working
            try {
                val snap = backupManager.readSnapshot(uri)
                pendingImport = snap
                _backupState.value = BackupState.Preview(backupManager.inspect(snap))
            } catch (e: Exception) {
                LedgerLog.e("Backup", "读取备份失败", e)
                _toast.value = Notice(e.message ?: "读取备份失败", NoticeKind.ERROR)
                _backupState.value = BackupState.Idle
            }
        }
    }

    fun confirmImport() {
        val snap = pendingImport ?: return
        viewModelScope.launch {
            _backupState.value = BackupState.Working
            try {
                val r = backupManager.restore(snap)
                _toast.value = Notice(
                    buildString {
                        append("恢复完成：新增 ${r.imported} 张")
                        if (r.skipped > 0) append("，跳过 ${r.skipped} 张（本机已有）")
                        if (r.failed > 0) append("，失败 ${r.failed} 张（看日志）")
                    }
                )
            } catch (e: Exception) {
                LedgerLog.e("Backup", "恢复失败", e)
                _toast.value = Notice("恢复失败：${e.message}", NoticeKind.ERROR)
            }
            pendingImport = null
            _backupState.value = BackupState.Idle
            // 设置可能被「补缺」补上了，重新读一次
            loadThinkingLevel()
            refreshAll()
        }
    }

    fun cancelImport() {
        pendingImport = null
        _backupState.value = BackupState.Idle
    }

    // ---------------- 删除票 ----------------

    fun deleteTicket(id: Long) {
        viewModelScope.launch {
            repo.deleteTicket(id)
            refreshAll()
            _toast.value = Notice("已删除")
        }
    }

    private fun AiProviderEntity.toConfig() = AiProviderConfig(
        id = id,
        name = name,
        provider = AiProviderKind.from(provider),
        enabled = enabled,
        baseUrl = baseUrl,
        model = model,
        apiKeyRef = apiKeyRef,
        role = AiRole.from(role),
        timeoutSeconds = timeoutSeconds,
        maxRetries = maxRetries,
        imageQuality = AiProviderConfig.ImageQuality.from(imageQuality),
        temperature = temperature,
        // 思考强度是全局设置，存 app_settings 表，这里注入进去
        thinkingLevel = _thinkingLevel.value
    )
}
