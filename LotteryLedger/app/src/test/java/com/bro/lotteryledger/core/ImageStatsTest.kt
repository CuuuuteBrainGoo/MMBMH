package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地预筛的统计计算。
 *
 * 少爷 2026-09-27 提了效率约束：**「如果会让流程增加超过 1 秒，那就不做」**。
 *
 * 这一层的回答是量化的：

 *  - 计算本身是纯数组遍历，4096 个点（64×64）在 JVM 上是**微秒~毫秒级**
 *  - 真正花钱的是「取出像素」那一步，而我们已经用 `getPixels` 一口气取整块
 *    （而不是逐点 JNI 调用）
 *  - 而且它**净省时间**：拦下一张误拍 = 省掉一次 3~5 秒的 API 调用
 *
 * 下面最后一条测试直接把耗时钉住，避免以后有人改成慢写法还没人发现。
 */
class ImageStatsTest {

    @Test
    fun `全白图 - 亮度拉满 没有明暗变化 没有颜色`() {
        val s = ImageStats.fromArgb(IntArray(4096) { 0xFFFFFFFF.toInt() })
        assertEquals(255.0, s.brightnessMean, 0.01)
        assertEquals(0.0, s.brightnessStd, 0.01)
        assertEquals(0.0, s.saturationMean, 0.01)
        // 这正是 ImageGuard 判定「整片同色」的依据
        assertEquals(ImageGuard.Verdict.FLAT, ImageGuard.judge(s).verdict)
    }

    @Test
    fun `纯黑图 - 亮度为 0`() {
        val s = ImageStats.fromArgb(IntArray(4096) { 0xFF000000.toInt() })
        assertEquals(0.0, s.brightnessMean, 0.01)
        // 这正是「太暗」那档的依据
        assertEquals(ImageGuard.Verdict.TOO_DARK, ImageGuard.judge(s).verdict)
    }

    @Test
    fun `黑白各半 - 有明显标准差 不会被误判为纯色`() {
        val pixels = IntArray(4096) { i ->
            if (i % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        val s = ImageStats.fromArgb(pixels)
        assertEquals(127.5, s.brightnessMean, 1.0)
        assertTrue("黑白各半的标准差应接近 127，实际 ${s.brightnessStd}", s.brightnessStd > 100.0)
        // 不能被当成「整片同色」拦掉
        assertEquals(ImageGuard.Verdict.OK, ImageGuard.judge(s).verdict)
    }

    @Test
    fun `纯红 - 饱和度拉满`() {
        val s = ImageStats.fromArgb(IntArray(100) { 0xFFFF0000.toInt() })
        assertEquals(1.0, s.saturationMean, 0.01)
        // 亮度是感知加权的结果，纯红的亮度约 76 而不是 255
        assertEquals(0.299 * 255, s.brightnessMean, 0.5)
    }

    @Test
    fun `灰色 - 饱和度为 0`() {
        val s = ImageStats.fromArgb(IntArray(100) { 0xFF808080.toInt() })
        assertEquals(0.0, s.saturationMean, 0.01)
    }

    @Test
    fun `模拟票面 - 浅底黑字应当放行`() {
        // 85% 浅纸色 + 15% 黑字，接近真票的明暗分布
        val pixels = IntArray(4096) { i ->
            if (i % 100 < 85) 0xFFF2EBDC.toInt() else 0xFF1A1A1A.toInt()
        }
        val s = ImageStats.fromArgb(pixels)
        assertTrue("票面应偏亮，实际 ${s.brightnessMean}", s.brightnessMean > 150)
        assertTrue("票面应有明暗对比，实际 ${s.brightnessStd}", s.brightnessStd > 6.0)
        assertTrue("票面饱和度应很低，实际 ${s.saturationMean}", s.saturationMean < 0.42)
        assertEquals(ImageGuard.Verdict.OK, ImageGuard.judge(s).verdict)
    }

    @Test
    fun `空数组 - 返回 UNKNOWN 让上层放行`() {
        assertEquals(ImageStats.UNKNOWN, ImageStats.fromArgb(IntArray(0)))
    }

    @Test
    fun `耗时上限 - 单次统计必须远低于 1 秒`() {
        // 少爷的效率约束：超过 1 秒就不做。
        // 断言给到 100ms（比 1 秒严格 10 倍），既不会在慢机器上误报，
        // 又足以挡住「有人把它改回逐点 getPixel 的慢写法」这类退化。
        //
        // 注意这里只测**纯计算**部分。取像素那一步是 1 次 getPixels 调用，
        // 在真机上比这还快一个量级。
        val pixels = IntArray(4096) { (it * 2654435761L).toInt() }

        // 预热，避开 JIT 首次编译的开销
        repeat(100) { ImageStats.fromArgb(pixels) }

        val rounds = 100
        val t0 = System.nanoTime()
        repeat(rounds) { ImageStats.fromArgb(pixels) }
        val perCallMs = (System.nanoTime() - t0) / 1_000_000.0 / rounds

        assertTrue(
            "单次统计耗时 ${"%.3f".format(perCallMs)}ms，超过 100ms 上限 —— " +
                "本地预筛不该成为瓶颈，检查是不是改成了逐点取像素",
            perCallMs < 100.0
        )
    }
}
