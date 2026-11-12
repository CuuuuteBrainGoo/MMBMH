package com.bro.lotteryledger.ai

import com.bro.lotteryledger.core.AiProviderKind
import com.bro.lotteryledger.core.EndpointNormalizer
import com.bro.lotteryledger.core.ThinkingLevel
import org.json.JSONObject
import java.net.URI

/** 识别和连通测试共用一份规则；不向未知兼容平台盲发专有参数。 */
internal object ThinkingRequestPolicy {
    fun apply(body: JSONObject, provider: AiProviderKind, baseUrl: String, model: String, level: ThinkingLevel) {
        val host = try { URI(EndpointNormalizer.normalize(baseUrl)).host.orEmpty().lowercase() }
            catch (_: Exception) { "" }
        if (host == "api.deepseek.com") {
            val fast = level == ThinkingLevel.FAST
            body.put("thinking", JSONObject().put("type", if (fast) "disabled" else "enabled"))
            if (!fast) body.put("reasoning_effort", if (level == ThinkingLevel.BALANCED) "low" else "high")
        } else if (provider == AiProviderKind.DOUBAO || host == "ark.cn-beijing.volces.com") {
            val id = model.lowercase()
            val effort = when {
                // 方舟 DeepSeek V4.1 的 minimal 仍会映射为 low；none 才是关闭思考。
                level == ThinkingLevel.FAST &&
                    (id.startsWith("deepseek-v4-1-") || id.startsWith("deepseek-v4.1-")) -> "none"
                // 这些模型始终思考，快速档只能请求最轻档，不能承诺关闭。
                level != ThinkingLevel.DEEP &&
                    (id.startsWith("kimi-k3-1-") || id.startsWith("kimi-k3.1-") ||
                        id.startsWith("glm-5-3-") || id.startsWith("glm-5.3-")) -> "low"
                else -> level.effort
            }
            body.put("reasoning_effort", effort)
        }
    }
}
