package com.bro.lotteryledger.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连通性探针的规格自检。
 *
 * 为什么值得单测：探针图用 1×1 时，火山方舟会返回 400（最小边长 14px），
 * 用户看到的是「测试失败」——**于是把一个完全正确的配置误判成错的**。
 * 这个坑 2026-09-26 已经真实踩过一次，用测试钉住。
 */
class ConnectivityProbeTest {

    @Test
    fun `探针图尺寸必须大于各平台已知的最小下限`() {
        assertTrue(
            "探针图 ${ConnectivityProbe.PROBE_IMAGE_SIDE}px 小于已知下限 " +
                "${ConnectivityProbe.KNOWN_MIN_IMAGE_SIDE}px，火山方舟会返回 400",
            ConnectivityProbe.PROBE_IMAGE_SIDE >= ConnectivityProbe.KNOWN_MIN_IMAGE_SIDE
        )
    }

    @Test
    fun `探针图要留足余量而不是刚好卡在下限`() {
        // 卡在 14 很危险：其它平台下限可能略高，改一点点就翻车
        assertTrue(
            "探针图应比已知下限留出至少 2 倍余量，当前 " +
                "${ConnectivityProbe.PROBE_IMAGE_SIDE}px vs 下限 " +
                "${ConnectivityProbe.KNOWN_MIN_IMAGE_SIDE}px",
            ConnectivityProbe.PROBE_IMAGE_SIDE >= ConnectivityProbe.KNOWN_MIN_IMAGE_SIDE * 2
        )
    }

    @Test
    fun `已知下限记录的是火山方舟实测值`() {
        // 这个数字来自真实 400 响应：
        // "Minimum allowed dimension: 14 pixels. Current dimensions: width = 1, height = 1."
        assertEquals(14, ConnectivityProbe.KNOWN_MIN_IMAGE_SIDE)
    }

    /**
     * 火山方舟把「model 字段填了模型名而非接入点 ID」报成 404，
     * 措辞是 "does not exist or you do not have access to it" —— 极像「地址错了」。
     * 必须被识别成「模型标识问题」，否则用户会去反复改地址，永远修不好。
     */
    @Test
    fun `火山方舟的接入点 404 被识别成模型标识问题而不是地址问题`() {
        val body = """
            {"error":{"code":"InvalidEndpointOrModel.NotFound",
            "message":"The model or endpoint doubao-seed-1-6-vision-250815 does not exist or you do not have access to it.",
            "param":"","type":"Not Found"}}
        """.trimIndent()
        val msg = FailureText.describe(
            404, body, "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        )
        assertTrue(
            "应提示是「模型/接入点」的问题，实际提示：$msg",
            msg.contains("接入点")
        )
        assertTrue(
            "不该把它当成通用 404（地址问题）来处理，实际提示：$msg",
            !msg.contains("接口地址不对")
        )
    }

    @Test
    fun `普通 404 仍然按地址问题提示`() {
        val msg = FailureText.describe(
            404, "", "https://example.com/v1/chat/completions"
        )
        assertTrue(
            "真正的地址错误仍应提示去核对接口地址，实际：$msg",
            msg.contains("接口地址") || msg.contains("地址")
        )
    }
}
