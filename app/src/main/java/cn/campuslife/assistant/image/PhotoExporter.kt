package cn.campuslife.assistant.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import cn.campuslife.watermark.*
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class PhotoExporter(private val context: Context) {
    fun toCache(photo: PhotoAsset, texts: List<String>, style: WatermarkStyle, format: ExportFormat, maxEdge: Int?=null,baseName: String?=null): File {
        val plan = ExportPolicy.plan(photo.rawWidth, photo.rawHeight, photo.orientation, format, (Runtime.getRuntime().maxMemory() * .65).toLong(),maxEdge)
        val layout = WatermarkRenderer.layout(photo.width,photo.height,texts,style)
        val folder = File(context.cacheDir, "share").apply { mkdirs() }
        val expiry=System.currentTimeMillis()-24L*60*60*1000
        folder.listFiles()?.filter { it.isFile && it.name.startsWith("活动水印_") && it.lastModified()<expiry }?.forEach { it.delete() }
        folder.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("job_[a-f0-9-]{36}")) && it.lastModified()<expiry }?.forEach { it.deleteRecursively() }
        val name=ExportNaming.fileName(baseName ?: "活动水印_${SimpleDateFormat("yyyyMMdd_HHmmss",Locale.CHINA).format(Date())}_${UUID.randomUUID().toString().take(6)}",format)
        val job=File(folder,"job_${UUID.randomUUID()}")
        check(job.mkdir()) { "无法创建导出缓存，请检查存储空间" }
        val file=File(job,name)
        var raw: Bitmap? = null
        var result: Bitmap? = null
        try {
            raw = BitmapFactory.decodeFile(photo.file.absolutePath, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize=plan.sampleSize
            })
                ?: throw IOException("原照片读取失败，请重新选择")
            result = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            if (format == ExportFormat.JPEG) canvas.drawColor(android.graphics.Color.WHITE)
            WatermarkRenderer.drawOriented(canvas, raw, photo.orientation, plan.width, plan.height)
            canvas.save()
            canvas.scale(plan.width.toFloat()/photo.width,plan.height.toFloat()/photo.height)
            WatermarkRenderer.drawWatermark(canvas, layout, style)
            canvas.restore()
            file.outputStream().use { output ->
                val type = if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                if (!result.compress(type, plan.quality, output)) throw IOException("图片编码失败，请重试")
            }
            return file
        } catch (_: OutOfMemoryError) {
            file.delete();job.delete(); throw IOException("手机可用内存不足，请关闭其他应用后重试或选择较小照片；原图未修改")
        } catch (error: Exception) { file.delete();job.delete(); throw error
        } finally { raw?.recycle(); result?.recycle() }
    }

    fun saveToGallery(file: File): Uri {
        require(Build.VERSION.SDK_INT >= 29)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, if (file.extension == "png") "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/校园生活助手")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("无法创建相册图片，请检查存储空间")
        try {
            copyToDocument(file, uri)
            val finished = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            check(resolver.update(uri, finished, null, null) > 0) { "相册未能完成保存，请重试" }
            return uri
        } catch (error: Exception) { resolver.delete(uri, null, null); throw error }
    }

    fun copyToDocument(file: File, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri) ?: throw IOException("没有写入所选位置的权限，请换一个位置")
        output.use { file.inputStream().use { input -> input.copyTo(it) } }
    }
}
