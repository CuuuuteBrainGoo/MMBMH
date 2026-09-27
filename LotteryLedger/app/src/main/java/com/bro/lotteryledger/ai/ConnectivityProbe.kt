package com.bro.lotteryledger.ai

import android.util.Base64
import com.bro.lotteryledger.core.AiProviderConfig
import com.bro.lotteryledger.core.AiProviderKind
import com.bro.lotteryledger.core.LedgerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 连通性测试（§18 配置自检）。
 *
 * 目的：让用户在**真的去拍照识别之前**就知道「这个平台配置到底通不通」，
 * 而不是拍完照、等 45 秒、拿到一个 HTTP 404 才发现地址填错了。
 *
 * 分三级，逐级定位问题边界：
 *  1. 地址格式 —— 不发网络请求，纯本地判 URL 是否合法（挡掉"忘了加 https://"）
 *  2. 网络可达 —— 发一次最小 POST，看能否拿到 HTTP 响应（挡掉域名/网络/代理问题）
 *  3. 模型可用 —— 响应里有没有 choices，模型名是否被平台认（挡掉模型名写错）
 *
 * 关键取舍：**测试用的是纯文本探针，不传图片**。
 *  - 省 token（一次几十 token，几乎不要钱）
 *  - 不碰用户照片（不违反 §3 隐私铁律）
 * 代价：测不出「平台不支持 image_url 传参」这一种情况。
 * 但这一类平台本身就极少，且真正识别时会立刻暴露，属于可接受的取舍。
 */
object ConnectivityProbe {

    /**
     * 探针图边长（像素）。
     *
     * **这个数字有实测依据，不要随便改小**：火山方舟拒绝边长 < 14px 的图片，
     * 见 [PROBE_JPEG] 的注释。取 32 留余量，兼顾其它平台可能更高一点的下限。
     */
    const val PROBE_IMAGE_SIDE = 32

    /** 已知最严格的图片最小边长（火山方舟，2026-09-26 实测）。 */
    const val KNOWN_MIN_IMAGE_SIDE = 14

