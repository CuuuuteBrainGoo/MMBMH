package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 崩溃记录里「版本号」的抽取（`CrashBeacon.versionOf`）。
 *
 * ## 为什么要测这个
 *
 * 2026-09-27 少爷反馈「升级后每次打开都弹同一个崩溃框」。
 * 根因不是崩溃没修好，而是**旧版本的崩溃文件没被区分**：
 * 他升级到修复版后，那条 v1.5.1 的记录还在，弹窗也没说明它是旧的。
 *
 * 修法是「比对记录里的版本和当前版本」—— 那就要求**抽版本这件事本身可靠**。
 * 它一抽错，要么把新崩溃误判成旧记录（**把真 bug 藏了**，更危险），
 * 要么把旧记录误判成新的（用户又被糊一脸）。
 *
 * 抽的是**纯文本**，所以能在 JVM 单测里跑（不需要 Android 环境）。
 */
class CrashBeaconVersionTest {

    /** 和 CrashBeacon.record 真实产出的格式保持一致。 */
    private fun record(version: String) = """
        === 彩票账本崩溃记录 ===
        时间：2026-09-27 21:31:47
        线程：main
        版本：$version
        设备：Android 16（API 36）
        机型：Xiaomi 2210132C

        --- 异常 ---
        java.lang.NullPointerException: something went wrong
        	at com.bro.lotteryledger.Foo.bar(Foo.kt:1)
    """.trimIndent()

    @Test
    fun `能抽出真实记录里的版本号`() {
        assertEquals("1.5.1", CrashBeacon.versionOf(record("1.5.1")))
        assertEquals("1.5.2", CrashBeacon.versionOf(record("1.5.2")))
    }

    @Test
    fun `版本号两边的空白被吃掉`() {
        assertEquals("1.5.1", CrashBeacon.versionOf("版本：  1.5.1  \n其它行"))
    }

    @Test
    fun `抽不出时返回 null 而不是乱猜`() {
        assertNull(CrashBeacon.versionOf(""))
        assertNull(CrashBeacon.versionOf("===== 没有版本这一行 ====="))
        // 只有空值的版本行也算抽不出来
        assertNull(CrashBeacon.versionOf("版本：\n其它"))
        assertNull(CrashBeacon.versionOf("版本：   \n其它"))
    }

    @Test
    fun `不会把异常文本里的 版本 字样误认成版本行`() {
        // 关键边界：只有**行首**是「版本：」才算数。
        // 堆栈里可能出现任意文本，行内出现的不能采信。
        val t = """
            === 彩票账本崩溃记录 ===
            线程：main
            设备：Android 16
            java.lang.IllegalStateException: 版本：9.9.9 只是个巧合
        """.trimIndent()
        assertNull(CrashBeacon.versionOf(t))
    }

    @Test
    fun `只有第一个版本行被采信`() {
        val t = "版本：1.5.1\n版本：2.0.0"
        assertEquals("1.5.1", CrashBeacon.versionOf(t))
    }
}
