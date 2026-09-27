package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 接口地址归一化测试。
 *
 * 背景（2026-09-26 真机 404 事故）：
 * 用户照平台控制台填基础地址 `…/api/v3`，请求打到基础地址上报 404。
 * 这些用例锁死「补齐 /chat/completions」的行为，防止回归。
 */
class EndpointNormalizerTest {

    @Test
    fun `豆包基础地址补全为完整端点`() {
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            EndpointNormalizer.normalize("https://ark.cn-beijing.volces.com/api/v3")
        )
    }

    @Test
    fun `已带完整路径时保持不变`() {
        val full = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        assertEquals(full, EndpointNormalizer.normalize(full))
    }

    @Test
    fun `结尾多余斜杠被剥掉`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            EndpointNormalizer.normalize("https://api.openai.com/v1/chat/completions/")
        )
    }

    @Test
    fun `基础地址带结尾斜杠也能补对`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            EndpointNormalizer.normalize("https://api.openai.com/v1/")
        )
    }

    @Test
    fun `OpenAI 官方基础地址补全`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            EndpointNormalizer.normalize("https://api.openai.com/v1")
        )
    }

    @Test
    fun `裸域名也能补全`() {
        assertEquals(
            "https://example.com/chat/completions",
            EndpointNormalizer.normalize("https://example.com")
        )
    }

    @Test
    fun `带非标准端口的地址保留端口`() {
        assertEquals(
            "http://192.168.1.10:8080/v1/chat/completions",
            EndpointNormalizer.normalize("http://192.168.1.10:8080/v1")
        )
    }

    @Test
    fun `查询参数被丢弃`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            EndpointNormalizer.normalize("https://api.openai.com/v1?debug=1")
        )
    }

    @Test
    fun `前后空白被清理`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            EndpointNormalizer.normalize("  https://api.openai.com/v1  ")
        )
    }

    @Test
    fun `空字符串返回空`() {
        assertEquals("", EndpointNormalizer.normalize(""))
        assertEquals("", EndpointNormalizer.normalize("   "))
    }

    @Test
    fun `非法地址原样返回不做猜测`() {
        // §2.3 同源原则：认不出来就不猜，交给上层报「地址无法识别」
        assertEquals("随便写的", EndpointNormalizer.normalize("随便写的"))
    }

    @Test
    fun `不认识的路径不猜测只补后缀`() {
        // 只做补全不做纠正：用户填了自建网关的奇怪前缀，就按他写的补
        assertEquals(
            "https://my-gw.example.com/lottery/v2/chat/completions",
            EndpointNormalizer.normalize("https://my-gw.example.com/lottery/v2")
        )
    }
}
