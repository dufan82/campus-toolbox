package cn.campuslife.assistant.update

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.InputStream
import java.io.IOException
import android.app.DownloadManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import java.util.UUID
import cn.campuslife.assistant.BuildConfig

enum class UpdatePhase { NONE, DOWNLOADING, PAUSED, DOWNLOADED, READY, FAILED }
data class DownloadStatus(val phase: UpdatePhase, val downloaded: Long = 0, val total: Long = 0, val message: String = "", val source: String = "")
data class UpdateDownloadState(val info: UpdatePackage? = null, val phase: UpdatePhase = UpdatePhase.NONE, val downloaded: Long = 0,
    val total: Long = 0, val message: String = "", val file: File? = null)
interface UpdateDownloadBackend {
    fun enqueue(info: UpdatePackage): Long
    fun query(id: Long): DownloadStatus
    fun open(id: Long): InputStream
    fun cancel(id: Long)
}
class UpdateDownloads(context: Context, private val backend: UpdateDownloadBackend = SystemUpdateDownloads(context),
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("update-download-v1", Context.MODE_PRIVATE)) {
    private val context = context.applicationContext
    private val directory get() = File(context.filesDir, "updates")
    private fun info(): UpdatePackage? = prefs.getString("info", null)?.let(UpdateJson::cached)
    private fun file(info: UpdatePackage) = File(directory, "update_${info.versionCode}_${info.sha256}.apk").also {
        require(it.canonicalFile.parentFile == directory.canonicalFile) { "安装包路径无效" }
    }
    private fun partial(info: UpdatePackage) = File(directory, "update_${info.versionCode}_${info.sha256}.part").also {
        require(it.canonicalFile.parentFile == directory.canonicalFile) { "临时安装包路径无效" }
    }
    fun state(): UpdateDownloadState = synchronized(lock) {
        val info = info() ?: return@synchronized UpdateDownloadState()
        if (info.versionCode <= BuildConfig.VERSION_CODE) {
            cancel()
            save(prefs.edit().remove("info"))
            return@synchronized UpdateDownloadState()
        }
        if (prefs.getBoolean("ready", false)) {
            val file = file(info)
            return@synchronized if (file.isFile) UpdateDownloadState(info, UpdatePhase.READY, info.size, info.size, "安装包已校验，点击安装", file)
                else UpdateDownloadState(info, UpdatePhase.FAILED, message = "安装文件已不存在，请重新下载")
        }
        val id = prefs.getLong("id", 0)
        if (id <= 0) return@synchronized UpdateDownloadState(info, if (prefs.getString("error", "").isNullOrEmpty()) UpdatePhase.NONE else UpdatePhase.FAILED, message = prefs.getString("error", "") ?: "")
        val status = backend.query(id)
        if (status.phase != UpdatePhase.FAILED && status.source != info.url) throw IOException("下载任务与发布信息不一致")
        UpdateDownloadState(info, status.phase, status.downloaded.coerceAtLeast(0), info.size, status.message)
    }
    fun start(info: UpdatePackage): Unit = synchronized(lock) {
        require(UpdateRules.valid(info) && info.versionCode > BuildConfig.VERSION_CODE && UpdateRules.assetUrl(info.url, info.tag, info.assetName)) { "更新信息无效或版本已安装" }
        val current = state()
        if (current.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.PAUSED, UpdatePhase.DOWNLOADED, UpdatePhase.READY)) {
            require(current.info == info) { "已有更新任务，请先取消后再下载其他版本" }
            return@synchronized
        }
        val previous = prefs.getLong("id", 0)
        if (previous > 0) {
            val previousInfo = current.info ?: throw IOException("下载记录损坏")
            val status = backend.query(previous)
            if (status.source.isNotEmpty() && status.source != previousInfo.url) throw IOException("下载记录不一致")
            backend.cancel(previous)
        }
        val id = backend.enqueue(info)
        try { save(prefs.edit().putString("info", UpdateJson.encode(info).toString()).putLong("id", id).putBoolean("ready", false).remove("error")) }
        catch (error: Exception) { runCatching { backend.cancel(id) }; throw error }
    }
    fun cancel(): Unit = synchronized(lock) {
        val info = info() ?: return@synchronized
        val id = prefs.getLong("id", 0)
        if (id > 0) {
            val status = backend.query(id)
            if (status.source.isNotEmpty() && status.source != info.url) throw IOException("下载任务不一致，未删除文件")
            backend.cancel(id)
        }
        listOf(file(info), partial(info)).forEach { owned ->
            if (owned.exists() && !owned.delete()) throw IOException("安装包暂时无法移除")
        }
        save(prefs.edit().putLong("id", 0).putBoolean("ready", false).remove("error"))
    }
    fun verifyDownloaded(): File = synchronized(lock) {
        val info = info() ?: throw IOException("没有更新任务")
        val current = state()
        if (current.phase == UpdatePhase.READY) {
            try { return@synchronized requireNotNull(current.file).also { ApkVerifier(context).verify(it, info) } }
            catch (error: Exception) {
                save(prefs.edit().putBoolean("ready", false).putString("error", "安装包未通过复查，请重新下载"))
                throw error
            }
        }
        require(current.phase == UpdatePhase.DOWNLOADED) { "安装包尚未下载完成" }
        val id = prefs.getLong("id", 0)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建更新目录")
        val partial = partial(info)
        if (StatFs(directory.absolutePath).availableBytes + partial.length() < info.size + 16L * 1024 * 1024) throw IOException("空间不足，请释放空间后重试校验")
        try {
            backend.open(id).use { input -> partial.outputStream().use { output ->
                UpdateRules.copyVerified(input, output, info); output.flush(); output.fd.sync()
            } }
            ApkVerifier(context).verify(partial, info)
            val target = file(info)
            if (target.exists() && !target.delete()) throw IOException("旧安装文件暂时被占用")
            if (!partial.renameTo(target)) throw IOException("安装文件保存失败")
            save(prefs.edit().putBoolean("ready", true).remove("error"))
            runCatching { backend.cancel(id); save(prefs.edit().putLong("id", 0)) }
            target
        } catch (error: Exception) {
            runCatching { backend.cancel(id) }
            save(prefs.edit().putLong("id", 0).putBoolean("ready", false).putString("error", "安装包校验失败或保存失败，请重新下载"))
            throw error
        } finally { if (partial.exists()) partial.delete() }
    }
    private fun save(edit: SharedPreferences.Editor) { if (!edit.commit()) throw IOException("更新任务保存失败，请稍后重试") }
    companion object { private val lock = Any() }
}

