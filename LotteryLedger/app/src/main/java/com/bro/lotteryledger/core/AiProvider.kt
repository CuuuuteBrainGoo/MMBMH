package com.bro.lotteryledger.core

/**
 * AI 平台配置（§1.2 / §18）。
 *
 * 原则：不把平台、模型名、接口地址写死（§23.10）。
 * 这里只提供「默认值」作为用户填表时的便利，全部可被覆盖。
 */
enum class AiProviderKind(val code: String, val display: String) {
    DOUBAO("doubao", "豆包 / 火山方舟"),
    HUNYUAN("hunyuan", "腾讯混元"),
    ZHIPU("zhipu", "智谱"),
    QIANFAN("qianfan", "百度千帆"),
    ALIYUN("aliyun", "阿里云百炼"),
    OPENAI("openai", "OpenAI 兼容"),
    CUSTOM("custom", "自定义");

    companion object {
        fun from(code: String?): AiProviderKind =
            entries.firstOrNull { it.code == code } ?: CUSTOM
    }
}

enum class AiRole(val code: String, val display: String) {
    PRIMARY("primary", "主识别"),
    REVIEWER("reviewer", "复核"),
    BOTH("both", "两者兼用");

    companion object {
        fun from(code: String?): AiRole =
            entries.firstOrNull { it.code == code } ?: PRIMARY
    }
}

/**
 * AI 的「思考强度」。豆包使用 reasoning_effort；DeepSeek 官方快速档使用 thinking.type=disabled。
 *
 * **这是本 App 最大的一处提速**。实测（同一张票面、同一提示词、同一接入点）：
 *
 * | effort | 耗时 | 思维链 token | 正文长度 |
 * |---|---|---|---|
 * | minimal（快速档） | **3.4 秒** | 0 | 510 字 |
 * | low（均衡档） | 13.8 秒 | 242 | 508 字 |
 * | high（深度档） | 23.9 秒 | 609 | 508 字 |
 *
 * **三档的正文长度几乎一样（508~510 字）**，说明思考对「读票面」这个任务
 * 没有产生任何有价值的内容 —— 纯属额外开销。
 *
 * 特别注意：**`high` 是平台的默认值**，所以不显式传这个参数就会走深度分析（很慢）。
 * 这就是之前一次识别要 124 秒的根因。
 *
 * `none` / `minimal` 都是「关闭思考」，实测 `minimal` 略快，取 `minimal`。
 * `xhigh` / `max` 会被平台映射回 `high`，所以不提供这两档。
 */
enum class ThinkingLevel(val code: String, val display: String, val effort: String, val hint: String) {
    FAST("fast", "快速", "minimal", "请求关闭思考 · 实际速度取决于模型和网络"),
    BALANCED("balanced", "均衡", "low", "请求轻量思考 · 票面模糊时可试"),
    DEEP("deep", "深度", "high", "请求深度思考 · 通常更慢");

    companion object {
        fun from(code: String?): ThinkingLevel =
            entries.firstOrNull { it.code == code } ?: FAST
    }
}

interface AiCallable {
    fun call(cfg: AiProviderConfig, apiKey: String, imageBase64: String, prompt: String): AiCallResult
}

/**
 * 一个 AI 平台的配置。字段与交接稿 §18 的 JSON 示意一一对应。
 *
 * [apiKeyRef] 是指向 Keystore 密文的引用名，**不是** API Key 本身。
 */
