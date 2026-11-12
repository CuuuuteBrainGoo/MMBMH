package com.bro.lotteryledger.ai

import com.bro.lotteryledger.core.AiProviderConfig
import com.bro.lotteryledger.core.AiProviderKind
import com.bro.lotteryledger.core.ThinkingLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求体构造的自检。
 *
 * 重点是**火山方舟的 `reasoning_effort`** —— 这是本 App 最大的一处提速：
 * 平台默认是 `high`（深度分析），实测同一张票面 23.9 秒；
 * 显式传 `minimal` 后只要 3.4 秒，而输出正文长度几乎一样（510 vs 508 字）。
 *
 * 这个参数一旦被误删、或条件判断被改坏，App 会悄悄退回「一次识别 20 秒以上」，
 * **不报错、不失败**，只会让人以为「AI 就是这么慢」。所以必须用测试钉住。
 */
class RequestBodyTest {

    private fun body(
        kind: AiProviderKind,
        model: String = "m",
        level: ThinkingLevel = ThinkingLevel.FAST,
        baseUrl: String = "https://example.com/v1"
    ): JSONObject {
        val cfg = AiProviderConfig(
            name = "t", provider = kind,
            baseUrl = baseUrl, model = model, apiKeyRef = "r",
            thinkingLevel = level
        )
        return JSONObject(OpenAiCompatibleClient().buildBody(cfg, "AAAA", "读这张图"))
    }

    @Test
    fun `方舟DeepSeekV41必须用none关闭而非minimal`() {
        listOf("deepseek-v4-1-flash-261017", "deepseek-v4-1-flash-economy-261017").forEach { model ->
            val b = body(AiProviderKind.DOUBAO, model)
            assertEquals("none", b.getString("reasoning_effort"))
        }
    }

    @Test
    fun `方舟始终思考的模型在快速档只能调到low`() {
        listOf("kimi-k3-1-pro-261024", "glm-5-3-flash-260828").forEach { model ->
            val b = body(AiProviderKind.DOUBAO, model)
            assertEquals("low", b.getString("reasoning_effort"))
            assertFalse(b.has("thinking"))
        }
    }

    @Test
    fun `按兼容平台保存的官方方舟地址也必须应用思考设置`() {
        val b = body(AiProviderKind.CUSTOM, "deepseek-v4-1-flash-261017", baseUrl = "https://ark.cn-beijing.volces.com/api/v3")
        assertEquals("none", b.getString("reasoning_effort"))
    }

    @Test
    fun `连通测试和正式识别的思考参数必须一致`() {
        val url = "https://api.deepseek.com/chat/completions"
        ThinkingLevel.entries.forEach { level ->
            val recognition = body(AiProviderKind.OPENAI, "deepseek-flash", level, url)
            val probe = JSONObject(ConnectivityProbe.buildProbeBody("deepseek-flash", false, url, AiProviderKind.OPENAI, level))
            assertEquals(recognition.getJSONObject("thinking").getString("type"), probe.getJSONObject("thinking").getString("type"))
            assertEquals(recognition.optString("reasoning_effort"), probe.optString("reasoning_effort"))
        }
    }

    @Test
    fun `DeepSeek官方快速档必须关闭默认思考而非只传minimal`() {
        val b = body(AiProviderKind.OPENAI, "deepseek-flash", baseUrl = "https://api.deepseek.com/chat/completions")
        assertEquals("disabled", b.getJSONObject("thinking").getString("type"))
        assertFalse(b.has("reasoning_effort"))
    }

    @Test
    fun `DeepSeek官方均衡和深度档显式开启思考`() {
        listOf(ThinkingLevel.BALANCED to "low", ThinkingLevel.DEEP to "high").forEach { (level, effort) ->
            val b = body(AiProviderKind.CUSTOM, "deepseek-flash", level, "https://api.deepseek.com/v1")
            assertEquals("enabled", b.getJSONObject("thinking").getString("type"))
            assertEquals(effort, b.getString("reasoning_effort"))
        }
    }

