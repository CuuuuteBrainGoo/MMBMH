package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平台预填模板的自检。
 *
 * 为什么值得测：这些字符串是**写死的**，一旦手滑打错一个字母，
 * 用户会拿到一个 404 而不知道错在哪。用测试把它们钉住。
 */
class ProviderTemplateTest {

    @Test
    fun `所有平台模板都填了地址和模型`() {
        AiProviderKind.entries
            .filter { it != AiProviderKind.CUSTOM }   // 自定义允许留空
            .forEach { k ->
                val t = ProviderDefaults.template(k)
                assertTrue("${k.display} 缺少地址", t.baseUrl.startsWith("https://"))
                assertTrue("${k.display} 缺少模型名", t.model.isNotBlank())
                assertTrue("${k.display} 缺少说明", t.note.isNotBlank())
            }
    }

    @Test
    fun `模板地址补齐后必须是合法的 chat completions 端点`() {
        AiProviderKind.entries.forEach { k ->
            val t = ProviderDefaults.template(k)
            if (t.baseUrl.isBlank()) return@forEach
            val full = EndpointNormalizer.normalize(t.baseUrl)
            assertTrue(
                "${k.display} 的地址归一化后不对：$full",
                full.endsWith("/chat/completions")
            )
            // 不允许出现双斜杠（除了协议那一段）
            val withoutScheme = full.removePrefix("https://").removePrefix("http://")
            assertTrue("${k.display} 地址里有重复斜杠：$full", !withoutScheme.contains("//"))
        }
    }

    @Test
    fun `豆包不再预填即将下线的旧模型`() {
        val t = ProviderDefaults.template(AiProviderKind.DOUBAO)
        assertEquals(
            "doubao-seed-1-6-vision-250815 已标注即将下线，不该再作为默认预填值",
            false,
            t.model.contains("1-6-vision")
        )
    }

    @Test
    fun `豆包预填的是实测可用的推理接入点 ID`() {
        val t = ProviderDefaults.template(AiProviderKind.DOUBAO)
        assertTrue(
            "豆包默认模型应是 ep- 开头的推理接入点 ID（已实测可用），实际是：${t.model}",
            t.model.startsWith("ep-")
        )
        assertEquals(ProviderDefaults.DOUBAO_ENDPOINT_ID, t.model)
        // 接入点 ID 与区域绑定，地址必须是北京区
        assertTrue(
            "接入点在北京区创建，地址必须同区域",
            t.baseUrl.contains("cn-beijing")
        )
    }

    @Test
    fun `接入点 ID 会被原样放进请求的 model 字段`() {
        // 接入点走的是和模型名完全相同的字段，这里钉住这个前提：
        // 一旦有人把 model 字段改成别的名字，火山方舟的接入点就会失效
        val cfg = AiProviderConfig(
            name = "t", provider = AiProviderKind.DOUBAO,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            model = ProviderDefaults.DOUBAO_ENDPOINT_ID, apiKeyRef = "r"
        )
        val body = com.bro.lotteryledger.ai.OpenAiCompatibleClient()
            .buildBody(cfg, "AAAA", "hi")
        assertTrue(
            "接入点 ID 必须原样出现在请求体的 model 字段里",
            body.contains("\"model\":\"${ProviderDefaults.DOUBAO_ENDPOINT_ID}\"")
        )
    }

    @Test
    fun `混元智谱千帆百炼都给出了确切地址`() {
        assertEquals(
            "https://api.hunyuan.cloud.tencent.com/v1",
            ProviderDefaults.template(AiProviderKind.HUNYUAN).baseUrl
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            ProviderDefaults.template(AiProviderKind.ZHIPU).baseUrl
        )
        assertEquals(
            "https://qianfan.baidubce.com/v2",
            ProviderDefaults.template(AiProviderKind.QIANFAN).baseUrl
        )
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ProviderDefaults.template(AiProviderKind.ALIYUN).baseUrl
        )
    }
}
