package cn.campuslife.assistant.batch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.image.PhotoAsset
import cn.campuslife.assistant.image.PhotoLoader
import cn.campuslife.watermark.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Application-scoped owner: rotation/new Activity instances rebind to this single executor. */
internal class BatchSession(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val store = BatchJobStore(context)
    private val processor = BatchProcessor(context, store)
    private val previewToken = AtomicInteger()
    private val settings = LocalStore(context, "campus_batch_editor_v1")
    private var receiver: (() -> Unit)? = null
    @Volatile var job: BatchJob? = null
        private set
    var options = BatchOptions.freeze(CampusProfile(), ActivityDetails(), EditorConfig(), ExportNaming.defaultFields, ExportFormat.JPEG, null, false)
        private set
    var loaded = false
        private set
    var busy = true
        private set
    var blocked = false
        private set
    var message: String? = null
        private set
    var needsContinue = false
    var selectedIndex = 0
        private set
    var preview: PhotoAsset? = null
        private set
    var previewBusy = false
        private set
    var previewError: String? = null
        private set
    var treeUri: String? = null
        private set
    @Volatile private var stop = 0 // 0 none, 1 pause, 2 cancel; read between complete photos only.
    @Volatile private var foreground = false
    val editable get() = loaded && !blocked && !busy && (job == null || job?.state == BatchRunState.READY)

    init { load() }
    fun bind(callback: () -> Unit) { receiver = callback; foreground = true; callback() }
    fun unbind() { receiver = null }
    private fun emit() { main.post { receiver?.invoke() } }

    fun load() {
        busy = true
        executor.execute {
            try {
                val shared = LocalStore(context)
                val own = context.getSharedPreferences("campus_batch_editor_v1", Context.MODE_PRIVATE)
                val previous = store.load()
                job = previous?.let { processor.cleanupRecordedImports(it).recover() }
                job?.let { store.save(it) }
                val initial = job?.options ?: BatchOptions.freeze(shared.profile(),
                    if (own.contains("details")) settings.details() else shared.details(),
                    if (own.contains("config")) settings.config() else shared.config(),
                    if (own.contains("naming_fields")) settings.namingFields() else shared.namingFields(), ExportFormat.JPEG, null, false)
                main.post {
                    options = initial; treeUri = job?.treeUri; loaded = true; blocked = false; busy = false
                    needsContinue = previous != null && previous.items.any { it.state !in setOf(BatchItemState.SUCCESS, BatchItemState.SKIPPED) }
                    message = settings.recoveryMessage ?: shared.recoveryMessage
                    receiver?.invoke()
                    if (job != null) requestPreview(0)
                }
            } catch (_: Exception) {
                main.post {
                    loaded = true; busy = false; blocked = true
                    message = "批量任务记录无法读取，原文件已保留。请保留应用数据并反馈此问题；可尝试重新读取"
                    receiver?.invoke()
                }
            }
        }
    }

    fun configure(value: BatchOptions) {
        if (!editable) return
        options = BatchOptions.freeze(value.profile, value.details, value.config, value.namingFields, value.format, value.maxEdge, value.unifiedTime, value.timestamp)
        val captured = options
        executor.execute {
            try {
                settings.saveConfig(captured.config); settings.saveDetails(captured.details); settings.saveNamingFields(captured.namingFields)
                job?.takeIf { it.state == BatchRunState.READY }?.let { persist(it.copy(options = captured)) }
            } catch (_: Exception) { failStorage() }
        }
    }
    fun refreshProfile() {
        if (!editable) return
        executor.execute {
            val profile = LocalStore(context).profile()
            main.post { if (editable) { configure(options.copy(profile = profile)); receiver?.invoke() } }
        }
    }

    fun select(uris: List<Uri>, flags: Int, replaceIndex: Int? = null) {
        if (busy || blocked) return
        if (replaceIndex == null && job?.hasUnresolvedOutputs() == true) {
            message = "请先在明细中继续保存或处理未完成输出，再选择新批次"
            receiver?.invoke(); return
        }
        if (uris.size !in 1..50) { message = "每批最多 50 张，请重新选择；没有截断本次选择"; receiver?.invoke(); return }
        clearPreview(); busy = true; message = null; receiver?.invoke()
        val snapshot = options
        executor.execute {
            try {
                val inputs = uris.distinct().map { uri ->
                    require(uri.scheme == "content")
                    var persisted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                    if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                        try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); persisted = true }
                        catch (_: Exception) { /* temporary access remains usable until it expires */ }
                    }
                    var name = "照片"; var size: Long? = null
                    try { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val b = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n).take(2000)
                            if (b >= 0 && !cursor.isNull(b)) size = cursor.getLong(b).takeIf { it >= 0 }
                        }
                    } } catch (_: Exception) { /* PhotoLoader validates bytes and actual image type on import. */ }
                    BatchInput(uri.toString(), name, persisted, size)
                }
                if (replaceIndex == null) persist(BatchJob.create(inputs, snapshot, treeUri = treeUri))
                else { require(inputs.size == 1); persist(requireNotNull(job).reselect(replaceIndex, inputs.single())) }
                main.post {
                    busy = false; selectedIndex = replaceIndex ?: 0
                    message = if (inputs.any { !it.persisted }) "部分照片只获临时访问权限；重开后若失效，请在明细中重新选择" else null
                    receiver?.invoke(); requestPreview(selectedIndex)
                }
            } catch (_: Exception) {
                main.post { busy = false; message = "选取或保存照片列表失败，请检查文件权限及剩余空间后重试"; receiver?.invoke() }
            }
        }
    }

    fun setTree(uri: Uri, flags: Int) {
        if (busy || blocked) return
        command {
            require(android.provider.DocumentsContract.isTreeUri(uri))
            val grants = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            require(grants and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            var persisted = true
            try { context.contentResolver.takePersistableUriPermission(uri, grants) } catch (_: Exception) { persisted = false }
            treeUri = uri.toString()
            job?.let { persist(it.copy(treeUri = treeUri)) }
            main.post { message = if (persisted) "已选择保存目录，将逐张新建文件" else "目录只有临时授权；权限失效时请重新选择目录" }
        }
    }

    fun start() {
        if (!loaded || busy || blocked || job == null) return
        clearPreview(); stop = 0; message = null
        command {
            var current = requireNotNull(job)
            if (current.state == BatchRunState.READY) {
                // Copy at launch, including latest locally edited profile, then never consult live preferences again.
                val fresh = LocalStore(context).profile()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
                val frozen = BatchOptions.freeze(fresh, options.details, options.config, options.namingFields, options.format,
                    options.maxEdge, options.unifiedTime, timestamp)
                current = current.copy(options = frozen)
                main.post { options = frozen }
            }
            persist(current.start())
            while (true) {
                current = requireNotNull(job)
                if (!foreground || stop != 0) {
                    persist(current.requestStop(stop == 2).settle()); break
                }
                val index = current.nextIndex()
                if (index == null) { persist(current.settle()); break }
                main.post { selectedIndex = index }
                processor.process(current, index) { next -> job = next; emit() }.also { job = it }
            }
        }
    }
    fun pause(cancel: Boolean = false) {
        stop = maxOf(stop, if (cancel) 2 else 1)
        message = if (cancel) "取消已请求：安全完成当前照片后停止，已保存图片保留" else "暂停已请求：安全完成当前照片后停止"
        if (!busy && job != null && !blocked) command { persist(requireNotNull(job).requestStop(cancel).settle()) }
        receiver?.invoke()
    }
    fun background() { foreground = false; if (busy && job?.state == BatchRunState.RUNNING) pause(); clearPreview() }
    fun retryFailures() { if (!busy && !blocked) command { persist(requireNotNull(job).retryFailures()) } }
    fun skip(index: Int) { if (!busy && !blocked) command { persist(requireNotNull(job).skip(index).settle()) } }
    fun resolve(index: Int, retry: Boolean) { if (!busy && !blocked) command {
        job = processor.resolveReviewed(requireNotNull(job), index, retry); emit()
    } }
    fun preserve(index: Int) { if (!busy && !blocked) command {
        job = processor.preservePublished(requireNotNull(job), index); emit()
    } }
    fun acknowledgeDocument(index: Int) { if (!busy && !blocked) command {
        job = processor.acknowledgeDocumentOutput(requireNotNull(job), index); emit()
    } }

    fun requestPreview(index: Int) {
        val current = job ?: return
        if (busy || blocked || index !in current.items.indices) return
        clearPreview(); selectedIndex = index
        val token = previewToken.incrementAndGet(); previewBusy = true; previewError = null; receiver?.invoke()
        executor.execute {
            if (previewToken.get() != token) return@execute
            var asset: PhotoAsset? = null
            val error = try { asset = PhotoLoader(context).importPhoto(Uri.parse(current.items[index].input.uri)); null }
            catch (error: Exception) { BatchErrors.describe(error, BatchItemState.PROCESSING) }
            catch (_: OutOfMemoryError) { "预览内存不足，请选择较小照片" }
            main.post {
                if (token != previewToken.get() || busy || !foreground) {
                    asset?.let { stale -> stale.preview.recycle(); executor.execute { stale.file.delete() } }
                } else {
                    preview = asset; previewBusy = false; previewError = error; receiver?.invoke()
                }
            }
        }
    }
    private fun clearPreview() {
        previewToken.incrementAndGet(); previewBusy = false; previewError = null
        val old = preview; preview = null
        // The main thread first detaches the bitmap from the View, then the worker recycles/deletes it.
        receiver?.invoke()
        old?.let { executor.execute { BatchProcessor.releaseImport(it) } }
    }
    private fun persist(next: BatchJob) { store.save(next); job = next; emit() }
    private fun command(action: () -> Unit) {
        if (busy) return
        busy = true; receiver?.invoke()
        executor.execute {
            try { action() }
            catch (_: BatchPersistenceException) { failStorage() }
            catch (error: Exception) { main.post {
                message = when (error) {
                    is IllegalArgumentException, is IllegalStateException, is java.io.IOException ->
                        error.message?.take(180) ?: "操作失败，原记录已保留"
                    else -> "操作失败，请检查文件或目录权限与剩余空间后重试"
                }
            } }
            finally { main.post { busy = false; receiver?.invoke() } }
        }
    }
    private fun failStorage() {
        stop = 1
        main.post {
            blocked = true
            message = "批量任务记录保存失败，已停止安排下一张。原记录和已保存图片保留；请检查空间后重新读取"
            receiver?.invoke()
        }
    }
}

internal object BatchSessions {
    private var current: BatchSession? = null
    fun acquire(context: Context): BatchSession = current ?: BatchSession(context.applicationContext).also { current = it }
}
