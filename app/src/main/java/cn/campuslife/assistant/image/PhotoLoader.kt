package cn.campuslife.assistant.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import cn.campuslife.watermark.Orientation
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max

data class PhotoAsset(val file: File, val rawWidth: Int, val rawHeight: Int, val orientation: Int,
    val preview: Bitmap, val capturedAt: String?) {
    val width get() = if (Orientation(orientation).swapsAxes) rawHeight else rawWidth
    val height get() = if (Orientation(orientation).swapsAxes) rawWidth else rawHeight
}

class PhotoLoader(private val context: Context) {
    val draftFolder get() = File(context.filesDir, "photos").apply { mkdirs() }

    fun importPhoto(uri: Uri): PhotoAsset {
        val file = File(draftFolder, "photo_${UUID.randomUUID()}.img")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= 64L * 1024 * 1024) { "照片超过 64 MB，请选择较小的 JPEG 或 PNG 副本" }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IOException("无法读取这张照片，请重新选择并授予本次文件访问")
            return loadFile(file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    fun restore(name: String): PhotoAsset? {
        val file = File(draftFolder, name)
        if (!file.isFile || file.canonicalFile.parentFile != draftFolder.canonicalFile) return null
        return loadFile(file)
    }

    fun loadFile(file: File): PhotoAsset {
        require(file.length() in 1..(64L * 1024 * 1024)) { "照片为空或超过 64 MB，请重新选择" }
        val bytes = ByteArray(8)
        val count = file.inputStream().use { it.read(bytes) }
        val jpeg = count >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        val png = count == 8 && bytes.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        require(jpeg || png) { "暂不支持这种格式，请选择 JPEG 或 PNG；HEIC 请先转换" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "照片已损坏或无法解码，请换一张" }
        require(bounds.outWidth.toLong() * bounds.outHeight <= 80_000_000L) { "照片超过 8000 万像素，请选择较小尺寸的副本" }
        val exif = try { ExifInterface(file.absolutePath) } catch (_: IOException) { null }
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)?.takeIf { it in 1..8 } ?: 1
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val raw = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IOException("照片无法解码，请重新选择")
        val swap = Orientation(orientation).swapsAxes
        val preview = try {
            Bitmap.createBitmap(if (swap) raw.height else raw.width, if (swap) raw.width else raw.height, Bitmap.Config.ARGB_8888).also {
                WatermarkRenderer.drawOriented(Canvas(it), raw, orientation, it.width, it.height)
            }
        } finally { raw.recycle() }
        val date = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        val capture = date?.takeIf { it.matches(Regex("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}")) }
            ?.let { it.take(10).replace(':', '-') + it.substring(10) }
        return PhotoAsset(file, bounds.outWidth, bounds.outHeight, orientation, preview, capture)
    }
}
