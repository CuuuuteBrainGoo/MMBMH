package com.bro.lotteryledger.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 单张扫描「识别无异常 → 直接入账」的守护测试。
 *
 * ## 为什么要有这条
 *
 * 2026-09-28 少爷发现：批量导入能一键入账干净的票，**单张扫描却必须手点确认** ——
 * 判据（`core/BatchTriage.kt`）明明只有一处实现，却**只接了批量一个消费者**。
 * 同一个「干净」定义，两条路径两种行为。
 *
 * 修完之后，这个行为完全依赖 `runRecognition` 里那一句分诊调用。
 * 它一旦被人重构掉（比如"顺手简化一下扫描流程"），行为会**静默退回**
 * 「必须人工确认」—— 编译过、其他测试全绿、界面上也看不出异常，
 * 只有这条测试会红。
 *
 * ## 为什么读源码而不是跑一遍
 *
 * `runRecognition` 要真跑得起 Android 的 Bitmap / 网络 / Room，那是仪器测试的范围。
 * 而这里要守的是**接线本身**：单张路径到底有没有接上那个判据。
 */
class ScanAutoCommitGuardTest {

    private val vmSource: String by lazy {
        readSource("src/main/java/com/bro/lotteryledger/ui/MainViewModel.kt")
    }

    /**
     * 读源码文件。
     *
     * Gradle 跑单元测试时工作目录是模块目录（`app/`），但不同环境可能不同，
     * 所以试几个常见位置。**都找不到就断言失败** —— 静默跳过等于这个测试白写。
     */
    private fun readSource(rel: String): String {
        val candidates = listOf(File(rel), File("app/$rel"), File("../app/$rel"))
        val f = candidates.firstOrNull { it.exists() }
        assertNotNull(
            "找不到 $rel。试过：${candidates.joinToString { it.absolutePath }}",
            f
        )
        return f!!.readText(Charsets.UTF_8)
    }

    /**
     * 剥掉块注释和整行注释。
     *
     * 必须做这一步：下面几条断言用的标识符（`BatchTriage.classify` 之类）
     * 在说明性注释里也会出现，不剥就会**把注释当成代码**判成通过 ——
     * 那样函数体其实被删空了，测试反而变绿。
     */
    private fun stripComments(src: String): String =
        src.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""^[ \t]*//.*$""", RegexOption.MULTILINE), "")

    /** 取某个函数的函数体原文：从函数签名起，到下一个顶层成员（4 空格缩进的 fun）为止。 */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue(
            "源码里找不到「$signature」—— 函数被改名了？这条测试要跟着改。",
            start >= 0
        )
        val rest = src.substring(start + signature.length)
        val nextMember = Regex("""\n    (private |internal |public )?(suspend )?fun """).find(rest)
        return if (nextMember == null) rest else rest.substring(0, nextMember.range.first)
    }

    @Test
    fun `单张扫描必须走 BatchTriage 分诊`() {
        val body = stripComments(functionBody(vmSource, "private suspend fun runRecognition("))
        assertTrue(
            "runRecognition 里没有调用 BatchTriage.classify —— 单张扫描又退回" +
                "「必须人工确认」了（批量能一键入账、单张却要点一次）。",
            body.contains("BatchTriage.classify")
        )
    }

    @Test
    fun `分诊结果必须真的接上入账分支`() {
        // 光有 classify 不算数 —— 要确认它的返回值真的驱动了「直接入账」这条路。
        // （防「调了但结果被丢掉」这种假接线。）
        val body = stripComments(functionBody(vmSource, "private suspend fun runRecognition("))
        assertTrue(
            "分了诊但没拿结果决定是否直接入账 —— classify 的返回值被丢掉了？",
            body.contains("DraftTriage.CLEAN") && body.contains("commitCleanScan")
        )
    }

    @Test
    fun `直接入账后必须给用户提示`() {
        // 用户没经过核对页，toast 是他唯一能看出「票到底进没进、进的是什么」的地方。
        // 静默入账 = 用户以为没录上，回头重扫一张 → 变成重复票。
        val body = stripComments(functionBody(vmSource, "private suspend fun commitCleanScan("))
        assertTrue(
            "commitCleanScan 里没有 toast —— 用户没经过核对页，" +
                "不提示他会以为票没录进去。",
            body.contains("_toast.value")
        )
    }

    @Test
    fun `直接入账后必须当场核验开奖结果`() {
        // 少爷的既有要求：入账时若开奖时间已过，当场核验一次，
        // 别让用户等到晚上 22:30 的定时任务。核对页入账有这待遇，
        // 自动入账不能漏 —— 漏了就变成「手动入账的票当场出结果，自动入账的要等明天」。
        val body = stripComments(functionBody(vmSource, "private suspend fun commitCleanScan("))
        assertTrue(
            "commitCleanScan 里没有 autoCheckAfterCommit —— " +
                "自动入账的票拿不到即时核验，要等定时任务。",
            body.contains("autoCheckAfterCommit")
        )
    }
}
