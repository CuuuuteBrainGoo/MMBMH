package com.bro.lotteryledger.ai

import com.bro.lotteryledger.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecognitionClientTest {
    private fun config(server: MockWebServer) = AiProviderConfig(
        name = "test", provider = AiProviderKind.DOUBAO,
        baseUrl = server.url("/v1").toString(), model = "test-model", apiKeyRef = "test-ref",
        maxRetries = 1
    )

    private fun response(reasoning: Int? = null): MockResponse {
        val usage = JSONObject().put("prompt_tokens", 10).put("completion_tokens", 20)
        if (reasoning != null) usage.put("completion_tokens_details", JSONObject().put("reasoning_tokens", reasoning))
        return MockResponse().setBody(JSONObject()
            .put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", "{}"))))
            .put("usage", usage).toString())
    }

    @Test fun `停用配置不会发请求也不会重试`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val result = OpenAiCompatibleClient().recognize(config(server).copy(enabled = false), "test-key", "test-image", "test-prompt")
            assertFalse(result.ok)
            assertEquals(0, result.attemptCount)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `真实请求携带快速参数且重试次数和思考用量可诊断`() = runBlocking {
        LedgerLog.clear()
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
            server.enqueue(response(17))
            val result = OpenAiCompatibleClient().recognize(config(server), "test-key-private", "test-image-private", "test-prompt-private")
            assertTrue(result.ok)
            assertEquals(2, result.attemptCount)
            assertEquals(17, result.reasoningTokens)
            assertEquals(30, result.totalTokens)
            assertEquals(2, server.requestCount)
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("/v1/chat/completions", request.path)
                assertEquals("minimal", JSONObject(request.body.readUtf8()).getString("reasoning_effort"))
            }
            val log = LedgerLog.exportText()
            assertTrue(log.contains("尝试=2"))
            assertTrue(log.contains("等待响应="))
            assertTrue(log.contains("思考token=17"))
            listOf("test-key-private", "test-image-private", "test-prompt-private").forEach { assertFalse(log.contains(it)) }
        }
    }

    @Test fun `平台未提供思考用量不能记成零`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response())
            val result = OpenAiCompatibleClient().recognize(config(server), "test-key", "image", "prompt")
            assertTrue(result.ok)
            assertNull(result.reasoningTokens)
            assertEquals(1, result.attemptCount)
        }
    }

    @Test fun `停用复核平台不会因主结果有疑问而被调用`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response()) // 空对象会触发本地校验疑问
            val cfg = config(server)
            val result = RecognitionOrchestrator(OpenAiCompatibleClient()).run(
                cfg, cfg.copy(enabled = false, role = AiRole.REVIEWER), "test-key", "review-key", "image"
            )
            assertFalse(result.reviewed)
            assertEquals(1, result.runs.size)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `认证失败不会重复请求计费接口`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            val result = OpenAiCompatibleClient().recognize(config(server), "test-key", "image", "prompt")
            assertFalse(result.ok)
            assertEquals(1, result.attemptCount)
            assertEquals(1, server.requestCount)
        }
    }
}
