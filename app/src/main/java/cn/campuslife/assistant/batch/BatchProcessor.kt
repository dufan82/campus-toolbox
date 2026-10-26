package cn.campuslife.assistant.batch

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import cn.campuslife.assistant.image.PhotoAsset
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.image.PhotoExporter
import cn.campuslife.assistant.image.PhotoLoader
import cn.campuslife.watermark.ExportFormat
import cn.campuslife.watermark.ExportNaming
import cn.campuslife.watermark.formattedFields
import java.io.File
import java.io.IOException
import java.util.UUID

/** One invocation owns at most one import/bitmap/output cache and finishes it before returning. */
class BatchProcessor(private val context: Context, private val store: BatchJobStore,
    private val finishOutput: (Uri) -> Unit = BatchOutputs(context)::finish) {
    private val loader = PhotoLoader(context)
    private val exporter = PhotoExporter(context)
    private val outputs = BatchOutputs(context)

    fun process(initial: BatchJob, index: Int, onCheckpoint: (BatchJob) -> Unit): BatchJob {
        var job = initial
        var photo: PhotoAsset? = null
        var cache: File? = null
        fun checkpoint(next: BatchJob) { store.save(next); job = next; onCheckpoint(next) }
        try {
            if (job.items[index].state == BatchItemState.WRITTEN) {
                // The complete stream was closed before WRITTEN was saved. Commit the same owned URI.
                finishOutput(Uri.parse(job.items[index].outputUri!!))
                checkpoint(job.succeeded(index))
            } else {
                checkpoint(job.begin(index))
                photo = loader.importPhoto(Uri.parse(job.items[index].input.uri))
                checkpoint(job.imported(index, photo.file.name))
                val options = job.options
                val details = options.detailsFor(photo.capturedAt)
                val texts = formattedFields(options.profile, details, options.config)
                val name = BatchNames.fileName(options, photo.capturedAt, index + 1, UUID.randomUUID().toString().replace("-", "").take(8))
                cache = exporter.toCache(photo, texts, options.config.style, options.format, options.maxEdge, ExportNaming.baseName(name))
                // Persist the create intent first: a crash in createDocument/insert is uncertain, never auto-retry.
                checkpoint(job.creating(index, name))
                val uri = outputs.create(job.treeUri, name, options.format)
                check(uri.toString() != job.items[index].input.uri) { "Output aliases input" }
                checkpoint(job.created(index, uri.toString(), name))
                exporter.copyToDocument(cache, uri)
                checkpoint(job.written(index))
                finishOutput(uri)
                checkpoint(job.succeeded(index))
            }
        } catch (error: BatchPersistenceException) {
            throw error
        } catch (error: Exception) {
            checkpoint(job.failed(index, BatchErrors.describe(error, job.items[index].state)))
        } catch (_: OutOfMemoryError) {
            checkpoint(job.failed(index, "本机内存不足；请选择较小的最大边长后新建批次，原图未修改"))
        } finally {
            photo?.let { releaseImport(it) }
            cache?.let { it.delete(); it.parentFile?.delete() }
        }
        // Retain the exact filename if cleanup failed; recovery only touches explicitly owned imports.
        if (photo != null && !photo.file.exists()) checkpoint(job.imported(index, null))
        return job
    }

    /** Only called after an explicit confirmation; never removes input or published media. */
    fun resolveReviewed(job: BatchJob, index: Int, retry: Boolean): BatchJob {
        val item = job.items[index]
        require(item.state in setOf(BatchItemState.REVIEW, BatchItemState.WRITTEN))
        item.outputUri?.let { outputs.removeUnfinished(job, index) }
        val resolved = job.resolveReview(index, retry, outputRemoved = item.outputUri != null)
        store.save(resolved)
        return resolved
    }

    fun preservePublished(job: BatchJob, index: Int): BatchJob {
        outputs.requirePublished(job, index)
        return job.keepOutputAndSkip(index).also(store::save)
    }

    /** Legacy SAF has no hidden/pending state. User handles the file; retain the exact reference. */
    fun acknowledgeDocumentOutput(job: BatchJob, index: Int): BatchJob {
        outputs.requireLegacyDocumentReference(job, index)
        return job.keepOutputAndSkip(index).also(store::save)
    }

    fun cleanupRecordedImports(job: BatchJob): BatchJob {
        var cleaned = job
        val protectedDraft = LocalStore(context).draftName()
        job.items.forEachIndexed { index, item -> item.importedName?.let { name ->
            val file = File(loader.draftFolder, name)
            if (name == protectedDraft) {
                // Even a parseable but incorrect ownership record cannot delete the editor's current file.
                cleaned = cleaned.imported(index, null)
            } else if (name.matches(Regex("photo_[a-f0-9-]{36}\\.img")) && file.canonicalFile.parentFile == loader.draftFolder.canonicalFile) {
                if (!file.exists() || file.delete()) cleaned = cleaned.imported(index, null)
            }
        } }
        return cleaned
    }
    companion object {
        fun releaseImport(photo: PhotoAsset) {
            if (!photo.preview.isRecycled) photo.preview.recycle()
            // This exact PhotoAsset came from this module's importPhoto, never restore/single-image draft.
            photo.file.delete()
        }
    }
}