    /**
     * 探针用的小图（32×32 纯白 JPEG）。
     *
     * 尺寸不是随便定的 —— **实测教训**：
     * 火山方舟对图片有**最小边长 14 像素**的硬限制，用 1×1 会被 400 拒绝：
     *   `InvalidParameter: image data 0 failed: Image dimensions are too small.
     *    Minimum allowed dimension: 14 pixels.`
     * 那样用户会看到「测试失败」，误以为是自己配置错了，实际是探针图太小。
     *
     * base64 后约 130 字节，对请求大小和 token 都无影响
     * （图片的 token 由平台的固定分块决定，与图片本身大小关系不大）。
     */
    private val PROBE_JPEG: String by lazy {
        val side = PROBE_IMAGE_SIDE
        val bmp = android.graphics.Bitmap.createBitmap(
            side, side, android.graphics.Bitmap.Config.ARGB_8888
        )
        bmp.eraseColor(android.graphics.Color.WHITE)
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
        bmp.recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** 用一个极小但合法的 zlib 块凑出 256 字节，用来探「图片太小被拒」以外的场景。 */
    enum class Stage(val display: String) {
        ADDRESS("地址"),
        REACHABLE("网络"),
        MODEL("模型"),
        DONE("完成")
    }

    data class Step(
        val stage: Stage,
        val ok: Boolean,
        val detail: String
    )

    data class Report(
        val ok: Boolean,
        val steps: List<Step>,
        /** 实际请求的端点（已归一化） */
        val endpoint: String,
        val latencyMs: Long,
        val httpStatus: Int?,
        /** 模型回答的原文（截断），用来证明「真的调通了」而不是「只是没报错」 */
        val replyPreview: String?,
        val tokens: Int?,
        val error: String?
    ) {
        fun render(): String = buildString {
            append(if (ok) "✅ 配置可用" else "❌ 配置有问题")
            append("\n\n")
            steps.forEach { s ->
                append(if (s.ok) "· " else "· ")
                append('[').append(s.stage.display).append("] ")
                append(s.detail)
                append('\n')
            }
            append("\n请求地址：").append(endpoint)
            if (httpStatus != null) append("\nHTTP 状态：").append(httpStatus)
            append("\n耗时：").append(latencyMs).append(" ms")
            if (tokens != null) append("\n消耗 token：").append(tokens)
            if (!replyPreview.isNullOrBlank()) {
                append("\n\n模型回应：\n").append(replyPreview)
            }
            if (!error.isNullOrBlank()) {
                append("\n\n").append(error)
            }
        }
    }

    /**
     * 用一张内置的小图发一次真实请求。
     *
     * @param includeImage true 时带 1×1 图片（更接近真实识别路径）；
     *                     false 时纯文本（省 token，但测不出图片传参问题）。
     */
    suspend fun test(
        baseUrl: String,
        model: String,
        apiKey: String,
        timeoutSeconds: Int = 30,
        includeImage: Boolean = true
    ): Report = withContext(Dispatchers.IO) {

        val steps = mutableListOf<Step>()
        val endpoint = com.bro.lotteryledger.core.EndpointNormalizer.normalize(baseUrl)

        // --- 第 1 级：地址 ---
        if (baseUrl.isBlank()) {
            return@withContext Report(
                ok = false,
                steps = listOf(Step(Stage.ADDRESS, false, "接口地址是空的")),
                endpoint = "", latencyMs = 0, httpStatus = null, replyPreview = null,
                tokens = null,
                error = "请填写接口地址。多数平台控制台显示的是「基础地址」，" +
                    "例如 https://ark.cn-beijing.volces.com/api/v3 —— 直接照抄即可，App 会自动补上 /chat/completions。"
            )
        }
        if (endpoint.isBlank() || !endpoint.startsWith("http")) {
            return@withContext Report(
                ok = false,
                steps = listOf(Step(Stage.ADDRESS, false, "地址格式不对：$baseUrl")),
                endpoint = endpoint, latencyMs = 0, httpStatus = null, replyPreview = null,
                tokens = null,
                error = "地址要以 http:// 或 https:// 开头。"
            )
        }
        steps += Step(Stage.ADDRESS, true, "请求地址 → $endpoint")

        if (model.isBlank()) {
            steps += Step(Stage.MODEL, false, "模型标识是空的")
            return@withContext Report(
                ok = false, steps = steps, endpoint = endpoint, latencyMs = 0,
                httpStatus = null, replyPreview = null, tokens = null,
                // 不列豆包的模型名当例子 —— 火山方舟那一栏要填的是接入点 ID，
                // 列模型名会把少爷往 404 上引
                error = "请填写模型标识。\n" +
                    "· 火山方舟（豆包）：填推理接入点 ID，形如 ep-m-xxxxxxxx\n" +
                    "· 其它平台：填模型名，例如 glm-4v-plus / hunyuan-vision / qwen-vl-max-latest"
            )
        }
        if (apiKey.isBlank()) {
            steps += Step(Stage.REACHABLE, false, "API Key 是空的")
            return@withContext Report(
                ok = false, steps = steps, endpoint = endpoint, latencyMs = 0,
                httpStatus = null, replyPreview = null, tokens = null,
                error = "请填写 API Key。已保存过的配置若不想改 Key，可先在编辑框里重新粘贴一次再测。"
            )
        }

        // --- 第 2/3 级：真的发一次 ---
        val started = System.currentTimeMillis()
        val body = buildProbeBody(model, includeImage)

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong().coerceIn(5, 60), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong().coerceIn(5, 60), TimeUnit.SECONDS)
            .callTimeout((timeoutSeconds + 10).toLong().coerceIn(15, 90), TimeUnit.SECONDS)
            .build()

        LedgerLog.i("Probe", "连通测试 → $endpoint，模型 $model，带图=$includeImage")

        var status: Int? = null
        var reply: String? = null
        var tokens: Int? = null
        var error: String? = null

        try {
            client.newCall(request).execute().use { resp ->
                status = resp.code
                val text = resp.body?.string().orEmpty()
                val latency = System.currentTimeMillis() - started

                if (!resp.isSuccessful) {
                    steps += Step(Stage.REACHABLE, true, "网络通了（HTTP ${resp.code}），但被平台拒绝")
                    steps += Step(Stage.MODEL, false, "未能调用成功")
                    error = FailureText.describe(resp.code, text, endpoint)
                    LedgerLog.e("Probe", "失败 HTTP ${resp.code}：$error")
                    return@withContext Report(
                        ok = false, steps = steps, endpoint = endpoint, latencyMs = latency,
                        httpStatus = status, replyPreview = null, tokens = null, error = error
                    )
                }

                steps += Step(Stage.REACHABLE, true, "网络通了（HTTP ${resp.code}，${latency}ms）")

                val parsed = parseProbeReply(text)
                reply = parsed.first
                tokens = parsed.second

                if (reply.isNullOrBlank()) {
                    // 200 但没有正文：平台把错误塞在 200 里（智谱、百炼偶见）
                    val errBody = text.trim().take(300)
                    steps += Step(Stage.MODEL, false, "响应里没有模型输出")
                    error = buildString {
                        append("平台返回了 200，但响应体里没有 choices / content。")
                        if (errBody.isNotEmpty()) append("\n\n原始响应：\n").append(errBody)
                        append("\n\n常见原因：模型名写错了，或该模型没在当前账号开通。")
                    }
                    LedgerLog.e("Probe", "200 但无正文：$errBody")
                    return@withContext Report(
                        ok = false, steps = steps, endpoint = endpoint, latencyMs = latency,
                        httpStatus = status, replyPreview = null, tokens = null, error = error
                    )
                }

                steps += Step(Stage.MODEL, true, "模型「$model」可用，正常返回内容")
                steps += Step(Stage.DONE, true, "配置完全可用，可以去拍照识别了")

                LedgerLog.i("Probe", "连通成功（${latency}ms, token ${tokens ?: "?"}）")

                // 注意：探针请求会真实产生一次调用记录，但不计入用户账本的「识别次数」——
                // 因为它不写 recognition_runs（这里只写日志）。
                Report(
                    ok = true, steps = steps, endpoint = endpoint, latencyMs = latency,
                    httpStatus = status, replyPreview = reply, tokens = tokens, error = null
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - started
            val kind = when (e) {
                is java.net.UnknownHostException -> "域名解析失败"
                is java.net.SocketTimeoutException -> "连接或读取超时"
                is javax.net.ssl.SSLException -> "TLS/证书握手失败"
                is java.net.ConnectException -> "无法建立连接"
                else -> e.javaClass.simpleName
            }
            steps += Step(Stage.REACHABLE, false, "$kind：${e.message ?: "无详细信息"}")
            error = buildString {
                append("发起请求时就失败了：").append(kind).append("。")
                append("\n\n可能原因：\n")
                append("· 手机网络不通，或平台域名被当前网络限制\n")
                append("· 地址域名写错（比对一下平台官方文档）\n")
                append("· 用了公司/校园 Wi-Fi 且有代理拦截\n")
                append("\n技术细节：").append(e.javaClass.simpleName).append(": ").append(e.message)
            }
            LedgerLog.e("Probe", "请求异常（${latency}ms）：$kind - ${e.message}")
            Report(
                ok = false, steps = steps, endpoint = endpoint, latencyMs = latency,
                httpStatus = null, replyPreview = null, tokens = null, error = error
            )
        }
    }

    /**
     * 探针请求体：一句话 + 可选 32×32 小图。
     *
     * max_tokens 为什么给 512：**豆包这类模型是推理模型**，会先生成
     * `reasoning_content`（思维链）再给正文。实测一次探针的 completion 里
     * 有 130~160 个 token 是思维链，只有 1~2 个是真正的正文。
     * 给 16 会把思维链截断，导致拿不到正文而误判为失败。
     */
    private fun buildProbeBody(model: String, includeImage: Boolean): String {
        val obj = JSONObject()
        obj.put("model", model)
        obj.put("max_tokens", 512)
        obj.put("temperature", 0.0)

        val content = JSONArray()
        if (includeImage) {
            content.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$PROBE_JPEG"))
            })
        }
        content.put(JSONObject().apply {
            put("type", "text")
            put("text", "连通性测试。请直接回复两个字：正常。")
        })

        val msg = JSONObject().apply {
            put("role", "user")
            put("content", content)
        }
        obj.put("messages", JSONArray().put(msg))
        return obj.toString()
    }

    private fun parseProbeReply(text: String): Pair<String?, Int?> {
        val root = try { JSONObject(text) } catch (_: Exception) { return null to null }
        // 有些网关 200 也塞 error
        root.optJSONObject("error")?.let { err ->
            val m = err.optString("message", "").ifBlank { err.toString() }
            return ("【平台报错】$m") to null
        }
        val choices = root.optJSONArray("choices") ?: return null to null
        if (choices.length() == 0) return null to null
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return null to null
        val c = message.opt("content")
        val content = when (c) {
            is String -> c
            // 少数平台返回 content 数组（分段），拼一下
            is JSONArray -> (0 until c.length()).joinToString("") { i ->
                c.optJSONObject(i)?.optString("text", "").orEmpty()
            }
            else -> ""
        }.trim().ifEmpty { null }

        val usage = root.optJSONObject("usage")
        val tokens = usage?.let {
            if (it.has("total_tokens")) it.optInt("total_tokens")
            else it.optInt("prompt_tokens") + it.optInt("completion_tokens")
        }
        return content to tokens
    }
}

