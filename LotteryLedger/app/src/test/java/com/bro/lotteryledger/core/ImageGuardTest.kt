package com.bro.lotteryledger.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地预筛（少爷 2026-09-27 问：拍到桌面能不能在上传前就拦掉）。
 *
 * 这一层的**铁律是宁可漏拦、不可误拦**：误拦一张真票的代价
 * （用户莫名其妙识别不了，还以为 App 坏了）远大于放过一张桌面照片。
 *
 * 所以测试重点在两处：
 *  ① 真票的典型统计值必须放行；
 *  ② 统计拿不到时必须放行（不能因为本地算错就不让识别）。
 */
class ImageGuardTest {

    /** 真票照片的典型特征：亮、有明暗对比、几乎无彩色 */
    private fun realTicket() = ImageStats(
        brightnessMean = 185.0,
        brightnessStd = 48.0,
        saturationMean = 0.08
    )

    @Test
    fun `真票的统计特征必须放行`() {
        val r = ImageGuard.judge(realTicket())
        assertEquals(ImageGuard.Verdict.OK, r.verdict)
        assertFalse(r.blocks)
        assertFalse(r.warns)
    }

    @Test
    fun `逆光或暗处的票也必须放行`() {
        // 亮度 60，远高于"全黑"阈值 25 —— 不能拦
        val dim = ImageStats(brightnessMean = 60.0, brightnessStd = 30.0, saturationMean = 0.1)
        assertEquals(ImageGuard.Verdict.OK, ImageGuard.judge(dim).verdict)
    }

    @Test
    fun `偏色的票也必须放行 只要饱和度没超线`() {
        // 米黄纸面 + 一点红色 logo，饱和度 0.3，仍在阈值内
        val warm = ImageStats(brightnessMean = 200.0, brightnessStd = 40.0, saturationMean = 0.3)
        assertEquals(ImageGuard.Verdict.OK, ImageGuard.judge(warm).verdict)
    }

    @Test
    fun `全黑照片要拦下且不发请求`() {
        val dark = ImageStats(brightnessMean = 8.0, brightnessStd = 5.0, saturationMean = 0.02)
        val r = ImageGuard.judge(dark)
        assertEquals(ImageGuard.Verdict.TOO_DARK, r.verdict)
        assertTrue(r.blocks)
    }

    @Test
    fun `整片同色要拦下`() {
        // 对着白墙 / 纯色桌面拍：很亮，但几乎没有任何明暗变化
        val flat = ImageStats(brightnessMean = 230.0, brightnessStd = 2.0, saturationMean = 0.05)
        val r = ImageGuard.judge(flat)
        assertEquals(ImageGuard.Verdict.FLAT, r.verdict)
        assertTrue(r.blocks)
    }

    @Test
    fun `色彩鲜艳只提醒不拦截`() {
        // 草地 / 木桌这类误拍对象
        val colorful = ImageStats(brightnessMean = 120.0, brightnessStd = 45.0, saturationMean = 0.6)
        val r = ImageGuard.judge(colorful)
        assertEquals(ImageGuard.Verdict.TOO_COLORFUL, r.verdict)
        // 关键：只提醒，不能拦 —— 票放在彩色桌布上拍是正常用法
        assertFalse(r.blocks)
        assertTrue(r.warns)
    }

    @Test
    fun `统计拿不到时必须放行`() {
        val r = ImageGuard.judge(ImageStats.UNKNOWN)
        assertEquals(ImageGuard.Verdict.OK, r.verdict)
        assertFalse(r.blocks)
    }

    @Test
    fun `拦截判定优先于提醒判定`() {
        // 又黑又艳 —— 应该按"太暗"拦掉，而不是只提醒
        val both = ImageStats(brightnessMean = 5.0, brightnessStd = 30.0, saturationMean = 0.9)
        assertTrue(ImageGuard.judge(both).blocks)
    }
}