data class AiProviderConfig(
    val id: Long = 0,
    val name: String,
    val provider: AiProviderKind,
    val enabled: Boolean = true,
    val baseUrl: String,
    val model: String,
    val apiKeyRef: String,
    val role: AiRole = AiRole.PRIMARY,
    /** 该平台适用的彩种；空表示全部适用（§1.2「支持未来按彩种指定不同模型」） */
    val lotteryTypes: List<LotteryType> = emptyList(),
    /**
     * 单次请求的读超时（秒）。
     *
     * 默认 240 —— 是个「兜底」值，正常路径根本用不到这么多。
     *
     * 真机实测的两组数据：
     *  - **关闭深度思考前**：双色球一次 124.4 秒、大乐透一次 46.3 秒（同一台手机差 2.7 倍）
     *  - **关闭深度思考后**（见 [OpenAiCompatibleClient.buildBody]）：约 4 秒量级
     *
     * 保留 240 是为了覆盖两种情况：网络/平台抖动，以及换成不支持关闭思考的平台。
     * 另外 OkHttp 的 readTimeout 是「两次读之间的间隔」而非总时长，
     * 它同时受 callTimeout（本值 + 30 秒）约束。
     */
    val timeoutSeconds: Int = 240,
    val maxRetries: Int = 1,
    /**
     * 思考强度。当前适配豆包与 DeepSeek 官方接口，未知平台不下发专有参数。
     *
     * 这个值来自全局设置（`app_settings` 表），不是每个平台单独配。
     */
    val thinkingLevel: ThinkingLevel = ThinkingLevel.FAST,
    val imageQuality: ImageQuality = ImageQuality.HIGH,
    val structuredOutput: Boolean = true,
    val temperature: Double = 0.0
) {
    enum class ImageQuality(val maxEdge: Int, val jpegQuality: Int) {
        HIGH(2000, 90),
        MEDIUM(1500, 82),
        LOW(1100, 72);

        companion object {
            fun from(name: String?): ImageQuality =
                entries.firstOrNull { it.name.equals(name, true) } ?: HIGH
        }
    }
}

/**
 * 把用户填的接口地址规范成**真正可 POST 的 chat/completions 端点**。
 *
 * 为什么需要它：各平台控制台展示的都是「基础地址」（如
 * `https://ark.cn-beijing.volces.com/api/v3`），用户照着填，
 * 请求就会打到基础地址上，网关按未知路径返回 404。
 * 用户没有义务知道要补 `/chat/completions` —— 这里替他补。
 *
 * 规则：
 *  - 空 / 非法  → 原样返回（由调用方报「接口地址未填写」）
 *  - 已带 /chat/completions（含结尾斜杠）→ 去掉尾部斜杠后原样返回
 *  - 其余 → 补 `/chat/completions`
 *
 * 只做「补全」，不做「纠正」：不认识的路径不猜（§2.3 同源原则）。
 */
object EndpointNormalizer {

    private const val SUFFIX = "/chat/completions"

    fun normalize(raw: String): String {
        val s = raw.trim()
        if (s.isBlank()) return ""
        // 用 URL 结构解析，避免纯字符串判断误伤（如 path 里带 chat/completions 的 CDN 前缀）
        val parsed = try {
            java.net.URL(s)
        } catch (_: Exception) {
            return s
        }
        if (parsed.protocol == null || parsed.host.isNullOrBlank()) return s

        // 剥掉 query / fragment，它们不属于端点路径
        val sb = StringBuilder()
        sb.append(parsed.protocol).append("://").append(parsed.authority)
        sb.append(parsed.path.orEmpty().trimEnd('/'))
        val base = sb.toString()

        return if (base.endsWith(SUFFIX)) base else base + SUFFIX
    }
}

/** 一次 AI 调用的结果，用于落 [RecognitionRun] 统计（§1.2 要求记录 token/耗时）。 */
data class AiCallResult(
    val rawText: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val latencyMs: Long,
    val httpStatus: Int?,
    val error: String? = null,
    /** 平台报告的思考 token 数；未返回时为 null，不能当成没有思考。 */
    val reasoningTokens: Int? = null,
    val attemptCount: Int = 0
) {
    val ok: Boolean get() = error == null && rawText != null
    val totalTokens: Int? get() = if (promptTokens != null && completionTokens != null) promptTokens + completionTokens else null
}

