package com.bro.lotteryledger.ai

import com.bro.lotteryledger.core.AiCallResult
import com.bro.lotteryledger.core.AiProviderConfig
import com.bro.lotteryledger.core.EndpointNormalizer
import com.bro.lotteryledger.core.VisionPrompt
import com.bro.lotteryledger.core.LedgerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容的视觉识别客户端。
 *
 * 豆包 / 混元(兼容模式) / 智谱 / 百炼 / OpenAI 官方 / 任何兼容实现，
 * 都用同一套 `chat/completions` + `image_url`(base64 data URI) 协议，
 * 所以只写这一个客户端（§23.10：不把 App 绑定某一家）。
 */
class OpenAiCompatibleClient(
    private val okHttp: OkHttpClient = defaultClient()
) {

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        private const val MEDIA_JSON = "application/json; charset=utf-8"
    }

    /**
     * 调用一次视觉识别。
     *
     * @param imageBase64 已经是裸 base64（不含 data URI 前缀）
     * @param prompt 提示词。复核调用传 [VisionPrompt.buildReviewer]，**绝不传主模型结果**
     */
    suspend fun recognize(
        cfg: AiProviderConfig,
        apiKey: String,
        imageBase64: String,
        prompt: String
    ): AiCallResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        fun elapsedMs() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        if (!cfg.enabled) {
            return@withContext AiCallResult(null, null, null, 0, null, "平台已停用，请先在设置中启用")
        }

        if (cfg.baseUrl.isBlank()) {
            return@withContext AiCallResult(null, null, null, 0, null, "接口地址未填写")
        }
        if (cfg.model.isBlank()) {
            return@withContext AiCallResult(null, null, null, 0, null, "模型名未填写")
        }
        if (apiKey.isBlank()) {
            return@withContext AiCallResult(null, null, null, 0, null, "API Key 未配置")
        }

        // 用户可能只填了平台的基础地址，这里补齐 /chat/completions
        val endpoint = EndpointNormalizer.normalize(cfg.baseUrl)
        if (endpoint.isBlank()) {
            return@withContext AiCallResult(null, null, null, 0, null, "接口地址无法识别，请检查是否填完整")
        }

        val body = buildBody(cfg, imageBase64, prompt)
        val sentParams = JSONObject(body)
        val thinkingDescription = listOfNotNull(
            sentParams.optJSONObject("thinking")?.optString("type")?.let { "thinking=$it" },
            sentParams.optString("reasoning_effort", "").takeIf { it.isNotEmpty() }?.let { "effort=$it" }
        ).joinToString(" ").ifEmpty { "未发送（由平台决定）" }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(MEDIA_JSON.toMediaType()))
            .build()

        var lastError: String? = null
        var lastStatus: Int? = null
        val attempts = cfg.maxRetries.coerceAtLeast(0) + 1

        // 用显式下标循环，避免在 use{} 的 inline lambda 里 continue
        var attempt = 1
        while (attempt <= attempts) {
            val currentAttempt = attempt++
            val timing = AiRequestTiming()
            LedgerLog.i("AI耗时", "请求 $currentAttempt/$attempts：平台=${cfg.provider.code} 模型=${cfg.model} 思考参数=$thinkingDescription")
            try {
                val client = okHttp.newBuilder()
                    .eventListener(timing)
                    .readTimeout(cfg.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    // callTimeout 是整个调用的硬上限，必须比 readTimeout 宽出一截，
                    // 否则读超时还没到，callTimeout 先把连接掐了
                    .callTimeout(cfg.timeoutSeconds.toLong() + 30, TimeUnit.SECONDS)
                    .build()

                val result = client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val latency = elapsedMs()
                    lastStatus = resp.code
                    LedgerLog.i("AI耗时", "请求 $currentAttempt HTTP ${resp.code}：${timing.summary()}")

                    if (!resp.isSuccessful) {
                        lastError = FailureText.describe(resp.code, text, endpoint)
                        // 4xx 除 429 外重试没意义
                        val fatal = resp.code in 400..499 && resp.code != 429
                        return@use Triple(null, latency, fatal)
                    }

                    val (content, pt, ct) = extractContent(text)
                    if (content == null) {
                        lastError = "响应里没有可用的文本内容"
                        return@use Triple(null, latency, false)
                    }
                    val reasoningTokens = reasoningTokens(text)
                    LedgerLog.i("AI耗时", "请求成功：累计=${latency}ms 尝试=$currentAttempt " +
                        "平台报告思考token=${reasoningTokens ?: "未提供"}")
                    // 成功：用外层标记 + 结果返回
                    return@withContext AiCallResult(content, pt, ct, latency, resp.code, null, reasoningTokens, currentAttempt)
                }
                if (result.third) break
            } catch (e: java.io.InterruptedIOException) {
                // 超时**不重试**：模型慢的话重试还是慢，只会让用户多等一个完整超时周期。
                // 直接报错，并在提示里告诉他可以把超时调大。
                lastError = "请求超时（等了 ${cfg.timeoutSeconds} 秒没等到响应）。" +
                    "正常情况下读票只要 10～30 秒，这次大概率是网络或平台抖动。" +
                    "可以再试一次；如果反复超时，到「设置 → AI 平台」把「超时(秒)」调更大。" +
                    "\n技术细节：${e.javaClass.simpleName}: ${e.message}"
                break
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
            }
            LedgerLog.w("AI耗时", "请求 $currentAttempt 未成功：${timing.summary()}；" +
                if (attempt <= attempts) "准备重试" else "重试次数已用完")
        }

        AiCallResult(null, null, null, elapsedMs(), lastStatus, lastError ?: "未知错误", attemptCount = attempt - 1)
    }

    /** 构造 OpenAI 兼容请求体。 */
    fun buildBody(cfg: AiProviderConfig, imageBase64: String, prompt: String): String {
        val obj = JSONObject()
        obj.put("model", cfg.model)
        obj.put("temperature", cfg.temperature)

        ThinkingRequestPolicy.apply(obj, cfg.provider, cfg.baseUrl, cfg.model, cfg.thinkingLevel)

        val messages = JSONArray()
        val msg = JSONObject()
        msg.put("role", "user")

        val content = JSONArray()
        content.put(JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64"))
        })
        content.put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })
        msg.put("content", content)
        messages.put(msg)
        obj.put("messages", messages)

        // 要求结构化输出（§18 structured_output）
        if (cfg.structuredOutput) {
            obj.put("response_format", JSONObject().put("type", "json_object"))
        }
        return obj.toString()
    }

    /** 从响应里取正文与 token 用量。 */
    private fun reasoningTokens(text: String): Int? = try {
        val details = JSONObject(text).optJSONObject("usage")?.optJSONObject("completion_tokens_details")
        if (details != null && details.has("reasoning_tokens") && !details.isNull("reasoning_tokens"))
            details.optInt("reasoning_tokens").takeIf { it >= 0 }
        else null
    } catch (_: Exception) { null }

    private fun extractContent(text: String): Triple<String?, Int?, Int?> {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return Triple(null, null, null)
        }

        // 有些网关把错误塞在 200 响应里
        if (root.has("error") && !root.isNull("error")) {
            return Triple(null, null, null)
        }

        val choices = root.optJSONArray("choices") ?: return Triple(null, null, null)
        if (choices.length() == 0) return Triple(null, null, null)

        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return Triple(null, null, null)
        val content = message.optString("content", "").trim().ifEmpty { null }

        val usage = root.optJSONObject("usage")
        val pt = usage?.optInt("prompt_tokens")?.takeIf { usage.has("prompt_tokens") }
        val ct = usage?.optInt("completion_tokens")?.takeIf { usage.has("completion_tokens") }

        return Triple(content, pt, ct)
    }
}
