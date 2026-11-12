package cn.campuslife.assistant.daily

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Original image files only. Call all file/media I/O on a worker; the caller owns UI and viewing. */
class Daily60ImageFiles(private val context:Context) {
    /** Unique cache/share file, never an overwrite, resize or recompression. Caller owns its lifetime. */
    fun toSharedFile(bytes:ByteArray,mime:String,savedAtMillis:Long):File {
        val extension=extension(mime)
        require(bytes.isNotEmpty()) {"图片内容为空"}
        val folder=File(context.cacheDir,"share")
        if(!folder.isDirectory && !folder.mkdirs()) throw IOException("无法创建图片分享目录")
        val file=File.createTempFile("daily60_${savedAtMillis}_",".$extension",folder)
        try {
            file.outputStream().use {it.write(bytes)}
            return file
        } catch(error:Exception) {
            file.delete()
            throw error
        }
    }

    /** API 29+: creates exactly one pending row; never deletes an existing/user-selected image. */
    fun saveToGallery(file:File,mime:String):Uri {
        extension(mime)
        require(Build.VERSION.SDK_INT>=29) {"此系统请使用系统另存为"}
        requireReadable(file)
        val values=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,file.name)
            put(MediaStore.Images.Media.MIME_TYPE,mime)
            put(MediaStore.Images.Media.RELATIVE_PATH,"${Environment.DIRECTORY_PICTURES}/校伴")
            put(MediaStore.Images.Media.IS_PENDING,1)
        }
        val resolver=context.contentResolver
        val created=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)
            ?: throw IOException("无法创建相册图片")
        try {
            copyToDocument(file,created)
            val finished=ContentValues().apply {put(MediaStore.Images.Media.IS_PENDING,0)}
            if(resolver.update(created,finished,null,null)!=1) throw IOException("相册图片未能完成保存")
            return created
        } catch(error:Exception) {
            try {resolver.delete(created,null,null)} catch(cleanup:Exception) {error.addSuppressed(cleanup)}
            throw error
        }
    }

    /** API 26+ SAF destination. Input opens first; a failed output/close propagates, never deletes that URI. */
    fun copyToDocument(file:File,uri:Uri) {
        requireReadable(file)
        file.inputStream().use {input ->
            val output=context.contentResolver.openOutputStream(uri,"wt") ?: throw IOException("无法写入所选位置")
            output.use {input.copyTo(it);it.flush()}
        }
    }

    /** Builds only: viewer resolution, ActivityNotFoundException and launching belong to the caller. */
    fun openIntent(file:File,mime:String):Intent {
        extension(mime)
        requireReadable(file)
        require(file.canonicalFile.parentFile==File(context.cacheDir,"share").canonicalFile) {"图片不在分享目录"}
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData=ClipData.newRawUri(file.name,uri)
        }
    }

    private fun extension(mime:String):String = when(mime) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> throw IllegalArgumentException("仅支持 PNG、JPEG 或 WebP 原图")
    }
    private fun requireReadable(file:File) {
        if(!file.isFile || file.length()==0L || !file.canRead()) throw IOException("原图文件不可读取")
    }
}
