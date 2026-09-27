package com.bro.lotteryledger.core

/**
 * API Key 的掩码显示。
 *
 * 需求（少爷 2026-09-27）：编辑已保存的平台配置时，
 * 输入框里显示成**保留头尾各 3 个字符、中间换成 3 个星号**，
 * 而不是一片空白。
 *
 * 空白的问题是：用户看不出「这里到底有没有存过 Key」，
 * 也看不出自己配的是哪一把（多平台、多个 Key 时很常见）。
 * 显示头尾几个字符既能辨认，又不至于泄露完整的密钥。
 *
 * 安全性：只暴露 6 个字符，且**中间宽度不反映真实长度**
 * （一律 3 个星号）—— 连密钥长度都不泄露。
 *
 * 这是纯函数，没有 Android 依赖，所以能直接单测。
 */
object ApiKeyMask {

    /** 中间固定 3 个星号。刻意不用真实长度，避免泄露密钥长度信息。 */
    const val MIDDLE = "***"

    /** 头尾各保留的字符数。 */
    const val EDGE = 3

    /**
     * 掩码。
     *
     * 太短（长度 ≤ 头+尾 = 6）的 Key 无法安全地暴露头尾
     * —— 那种情况下全部打码，宁可不显示也不要露出大半。
     *
     * @return 掩码串；[raw] 为空（null / 空串 / 全空白）时返回 null，
     *         调用方据此显示「未配置」。
     */
    fun mask(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.length <= EDGE * 2) return "***" + "***"   // 6 个星号，长度本身也不透露
        return s.take(EDGE) + MIDDLE + s.takeLast(EDGE)
    }

    /**
     * 判断用户输入框里的内容是否**就是**当前已保存密钥的掩码。
     *
     * 用于「用户没动这个框 → 不该拿掩码去覆盖真 Key」。
     * 这一步很关键：如果不判，用户改个模型名点保存，
     * 掩码串就被当成新 Key 存进去了 —— 密钥静默变成一串星号，
     * 下次识别报 401 且完全看不出原因。
     */
    fun isUnchangedMask(input: String?, stored: String?): Boolean {
        val m = mask(stored) ?: return false
        return input?.trim() == m
    }
}
