package com.bro.lotteryledger.net

import com.bro.lotteryledger.core.DrawNumbers
import com.bro.lotteryledger.core.LedgerLog
import com.bro.lotteryledger.core.LotteryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 官方开奖结果查询（§12）。
 *
 * 两个接口（2026-09-26 实测通，返回结构以实测为准，不是猜的）：
 *
 * **双色球**（中国福彩网）
 * ```
 * GET https://www.cwl.gov.cn/cwl_admin/front/cwlkj/search/kjxx/findDrawNotice?name=ssq&issueCount=N
 * 必须带 Referer，否则被拦
 * result[].code=期号 / red="06,07,14,22,31,32" / blue="05"
 *          date="2026-09-24(四)"（**带星期，要截断**）
 *          poolmoney="966038984"（奖池） prizegrades[].typemoney=各奖级单注金额
 * ```
 *
 * **大乐透**（中国体彩网）
 * ```
 * GET https://webapi.sporttery.cn/gateway/lottery/getHistoryPageListV1.qry?gameNo=85&provinceId=0&pageSize=N&isVerify=1&pageNo=1
 * value.list[].lotteryDrawNum=期号 / lotteryDrawResult="12 14 16 27 34 04 08"（前5后2）
 *             lotteryDrawTime="2026-09-23"
 *             poolBalanceAfterdraw="875,294,743.09"（**带千分位逗号，要去掉**）
 *             prizeLevelList[].prizeLevel="三等奖" stakeAmount="6,666"
 * ```
 *
 * ## 两个设计取舍
 *
 * 1. **拉最近 50 期再本地匹配期号**，而不是按单个期号精确查。
 *    彩票只有 60 天兑奖期，双色球每周 3 期 → 最多 26 期，50 期足够覆盖；
 *    这样一次请求能同时满足「对最新一期对奖」和「翻旧票补对奖」两种场景。
 * 2. **奖池金额要拿**：大乐透固定奖是否升档（≥8 亿）完全取决于它，
 *    拿不到就没法算准钱。实测这个字段两个接口都给。
 */