    @Test
    fun `不能只凭DeepSeek模型名或相似域名给未知服务传thinking`() {
        listOf("https://api.deepseek.com.example.com/v1", "https://example.com/api.deepseek.com/v1").forEach { url ->
            val b = body(AiProviderKind.OPENAI, "deepseek-flash", baseUrl = url)
            assertFalse(b.has("thinking"))
            assertFalse(b.has("reasoning_effort"))
        }
    }

    @Test
    fun `豆包必须带 reasoning_effort，否则退回平台默认的慢速档`() {
        val b = body(AiProviderKind.DOUBAO)
        assertTrue(
            "豆包请求体缺少 reasoning_effort —— 平台默认 high，会慢 7 倍",
            b.has("reasoning_effort")
        )
        // 默认档位必须是快速
        assertEquals("minimal", b.optString("reasoning_effort"))
    }

    @Test
    fun `三档思考强度映射到正确的 effort 值`() {
        assertEquals(
            "快速档",
            "minimal", body(AiProviderKind.DOUBAO, level = ThinkingLevel.FAST).optString("reasoning_effort")
        )
        assertEquals(
            "均衡档",
            "low", body(AiProviderKind.DOUBAO, level = ThinkingLevel.BALANCED).optString("reasoning_effort")
        )
        assertEquals(
            "深度档",
            "high", body(AiProviderKind.DOUBAO, level = ThinkingLevel.DEEP).optString("reasoning_effort")
        )
    }

    @Test
    fun `其它平台不能乱传 reasoning_effort`() {
        // 这个字段是火山方舟的，其它平台没有；未实测支持的参数一律不传，免得 400。
        listOf(
            AiProviderKind.HUNYUAN, AiProviderKind.ZHIPU,
            AiProviderKind.QIANFAN, AiProviderKind.ALIYUN,
            AiProviderKind.OPENAI, AiProviderKind.CUSTOM
        ).forEach { k ->
            assertFalse(
                "${k.display} 不该带 reasoning_effort（未实测支持，可能 400）",
                body(k).has("reasoning_effort")
            )
        }
    }

    @Test
    fun `模型标识原样进入 model 字段`() {
        // 火山方舟走接入点 ID，必须原样传，不能做任何加工。
        //
        // ⚠️ 这里用的是**编造的占位符**，不是任何人的真实接入点。
        // 这个测试只验"字符串原样搬运"，用真 ID 没有任何额外价值 ——
        // 但会把某个账号的资源标识永久写进公开仓库（2026-09-27 少爷要开源时发现并改掉）。
        // 同理，`core/ProviderDefaults` 里给新用户预填的也必须是占位符。
        val ep = "ep-m-xxxxxxxxxxxxxxxx"
        assertEquals(ep, body(AiProviderKind.DOUBAO, ep).optString("model"))
    }

    @Test
    fun `请求体保留了结构化输出与图片传参`() {
        val b = body(AiProviderKind.DOUBAO)
        assertEquals("json_object", b.optJSONObject("response_format")?.optString("type"))

        val content = b.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        val types = (0 until content.length()).map { content.getJSONObject(it).optString("type") }
        assertTrue("必须包含 image_url 传参", types.contains("image_url"))
        assertTrue("必须包含 text 传参", types.contains("text"))

        val img = (0 until content.length())
            .map { content.getJSONObject(it) }
            .first { it.optString("type") == "image_url" }
            .getJSONObject("image_url").optString("url")
        assertTrue("图片必须是 base64 data URI", img.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `思考强度的默认值必须是快速档`() {
        // 这条防的是「有人把默认值改成 DEEP」—— 不报错，只是所有用户都变慢
        assertEquals(
            ThinkingLevel.FAST,
            AiProviderConfig(
                name = "t", provider = AiProviderKind.DOUBAO,
                baseUrl = "u", model = "m", apiKeyRef = "r"
            ).thinkingLevel
        )
    }
}
