package com.bro.lotteryledger.ai

import com.bro.lotteryledger.core.AiProviderKind

/**
 * 把各平台返回的错误码 / 业务错误串，翻译成「下一步该干什么」。
 *
 * 为什么单独抽出来：原先探测路径（[ConnectivityProbe]）和识别路径
 * （[OpenAiCompatibleClient]）各写了一套 404 文案，结果**只改了一边**——
 * 火山方舟「model 填了模型名」的 404 在识别路径上仍被报成「接口地址不对」，
 * 会把用户引去反复改地址，永远修不好。同一类判断只能有一份。
 *
 * 匹配顺序有讲究：**平台自带的业务错误码要排在通用 HTTP 状态码之前**。
 * 火山方舟的 404 原文是 `InvalidEndpointOrModel.NotFound: ... does not exist
 * or you do not have access to it`，措辞极像「地址写错了」，但它实际说的是
 * 「模型标识不对，或该模型没在你的账号开通」。
 */
internal object FailureText {

    fun describe(code: Int, body: String, endpoint: String): String {
        val detail = body.trim().take(400)
        val lower = detail.lowercase()

        val hint = when {
            // 火山方舟特有：model 字段填了模型名而非接入点 ID，或模型未在账号开通。
            // 必须排在通用 404 / model_not_found 之前。
            lower.contains("invalidendpointormodel")
                    || lower.contains("does not exist or you do not have access")
                    || lower.contains("targeted an endpoint that does not exist") ->
                "这不是地址错了，是「模型」这一栏填得不对。\n" +
                    "火山方舟（豆包）必须填「推理接入点 ID」（ep- 开头），方舟靠它路由到具体模型；\n" +
                    "填模型名有时候能通，但只要那个模型没在你的账号里开通，就会报这个错。\n" +
                    "去方舟控制台「在线推理 → 推理接入点」创建并复制接入点 ID 填进来。"

            // 平台自带的业务错误码优先（它们通常也返回 4xx）
            lower.contains("model_not_found") || lower.contains("model not exist")
                    || lower.contains("模型不存在") || lower.contains("invalid model") ->
                "模型名不对，或该模型没在当前账号开通。请到平台控制台复制完整模型名。\n" +
                    "百度千帆要额外在「开通管理」里逐个开通模型，漏开会报这个错。"

            lower.contains("invalid api key") || lower.contains("authentication")
                    || lower.contains("apikey") || code == 401 ->
                "API Key 无效。检查是否复制完整（前后不要留空格）、是否已被删除或轮换。"

            lower.contains("insufficient") || lower.contains("quota") || lower.contains("balance")
                    || lower.contains("余额") || lower.contains("欠费") ->
                "账户余额或额度不足，请到平台控制台充值。"

            code == 403 ->
                "Key 有效但无权调用该模型。可能需要在控制台为这个 Key 授权，或开通对应模型。"

            code == 404 ->
                "接口地址不对。请检查「设置 → AI 平台」里的地址，" +
                    "应指向平台的 chat/completions 端点（本次请求发往：$endpoint）。"

            code == 413 -> "图片太大被网关拒绝，可到「设置」把图片质量调低一档。"

            code == 400 ->
                "请求参数被平台拒绝。常见原因：图片尺寸不合规、图片格式不支持、" +
                    "或模型标识填错。具体原因看上面的平台原文。"

            code == 429 -> "调用太频繁或额度用尽，稍后再试。"

            code in 500..599 -> "平台服务端异常（$code），这不是你的配置问题，稍后重试。"

            else -> "平台拒绝了这次请求。下面是原文，可对照官方文档核对参数。"
        }

        return buildString {
            append("HTTP ").append(code)
            if (detail.isNotEmpty()) append("：").append(detail)
            append("\n\n").append(hint)
        }
    }

    /** 平台 kind → 该平台特有的排错提示（在测试结果里附一条平台专属贴士）。 */
    fun platformTip(kind: AiProviderKind): String? = when (kind) {
        AiProviderKind.DOUBAO ->
            "火山方舟的地址填 https://ark.cn-beijing.volces.com/api/v3。" +
                "「模型」这一栏「必须填推理接入点 ID」（ep- 开头，如 ep-m-xxxx），" +
                "不能填 doubao-seed-xxx 这类模型名 —— 填模型名会报 404 " +
                "「model or endpoint does not exist」。" +
                "接入点在方舟控制台「在线推理 → 推理接入点」里创建并复制。" +
                "注意接入点与区域绑定，地址要和创建接入点时选的区域一致。"
        AiProviderKind.HUNYUAN ->
            "混元 OpenAI 兼容端点是 https://api.hunyuan.cloud.tencent.com/v1，视觉模型填 hunyuan-vision。"
        AiProviderKind.ZHIPU ->
            "智谱地址填 https://open.bigmodel.cn/api/paas/v4，视觉模型 glm-4v-plus 或 glm-4v-flash（后者免费）。"
        AiProviderKind.QIANFAN ->
            "千帆的 Key 形如 bce-v3/ALTAK-xxx/xxx（不是 sk- 开头），地址填 https://qianfan.baidubce.com/v2。\n" +
                "另外必须先到控制台「开通管理」里开通所用模型，否则会报模型不存在。"
        AiProviderKind.ALIYUN ->
            "百炼地址填 https://dashscope.aliyuncs.com/compatible-mode/v1，视觉模型 qwen-vl-max-latest。"
        AiProviderKind.OPENAI ->
            "官方地址 https://api.openai.com/v1。注意：本 App 会把图片以 base64 内联发送，" +
                "部分账号的图片额度需要单独确认。"
        AiProviderKind.CUSTOM -> null
    }
}
