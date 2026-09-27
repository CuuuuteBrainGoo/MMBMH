package com.bro.lotteryledger.core

/**
 * 图片的粗略统计（本地算，不联网、不上传）。
 *
 * 用来回答一个问题：**这张图有没有可能是一张彩票？**
 *
 * 少爷 2026-09-27 问：手抖拍到桌面/地面，能不能在调 API 之前就拦掉。
 *
 * ## 先说清楚能做到什么、做不到什么
 *
 * **做不到**：准确地判断「这是不是彩票」。
 * 那需要真正的视觉理解，本地没有模型，判断只能靠调 API ——
 * 而「调 API 之前就判断」和「调 API 判断」本身是矛盾的。
 *
 * **做得到**：拦掉**确定不是**票的图（几乎全黑、整片同色），
 * 并且对**明显不像**票的图（色彩很鲜艳）给一句提醒。
 * 这一层是「宁可漏拦，不可误拦」—— 误拦一张真票，比放过一张桌面照片糟糕得多。
 *
 * ## 为什么用亮度 + 饱和度这两个指标
 *
 * 彩票是**热敏纸**：浅色纸面（白/米黄）+ 黑色字。
 * 所以真票的照片有两个稳定特征：
 *  - 亮度**偏高且分布分散**（有纸的白、也有字的黑）
 *  - 饱和度**低**（基本是黑白灰，只有 logo 有一点点颜色）
 *
 * 桌面/草地/彩色墙面这些误拍对象，饱和度都明显更高。
 * 这不是精确判断，但足以把最离谱的那几类挡在门外。
 */
data class ImageStats(
    /** 平均亮度，0..255 */
    val brightnessMean: Double,
    /** 亮度标准差。越小说明画面越「平」（整片同色） */
    val brightnessStd: Double,
    /** 平均饱和度，0..1 */
    val saturationMean: Double
) {
    companion object {
        /** 拿不到统计时（解码异常）用这个 —— [ImageGuard] 见到它会直接放行。 */
        val UNKNOWN = ImageStats(-1.0, -1.0, -1.0)

        /**
         * 从 ARGB 像素数组算统计。
         *
         * **纯计算，没有任何 Android 依赖** —— 这是刻意的：
         * ① 能单测（含耗时上限）；
         * ② 调用方只要做「一次性取出像素」这一个昂贵动作，
         *    循环本身是纯 Java 数组遍历，微秒级。
         *
         * ⚠️ 别改成逐像素 `Bitmap.getPixel()` —— 那是**每次一个 JNI 调用**，
         * 4096 个点就是 4096 次跨语言调用。取整块用 `getPixels(IntArray)` 只有一次。
         *
         * @param argb 长度应为 side×side（调用方自己决定采样尺寸，64×64 足够）
         */
        fun fromArgb(argb: IntArray): ImageStats {
            val n = argb.size
            if (n == 0) return UNKNOWN

            var sumB = 0.0
            var sumB2 = 0.0
            var sumS = 0.0
            for (p in argb) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // 感知亮度（人眼对绿最敏感，系数来自 Rec.601）
                val lum = 0.299 * r + 0.587 * g + 0.114 * b
                sumB += lum
                sumB2 += lum * lum
                val mx = if (r > g) (if (r > b) r else b) else (if (g > b) g else b)
                val mn = if (r < g) (if (r < b) r else b) else (if (g < b) g else b)
                sumS += if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            }

            val mean = sumB / n
            // 用 E[x²] - E[x]² 一次遍历算方差，不用先存一份数据
            val variance = (sumB2 / n) - mean * mean
            return ImageStats(
                brightnessMean = mean,
                brightnessStd = kotlin.math.sqrt(variance.coerceAtLeast(0.0)),
                saturationMean = sumS / n
            )
        }
    }
}

object ImageGuard {

    enum class Verdict {
        /** 看着像正常照片，放行 */
        OK,

        /** 太暗，几乎全黑 */
        TOO_DARK,

        /** 几乎没有内容（整片同色） */
        FLAT,

        /** 色彩很鲜艳，不像票面 */
        TOO_COLORFUL
    }

    data class Result(val verdict: Verdict, val message: String) {
        /** 直接拦掉，不发请求 */
        val blocks: Boolean get() = verdict == Verdict.TOO_DARK || verdict == Verdict.FLAT

        /** 只是提醒，用户可以继续 */
        val warns: Boolean get() = verdict == Verdict.TOO_COLORFUL
    }

    /** 低于这个亮度基本是全黑照片 */
    const val MIN_BRIGHTNESS = 25.0

    /**
     * 亮度标准差低于这个值 = 整片同色。
     *
     * 6 分得很保守：一张正常拍摄的、稍微有点内容的照片，标准差远高于这个数
     * （纯白纸 + 一点阴影都不止 6）。宁可放过，也不要把真票拦了。
     */
    const val MIN_STD = 6.0

    /**
     * 饱和度高于这个值 = 颜色太艳，不像热敏纸。
     *
     * 0.42 是实验出来的保守值：真票照片通常在 0.05~0.2；
     * 草地、木桌、彩色桌布普遍在 0.4 以上。
     * 这一档**只提醒不拦** —— 因为「拍了一张颜色很艳的彩票」理论上也可能发生
     * （比如票放在彩色桌布上），拦了就是误伤。
     */
    const val MAX_SATURATION = 0.42

    /**
     * 判定。
     *
     * 统计拿不到（[ImageStats.UNKNOWN]）时一律放行 ——
     * 本地预筛出错的代价要它自己承担，绝不能因为统计失败就不让用户识别。
     */
    fun judge(s: ImageStats): Result {
        if (s.brightnessMean < 0) return Result(Verdict.OK, "")

        return when {
            s.brightnessMean < MIN_BRIGHTNESS -> Result(
                Verdict.TOO_DARK,
                "这张照片几乎是全黑的，不可能是彩票 —— 没有发去识别，省一次调用。\n" +
                    "请检查一下是不是镜头被挡住了、或者光线太暗。"
            )

            s.brightnessStd < MIN_STD -> Result(
                Verdict.FLAT,
                "这张照片几乎没有内容（整片都是同一种颜色），不可能是彩票 —— " +
                    "没有发去识别。\n请重新拍一张，让票面填满画面。"
            )

            s.saturationMean > MAX_SATURATION -> Result(
                Verdict.TOO_COLORFUL,
                "这张照片的颜色很鲜艳，不太像彩票（彩票是浅色纸面 + 黑字）。\n" +
                    "如果确实是票，继续就行；如果是误拍，建议回去重选一张。"
            )

            else -> Result(Verdict.OK, "")
        }
    }
}
