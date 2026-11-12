package com.bro.lotteryledger.ai

import okhttp3.Call
import okhttp3.EventListener
import java.util.concurrent.TimeUnit

/** 只记耗时，不读取请求头、密钥、图片或响应正文。每次显式重试单独记录。 */
internal class AiRequestTiming : EventListener() {
    private val started = System.nanoTime()
    private var uploadStarted: Long? = null
    private var uploadEnded: Long? = null
    private var headersStarted: Long? = null

    override fun requestBodyStart(call: Call) { uploadStarted = System.nanoTime() }
    override fun requestBodyEnd(call: Call, byteCount: Long) { uploadEnded = System.nanoTime() }
    override fun responseHeadersStart(call: Call) { headersStarted = System.nanoTime() }

    private fun ms(from: Long?, to: Long?): String =
        if (from != null && to != null) TimeUnit.NANOSECONDS.toMillis((to - from).coerceAtLeast(0)).toString()
        else "未知"

    fun summary(): String = "发出前=${ms(started, uploadStarted)}ms " +
        "上传=${ms(uploadStarted, uploadEnded)}ms " +
        "等待响应=${ms(uploadEnded, headersStarted)}ms " +
        "接收=${ms(headersStarted, System.nanoTime())}ms"
}