/** 识别运行记录（§19 recognition_runs）。 */
data class RecognitionRun(
    val id: Long = 0,
    val ticketDraftId: Long? = null,
    val provider: String,
    val model: String,
    val requestType: String,
    val result: String,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val latencyMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 各平台的默认接入信息。仅作「用户新建配置时的预填值」，用户可任意修改。
 *
 * 已核实（2026-09-26）：
 *  - DOUBAO   https://ark.cn-beijing.volces.com/api/v3        ep-<你自己的接入点>
 *  - HUNYUAN  https://api.hunyuan.cloud.tencent.com/v1        hunyuan-vision
 *  - ZHIPU    https://open.bigmodel.cn/api/paas/v4            glm-4v-plus
 *  - QIANFAN  https://qianfan.baidubce.com/v2                 ernie-4.5-turbo-vl
 *  - ALIYUN   https://dashscope.aliyuncs.com/compatible-mode/v1  qwen-vl-max-latest
 *  - OPENAI   https://api.openai.com/v1                       gpt-4o
 *
 * 地址一律填**基础地址**（不带 /chat/completions）—— 反正保存和请求前都会
 * 由 [EndpointNormalizer] 补齐，用户从控制台照抄即可，不用自己拼路径。
 */
object ProviderDefaults {
    data class Template(
        val baseUrl: String,
        val model: String,
        val note: String,
        val display: String = "",
        /** 接口地址框下方的短说明（一句话说清「这是什么、去哪抄」） */
        val baseUrlHint: String = "",
        /** 模型/接入点框下方的短说明 */
        val modelHint: String = "",
        /** 从哪里拿 API Key（含控制台入口） */
        val keyHint: String = "",
        /** 平台官网 / 控制台入口（帮助页跳转用） */
        val consoleUrl: String = "",
        /** 平台官方文档入口（帮助页跳转用） */
        val docsUrl: String = ""
    )

    /** 各输入框的**通用**短说明，供没有平台特异文案时兜底。 */
    object Hints {
        const val NAME = "随便起个好认的名字，比如「豆包-主力」。只影响你自己的列表显示。"
        const val BASE_URL =
            "平台控制台里给的「接口地址 / Base URL」，通常以 /v1 或 /api/v3 结尾。" +
                "不用自己加 /chat/completions，App 保存时会自动补上。"
        const val MODEL = "要有「看图」能力的模型名。纯文本模型读不了彩票照片。"
        const val API_KEY =
            "平台的密钥，只保存在这台手机里（系统级加密），不会上传、也不会进备份文件。"
        const val TIMEOUT = "等待 AI 回复的最长时间。识别一张票通常 10～30 秒，保持 240 就行。"
        const val RETRIES = "网络抖动导致的失败会自动重试。1 次够用，机房网络差可以调到 2。"
    }

    /**
     * 接入点 ID 的公开占位符，不包含任何账号资源标识。
     * 用户也可改填已开通模型的 Model ID，两者都原样进入请求的 model 字段。
     * 以控制台调用示例为准；使用接入点时需与接口区域一致。
     */
    const val DOUBAO_ENDPOINT_ID = "ep-xxxxxxxxxxxxxxxx"

    fun template(kind: AiProviderKind): Template {
        val t = rawTemplate(kind)
        return if (t.display.isBlank()) t.copy(display = kind.display) else t
    }

    private fun rawTemplate(kind: AiProviderKind): Template = when (kind) {
        AiProviderKind.DOUBAO -> Template(
            "https://ark.cn-beijing.volces.com/api/v3",
            DOUBAO_ENDPOINT_ID,
            "填写已开通模型的 Model ID，或推理接入点 ID（ep- 开头）。" +
                "从控制台「调用示例」复制 model 字段，不要抄页面上的展示名称。" +
                "使用接入点时，接口地址要和接入点的区域一致（当前预填北京区）。",
            baseUrlHint = "方舟控制台页面顶部就能看到，北京区照抄即可。",
            modelHint = "从控制台「调用示例」复制 model 字段，支持模型 ID 或 ep- 开头的接入点 ID；需先开通对应模型。",
            keyHint = "方舟控制台左侧「API Key 管理」里创建，形如 xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx。",
            consoleUrl = "https://console.volcengine.com/ark",
            docsUrl = "https://www.volcengine.com/docs/82379/1298454"
        )
        AiProviderKind.HUNYUAN -> Template(
            "https://api.hunyuan.cloud.tencent.com/v1",
            "hunyuan-vision",
            "腾讯混元 OpenAI 兼容端点。另有新版端点 https://tokenhub.tencentmaas.com/v1（模型 hy-vision-2.0-instruct），" +
                "两个都可用，先用上面这个更稳。",
            baseUrlHint = "腾讯云混元控制台的「OpenAI 兼容」接口地址，照抄即可。",
            modelHint = "选带 vision 的视觉模型，比如 hunyuan-vision。",
            keyHint = "腾讯云控制台「访问管理 → API 密钥管理」里创建，形如 AKIDxxxxxxxx。",
            consoleUrl = "https://console.cloud.tencent.com/hunyuan",
            docsUrl = "https://cloud.tencent.com/document/product/1729/111007"
        )
        AiProviderKind.ZHIPU -> Template(
            "https://open.bigmodel.cn/api/paas/v4",
            "glm-4v-plus",
            "智谱。视觉模型可选 glm-4v-plus / glm-4v / glm-4v-flash；" +
                "其中 glm-4v-flash 免费，适合先拿来测通配置。单次最多 5 张图。",
            baseUrlHint = "智谱开放平台文档里的基础地址，固定就是这个。",
            modelHint = "推荐先用免费的 glm-4v-flash 测通，够用再换 glm-4v-plus。",
            keyHint = "智谱开放平台「API Keys」页面创建，形如 xxxxxxxx.xxxxxxxx（中间有个点）。",
            consoleUrl = "https://open.bigmodel.cn/console/overview",
            docsUrl = "https://docs.bigmodel.cn/cn/guide/models/vlm/glm-4v"
        )
        AiProviderKind.QIANFAN -> Template(
            "https://qianfan.baidubce.com/v2",
            "ernie-4.5-turbo-vl",
            "百度千帆。注意 Key 形如 bce-v3/ALTAK-xxx/xxx，不是 sk- 开头；" +
                "且必须先在控制台「开通管理」里开通所用模型，否则报「模型不存在」。",
            baseUrlHint = "千帆控制台「应用接入」里给的 OpenAI 兼容地址。",
            modelHint = "需要带 vl（视觉）的模型，如 ernie-4.5-turbo-vl。",
            keyHint = "千帆控制台「应用接入」里创建应用后拿到的 Key，形如 bce-v3/ALTAK-xxx/xxx。",
            consoleUrl = "https://console.bce.baidu.com/qianfan/ais/console/applicationConsole/application",
            docsUrl = "https://cloud.baidu.com/doc/qianfan-api/s/3m7of64lb"
        )
        AiProviderKind.ALIYUN -> Template(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen-vl-max-latest",
            "阿里云百炼。视觉模型可选 qwen-vl-max-latest / qwen-vl-plus-latest / qwen3-vl-plus。" +
                "官方建议后续迁到业务空间专属域名（形如 https://你的空间ID.cn-beijing.maas.aliyuncs.com），" +
                "上面这个公共域名当前仍可用。",
            baseUrlHint = "百炼控制台「模型服务」里给的兼容模式地址，注意结尾有 compatible-mode。",
            modelHint = "选 vl 开头的通义千问视觉模型，如 qwen-vl-max-latest。",
            keyHint = "百炼控制台右上角「API-KEY」里创建，形如 sk-xxxxxxxx。",
            consoleUrl = "https://bailian.console.aliyun.com/",
            docsUrl = "https://help.aliyun.com/zh/model-studio/vision"
        )
        AiProviderKind.OPENAI -> Template(
            "https://api.openai.com/v1",
            "gpt-4o",
            "标准 OpenAI 兼容格式。任何兼容 /chat/completions 协议的服务都可填在这里。",
            baseUrlHint = "OpenAI 官方地址，或任何兼容服务的地址（通常以 /v1 结尾）。",
            modelHint = "要能看图的模型，如 gpt-4o / gpt-4o-mini。",
            keyHint = "platform.openai.com 的「API keys」页面创建，形如 sk-xxxxxxxx。",
            consoleUrl = "https://platform.openai.com/api-keys",
            docsUrl = "https://platform.openai.com/docs/guides/vision"
        )
        AiProviderKind.CUSTOM -> Template(
            "", "",
            "请填写平台的基础地址或完整的 chat/completions 地址，" +
                "App 会自动补齐路径。请求体使用 OpenAI 兼容的 image_url（base64 data URI）格式。",
            baseUrlHint = Hints.BASE_URL,
            modelHint = Hints.MODEL,
            keyHint = Hints.API_KEY
        )
    }

    /** 判断某配置是否适用于某彩种（§1.2「支持按彩种指定不同模型」）。 */
    fun applies(cfg: AiProviderConfig, type: LotteryType): Boolean =
        cfg.enabled && (cfg.lotteryTypes.isEmpty() || type in cfg.lotteryTypes)

    /** 帮助页用：只需要有跳转链接的平台（CUSTOM 没有官网，排除）。 */
    val documentedKinds: List<AiProviderKind>
        get() = AiProviderKind.entries.filter { rawTemplate(it).consoleUrl.isNotBlank() }
}