class DrawResultClient(
    private val okHttp: OkHttpClient = defaultClient()
) {

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * 每次拉取多少期。
         *
         * ⚠️ 这个数字曾经是 **50**，直接导致了少爷报的 bug：
         * 双色球每周开 3 期，50 期只覆盖约 3.8 个月。他扫的是 4 月份的票
         * （距今 5 个月），接口里根本没有那一期 →
         * 被判成「官方还没公布」→ 状态永远停在「等待开奖结果」、反复自动核验、永远出不来。
         *
         * 200 期覆盖约 15 个月，把 60 天兑奖期和「翻旧票补对奖」都装得下。
         * 代价是响应体变大（几百 KB 一次），所以**同一轮核验内必须复用缓存**
         * （见 [allOf]），否则批量核验会变成 N 张票 N 次全量拉取。
         */
        private const val PAGE = 200

        /** 缓存有效期。太旧的数据会让用户拿到过期的开奖结果。 */
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        private const val SSQ_URL =
            "https://www.cwl.gov.cn/cwl_admin/front/cwlkj/search/kjxx/findDrawNotice" +
                "?name=ssq&issueCount=$PAGE"

        private const val DLT_URL =
            "https://webapi.sporttery.cn/gateway/lottery/getHistoryPageListV1.qry" +
                "?gameNo=85&provinceId=0&pageSize=$PAGE&isVerify=1&pageNo=1"

        private val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }

    // ---------------- 请求内缓存 ----------------

    private val cache = java.util.concurrent.ConcurrentHashMap<LotteryType, List<Fetched>>()
    private var cacheAt = 0L

    /**
     * 取某彩种的一整批（带缓存）。
     *
     * 批量核验 13 张票时，如果不缓存就是 13 次全量拉取 —— 每次几百 KB，
     * 既慢又浪费对方的带宽（官方接口是公益性质的，不该这样薅）。
     *
     * @return 名单；**null 表示这次请求/解析失败**（网络问题、接口结构变了），
     *         跟「请求成功但里面没有这一期」是**两件完全不同的事** ——
     *         前者该重试，后者说明期号有问题。以前两者都返回 null，混在一起，
     *         直接导致了少爷那个「永远等待开奖结果」的 bug。
     */
    private fun allOf(type: LotteryType): List<Fetched>? {
        val now = System.currentTimeMillis()
        if (now - cacheAt > CACHE_TTL_MS) {
            cache.clear()
            cacheAt = now
        }
        cache[type]?.let { return it }

        val body = get(if (type == LotteryType.SSQ) SSQ_URL else DLT_URL, type) ?: return null
        val parsed = parseAll(type, body)
        // 解析出空列表说明接口结构变了（正常情况一定有一堆期号），当成失败处理
        if (parsed.isEmpty()) return null

        cache[type] = parsed
        return parsed
    }

    /** 手动核验前清缓存，保证拿到的是最新公告（晚上 22:30 后尤其重要）。 */
    fun clearCache() {
        cache.clear()
        cacheAt = 0L
    }

    /** 一次查询的结果。 */
    data class Fetched(
        val numbers: DrawNumbers,
        /** 奖池金额（元）。拿不到为 null。 */
        val poolYuan: Double?,
        /** 大乐透是否适用「奖池 ≥8 亿」的升档规则 */
        val poolHigh: Boolean,
        /**
         * 官方公布的单注奖金：奖级 → 元。
         *
         * 注意：**一二等奖这里也有值**（官方公告里就是有的），
         * 但按 §15 仍要用户确认后才入账 —— 官方值只作为预填默认值，
         * 省得用户自己去翻公告。
         */
        val officialMoney: Map<Int, Double>
    )

    /**
     * 查某一期的结果。三种结局分得很清楚 —— 调用方要据此决定「重试」还是「报异常」。
     */
    sealed interface Lookup {
        /** 查到了 */
        data class Found(val data: Fetched) : Lookup

        /**
         * 接口正常，但列表里**没有这一期**。
         *
         * 常见原因：① 这期刚开奖、官方还没挂出来；② 期号被 AI 读错了；
         * ③ 超出接口能返回的历史范围。
         * 调用方要结合票面的开奖日期来判断到底是哪一种。
         */
        data object NotInList : Lookup

        /** 网络 / 解析失败。该重试，不该改变票的状态语义。 */
        data class RequestFailed(val message: String) : Lookup
    }

    /**
     * 查某一期（区分三种结局的版本）。
     *
     * 老的 [fetch] 把「查不到」和「请求失败」都压成 null，
     * 调用方无法区分，只能一律当成「官方还没公布」—— 这就是少爷那三张
     * 4 月份票永远卡在「等待开奖结果」的根因。
     */
    suspend fun lookup(type: LotteryType, issue: String): Lookup = withContext(Dispatchers.IO) {
        try {
            val all = allOf(type)
                ?: return@withContext Lookup.RequestFailed("开奖查询接口没有返回可用数据")
            val hit = all.firstOrNull { it.numbers.issue == issue }
            if (hit != null) Lookup.Found(hit) else Lookup.NotInList
        } catch (e: Exception) {
            LedgerLog.e("Draw", "查询${type.display}第 $issue 期开奖失败", e)
            Lookup.RequestFailed(e.message ?: "未知错误")
        }
    }

    /**
     * 查某一期。
     *
     * @return 查不到（网络失败 / 该期还没开奖 / 期号对不上）返回 null，由调用方决定提示
     */
    suspend fun fetch(type: LotteryType, issue: String): Fetched? = withContext(Dispatchers.IO) {
        try {
            when (type) {
                LotteryType.SSQ -> fetchSsq(issue)
                LotteryType.DLT -> fetchDlt(issue)
            }
        } catch (e: Exception) {
            LedgerLog.e("Draw", "查询${type.display}第 $issue 期开奖失败", e)
            null
        }
    }

    /** 拉一批最近的期号（用于「哪些票可以对了」的判断，或界面展示）。 */
    suspend fun fetchRecent(type: LotteryType): List<DrawNumbers> = withContext(Dispatchers.IO) {
        try {
            allOf(type)?.map { it.numbers } ?: emptyList()
        } catch (e: Exception) {
            LedgerLog.e("Draw", "拉取${type.display}最近开奖失败", e)
            emptyList()
        }
    }

    // ---------------- 双色球 ----------------

    private fun fetchSsq(issue: String): Fetched? =
        allOf(LotteryType.SSQ)?.firstOrNull { it.numbers.issue == issue }

    // ---------------- 大乐透 ----------------

    private fun fetchDlt(issue: String): Fetched? =
        allOf(LotteryType.DLT)?.firstOrNull { it.numbers.issue == issue }

    private fun get(url: String, type: LotteryType): String? {
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", UA)
            .addHeader("Accept", "application/json, text/plain, */*")
            .addHeader("Accept-Language", "zh-CN,zh;q=0.9")
            .apply {
                // 两家都校验来源，缺了会被拦（实测双色球不带给 403/空体）
                if (type == LotteryType.SSQ) {
                    addHeader("Referer", "https://www.cwl.gov.cn/ygkj/wqkjgg/ssq/")
                    addHeader("X-Requested-With", "XMLHttpRequest")
                } else {
                    addHeader("Referer", "https://static.sporttery.cn/")
                    addHeader("Origin", "https://static.sporttery.cn")
                }
            }
            .build()

        okHttp.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                LedgerLog.w("Draw", "HTTP ${resp.code}：${text.take(200)}")
                return null
            }
            return text
        }
    }

    /** 解析某平台的整批返回。 */
    private fun parseAll(type: LotteryType, body: String): List<Fetched> = when (type) {
        LotteryType.SSQ -> parseSsq(body)
        LotteryType.DLT -> parseDlt(body)
    }

    private fun parseSsq(body: String): List<Fetched> {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return emptyList()
        }
        val arr = root.optJSONArray("result") ?: return emptyList()
        val out = mutableListOf<Fetched>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val issue = o.optString("code").trim()
            val red = splitNumbers(o.optString("red"))
            val blue = splitNumbers(o.optString("blue"))
            if (issue.isEmpty() || red.size != 6 || blue.size != 1) continue

            val pool = o.optString("poolmoney").toDoubleOrNull()

            // 官方给了各奖级单注金额，type 1..6 对应一到六等奖
            val money = mutableMapOf<Int, Double>()
            o.optJSONArray("prizegrades")?.let { pg ->
                for (j in 0 until pg.length()) {
                    val g = pg.optJSONObject(j) ?: continue
                    val t = g.optInt("type", -1)
                    // type 7 是福运奖（特别派奖期间才出现），不是常规奖级
                    if (t !in 1..6) continue
                    g.optString("typemoney").toDoubleOrNull()?.let { money[t] = it }
                }
            }

            out += Fetched(
                numbers = DrawNumbers(
                    type = LotteryType.SSQ,
                    issue = issue,
                    drawDate = normalizeDate(o.optString("date")),
                    main = red,
                    second = blue
                ),
                poolYuan = pool,
                poolHigh = false,        // 双色球没有奖池升档机制
                officialMoney = money
            )
        }
        return out
    }

    private fun parseDlt(body: String): List<Fetched> {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return emptyList()
        }
        val value = root.optJSONObject("value") ?: return emptyList()
        val arr = value.optJSONArray("list") ?: return emptyList()

        val out = mutableListOf<Fetched>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val issue = o.optString("lotteryDrawNum").trim()
            val all = splitNumbers(o.optString("lotteryDrawResult"))
            // 前区 5 个 + 后区 2 个
            if (issue.isEmpty() || all.size != 7) continue

            // 奖池：优先用开奖后余额，它才是"这期适用哪档"的依据
            val pool = o.optString("poolBalanceAfterdraw").cleanNumber()
                ?: o.optString("poolBalance").cleanNumber()

            val money = mutableMapOf<Int, Double>()
            o.optJSONArray("prizeLevelList")?.let { pl ->
                for (j in 0 until pl.length()) {
                    val g = pl.optJSONObject(j) ?: continue
                    val name = g.optString("prizeLevel")
                    // 「一等奖(追加)」是独立一条，跳过它，只取基本投注的金额
                    if (name.contains("追加")) continue
                    val level = dltLevelOf(name) ?: continue
                    g.optString("stakeAmountFormat").cleanNumber()?.let { money[level] = it }
                }
            }

            out += Fetched(
                numbers = DrawNumbers(
                    type = LotteryType.DLT,
                    issue = issue,
                    drawDate = normalizeDate(o.optString("lotteryDrawTime")),
                    main = all.take(5),
                    second = all.drop(5).take(2)
                ),
                poolYuan = pool,
                poolHigh = (pool ?: 0.0) >= 800_000_000.0,
                officialMoney = money
            )
        }
        return out
    }

    // ---------------- 工具 ----------------

    /** "06,07,14,22,31,32" / "12 14 16 27 34 04 08" → 列表（保持两位） */
    private fun splitNumbers(s: String): List<String> =
        s.trim().split(",", " ", "，", "+")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.padStart(2, '0') }

    /** "2026-09-24(四)" → "2026-09-24" */
    private fun normalizeDate(s: String): String {
        val t = s.trim()
        val m = Regex("(\\d{4}-\\d{2}-\\d{2})").find(t)
        return m?.groupValues?.get(1) ?: t
    }

    /** "875,294,743.09" → 875294743.09；"---" / "-1" → null */
    private fun String.cleanNumber(): Double? {
        val t = trim().replace(",", "").replace("，", "")
        if (t.isEmpty() || t == "---" || t == "-1" || t == "--") return null
        return t.toDoubleOrNull()
    }

    /** "三等奖" → 3 */
    private fun dltLevelOf(name: String): Int? = when (name.trim()) {
        "一等奖" -> 1
        "二等奖" -> 2
        "三等奖" -> 3
        "四等奖" -> 4
        "五等奖" -> 5
        "六等奖" -> 6
        "七等奖" -> 7
        else -> null
    }
}