/** DownloadManager owns the transfer. There is no app background polling service. */
private class SystemUpdateDownloads(context: Context) : UpdateDownloadBackend {
    private val context = context.applicationContext
    private val manager get() = context.getSystemService(DownloadManager::class.java) ?: throw IOException("系统下载服务不可用")
    override fun enqueue(info: UpdatePackage): Long {
        require(UpdateRules.assetUrl(info.url, info.tag, info.assetName))
        val folder = context.getExternalFilesDir("updates") ?: throw IOException("下载目录不可用")
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("无法创建下载目录")
        if (StatFs(folder.absolutePath).availableBytes < info.size + 16L * 1024 * 1024) throw IOException("下载空间不足")
        val request = DownloadManager.Request(Uri.parse(info.url)).setTitle("校园工具箱更新")
            .setDescription(info.versionName).setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "updates", "${UUID.randomUUID()}.apk")
        return manager.enqueue(request)
    }
    override fun query(id: Long): DownloadStatus {
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return DownloadStatus(UpdatePhase.FAILED, message = "下载记录已失效，请重试")
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val phase = when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> UpdatePhase.DOWNLOADED
                DownloadManager.STATUS_PAUSED -> UpdatePhase.PAUSED
                DownloadManager.STATUS_FAILED -> UpdatePhase.FAILED
                else -> UpdatePhase.DOWNLOADING
            }
            val message = when (phase) { UpdatePhase.PAUSED -> "下载已暂停，等待网络或系统恢复"; UpdatePhase.FAILED -> "下载失败，请检查网络和剩余空间后重试"; else -> "" }
            return DownloadStatus(phase, cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), message,
                cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)))
        }
    }
    override fun open(id: Long): InputStream = ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(id))
    override fun cancel(id: Long) { manager.remove(id) }
}