internal class BatchOutputs(private val context: Context) {
    private val folder = "Pictures/校园生活助手"
    fun create(tree: String?, name: String, format: ExportFormat): Uri {
        val mime = if (format == ExportFormat.PNG) "image/png" else "image/jpeg"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Create failed")
        } else {
            val uri = Uri.parse(requireNotNull(tree) { "请先选择批量保存目录" })
            require(DocumentsContract.isTreeUri(uri))
            val parent = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            DocumentsContract.createDocument(context.contentResolver, parent, mime, name) ?: throw IOException("Create failed")
        }
    }
    fun finish(uri: Uri) {
        // Validate the actual output before publishing, including when recovering WRITTEN.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Output missing")
        input.use { BitmapFactory.decodeStream(it, null, bounds) }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Output invalid" }
        if (Build.VERSION.SDK_INT >= 29) {
            require(uri.authority == MediaStore.AUTHORITY)
            val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            check(context.contentResolver.update(uri, values, null, null) > 0) { "Publish failed" }
        }
    }

    private fun ownedUri(job: BatchJob, index: Int): Uri {
        val item = job.items[index]
        require(item.state in setOf(BatchItemState.REVIEW, BatchItemState.WRITTEN))
        val uri = Uri.parse(requireNotNull(item.outputUri))
        require(uri.scheme == "content" && job.items.none { it.input.uri == uri.toString() } &&
            job.items.withIndex().none { (i, other) -> i != index && other.outputUri == uri.toString() }) {
            "输出记录不明确，已保留，请手动核对"
        }
        return uri
    }

    /** null means this exact recorded row is already gone (e.g. deletion succeeded before a crash). */
    private fun pending(job: BatchJob, index: Int, uri: Uri): Boolean? {
        check(Build.VERSION.SDK_INT >= 29)
        require(uri.authority == MediaStore.AUTHORITY && uri.pathSegments.size == 4 &&
            uri.pathSegments[1] == "images" && uri.pathSegments[2] == "media" &&
            uri.pathSegments[3].toLongOrNull()?.let { it > 0 } == true) { "输出来源不明确，已保留" }
        val columns = arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.OWNER_PACKAGE_NAME,
            MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.IS_PENDING)
        val cursor = context.contentResolver.query(uri, columns, null, null, null)
            ?: throw IOException("无法核对输出，原记录已保留")
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            require(it.getString(0) == job.items[index].outputName && it.getString(1) == context.packageName &&
                it.getString(2)?.trimEnd('/') == folder) { "文件归属或名称已改变，未删除，原记录已保留" }
            it.getInt(3) == 1
        }
    }

    fun removeUnfinished(job: BatchJob, index: Int) {
        val uri = ownedUri(job, index)
        require(Build.VERSION.SDK_INT >= 29) { "此系统的目录输出请先手动核对；可保留文件并跳过，不自动删除" }
        val pending = pending(job, index, uri) ?: return
        require(pending) { "图片已发布，不会删除。请先查看，再选择保留输出并跳过" }
        // Guard the mutation too: a concurrent publisher must never turn this into deletion of a saved image.
        val removed = context.contentResolver.delete(uri, "${MediaStore.Images.Media.IS_PENDING} = ? AND ${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf("1", context.packageName, job.items[index].outputName!!))
        check(removed == 1 || pending(job, index, uri) == null) { "未能移除未完成输出，原记录已保留" }
    }

    fun requirePublished(job: BatchJob, index: Int) {
        val uri = ownedUri(job, index)
        if (Build.VERSION.SDK_INT >= 29) {
            require(pending(job, index, uri) == false) { "输出尚未发布。请继续保存，或确认移除未完成输出" }
        } else {
            require(DocumentsContract.isDocumentUri(context, uri)) { "输出记录不明确，原记录已保留" }
            context.contentResolver.openFileDescriptor(uri, "r")?.use { } ?: throw IOException("输出无法读取，原记录已保留")
        }
    }

    fun requireLegacyDocumentReference(job: BatchJob, index: Int) {
        require(Build.VERSION.SDK_INT <= 28) { "相册输出请使用继续保存或处理未完成输出" }
        val uri = ownedUri(job, index)
        // No provider query/open/delete: the acknowledged file may be missing or its grant expired.
        require(uri.authority != MediaStore.AUTHORITY && "document" in uri.pathSegments) {
            "输出地址不是目录文档，原记录已保留"
        }
    }
}

internal object BatchErrors {
    fun describe(error: Exception, state: BatchItemState): String = when {
        error is SecurityException -> "文件访问权限已失效；请重新选择该照片或保存目录，再重试"
        state in setOf(BatchItemState.CREATING, BatchItemState.WRITING, BatchItemState.WRITTEN) ->
            "保存未完成，请检查目录权限与剩余空间；输出可能已创建，请先核对，避免重复"
        error is IllegalArgumentException && error.message?.contains("64") == true -> "照片超过 64 MiB，请选择较小的 JPEG / PNG 副本"
        error is IllegalArgumentException && error.message?.contains("8000") == true -> "照片超过 8000 万像素，请选择较小的副本"
        error is IllegalArgumentException && error.message?.contains("内存") == true -> "原尺寸超出本机处理内存；请显式选择较小最大边长后新建批次"
        error is IllegalArgumentException -> "照片格式或尺寸无效；仅支持完整 JPEG / PNG，HEIC 请先转换"
        else -> "照片读取或处理失败，请检查访问权限、文件是否损坏及剩余空间，再重新选择或重试"
    }
}
