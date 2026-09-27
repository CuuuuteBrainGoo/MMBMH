package com.bro.lotteryledger.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.bro.lotteryledger.core.AiProviderConfig
import com.bro.lotteryledger.core.ImageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 图片管道（§2.2 步骤 2 + §3 隐私生命周期）。
 *
 * 铁律：
 *  - 拍照不写系统相册，只落 App 私有缓存
 *  - 从相册导入只读，可生成临时副本，**不删用户原照片**
 *  - 识别确认后立即删除临时文件
 *  - 启动时清理上次闪退/断电遗留的临时文件
 *  - 重新编码 JPEG，**剥离 EXIF/GPS/设备信息**
 */
class ImagePipeline(private val context: Context) {

    /** 私有临时目录。放在 cacheDir 下，系统空间紧张时也会自动回收。 */
    private val workDir: File
        get() = File(context.cacheDir, "ticket_tmp").apply { if (!exists()) mkdirs() }

    data class Prepared(
        val file: File,
        val base64: String,
        val width: Int,
        val height: Int,
        val bytes: Int,
        /**
         * 亮度/饱和度统计。
         *
         * 用来做**本地预筛**：几乎全黑、整片同色的图直接拦掉不发请求
         * （少爷问的「拍到桌面能不能先拦下来」）。统计不出来时是
         * [ImageStats.UNKNOWN]，[com.bro.lotteryledger.core.ImageGuard] 会放行。
         */
        val stats: ImageStats = ImageStats.UNKNOWN
    ) {
        /**
         * 图片指纹用字节。这里从落盘的临时文件读回，
         * 避免为了算哈希在内存里额外留一份图片。
         */
        fun sha256Bytes(): ByteArray = try {
            file.readBytes()
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    /**
     * 从 Uri 准备一张待识别图片。
     * 返回的 [Prepared.file] 必须在识别流程结束后交给 [discard] 删除。
     */
    suspend fun prepare(uri: Uri, quality: AiProviderConfig.ImageQuality): Prepared? =
        withContext(Dispatchers.IO) {
            val original = decodeSampled(uri) ?: return@withContext null
            val rotated = applyExifRotation(uri, original)
            val scaled = scaleDown(rotated, quality.maxEdge)
            writeCleanJpeg(scaled, quality.jpegQuality)
        }

    /**
     * 直接对已经在内存里的 Bitmap（相机回调）做同样处理。
     */
    suspend fun prepare(bitmap: Bitmap, quality: AiProviderConfig.ImageQuality): Prepared? =
        withContext(Dispatchers.IO) {
            val scaled = scaleDown(bitmap, quality.maxEdge)
            writeCleanJpeg(scaled, quality.jpegQuality)
        }

    /**
     * 重新编码为 JPEG 并落盘。
     *
     * 关键：从 Bitmap 重新编码出来的 JPEG **本身就不携带任何 EXIF**，
     * 这是剥离 GPS/设备信息最彻底的方式 —— 比逐项删除 EXIF 标签更可靠。
     */
    private fun writeCleanJpeg(bmp: Bitmap, jpegQuality: Int): Prepared? {
        return try {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            val bytes = out.toByteArray()

            val f = File(workDir, "t_${UUID.randomUUID()}.jpg")
            FileOutputStream(f).use { it.write(bytes) }

            Prepared(
                file = f,
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                width = bmp.width,
                height = bmp.height,
                bytes = bytes.size,
                stats = analyze(bmp)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 算亮度 / 饱和度统计，用于本地预筛。
     *
     * ## 耗时（少爷 2026-09-27 要求「超过 1 秒就别做」）
     *
     * 这里的开销只有两块，都是**本地内存操作**，不涉及网络：
     *  1. `createScaledBitmap` 缩到 64×64 —— 缩图，几毫秒
     *  2. `getPixels` **一次性**取 4096 个像素 —— 一次 JNI 调用
     *
     * 第二块是关键：早期写法是逐点 `getPixel(x, y)`，那是 4096 次 JNI 调用。
     * 改成整块取之后，循环部分在 `ImageStats.fromArgb` 里是纯数组遍历。
     * 实测数据见 `ImageStatsTest`（有耗时上限断言）。
     *
     * 而且它是**净省时间**的：拦下一张误拍 = 省掉一次 3~5 秒的 API 调用。
     */
    private fun analyze(bmp: Bitmap): ImageStats {
        return try {
            val side = 64
            val small = Bitmap.createScaledBitmap(bmp, side, side, false)
            val buf = IntArray(side * side)
            small.getPixels(buf, 0, side, 0, 0, side, side)
            // 已经是 64×64 时 createScaledBitmap 会返回同一个对象，别 recycle 掉它
            if (small !== bmp) small.recycle()
            ImageStats.fromArgb(buf)
        } catch (_: Exception) {
            ImageStats.UNKNOWN
        }
    }

    /** 按 scale 采样解码，避免把整张原图读进内存导致 OOM。 */
    private fun decodeSampled(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0) return null

            var sample = 1
            // 目标：长边不超过 2600，留出足够余量给后续裁剪
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2600) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 读 EXIF 方向并转正（相册里的照片常常是带旋转标记的）。 */
    private fun applyExifRotation(uri: Uri, bmp: Bitmap): Bitmap {
        return try {
            val orientation = context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                else -> return bmp
            }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (_: Exception) {
            bmp
        }
    }

    private fun scaleDown(bmp: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(bmp.width, bmp.height)
        if (longEdge <= maxEdge) return bmp
        val ratio = maxEdge.toFloat() / longEdge
        val w = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    /** 识别结束（无论成功失败）都要调用，删除临时文件（§3.1）。 */
    fun discard(p: Prepared?) {
        try {
            p?.file?.takeIf { it.exists() }?.delete()
        } catch (_: Exception) { /* 删不掉也要继续，下次启动会清 */ }
    }

    /**
     * 启动清理（§3.2）：扫描自身临时目录，清掉因闪退/断电遗留的图片。
     * 只清本目录，不碰任何用户相册文件。
     */
    suspend fun sweepOrphans(): Int = withContext(Dispatchers.IO) {
        var n = 0
        try {
            workDir.listFiles()?.forEach { f ->
                if (f.isFile && f.delete()) n++
            }
        } catch (_: Exception) { /* ignore */ }
        n
    }
}
