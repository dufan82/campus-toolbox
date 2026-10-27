package cn.campuslife.assistant.update

import android.content.SharedPreferences
import android.content.Context
import android.os.Build
import cn.campuslife.assistant.BuildConfig
import java.io.IOException
import java.util.concurrent.Executors

data class UpdateCheck(val update: UpdatePackage? = null, val message: String = "", val offer: Boolean = false)
class UpdateRepository(private val prefs: SharedPreferences, private val client: GitHubReleaseClient = GitHubReleaseClient(),
    private val cnb: ReleaseClient? = BuildConfig.CNB_REPOSITORY.takeIf(UpdateRules::cnbRepository)?.let { CnbReleaseClient(it) },
    private val now: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("updates-v1", Context.MODE_PRIVATE))

    /** Run on the update worker, never the main thread. */
    fun check(manual: Boolean): UpdateCheck = synchronized(lock) {
        val time = now()
        val cached = cached()
        val cnbEnabled = UpdateRules.cnbRepository(BuildConfig.CNB_REPOSITORY)
        if (!UpdateRules.shouldCheck(time, prefs.getLong("attempt", 0), if (cnbEnabled) 0 else prefs.getLong("retry", 0), manual)) {
            return@synchronized describe(cached, manual, prefs.getString("message", "稍后可重新检查") ?: "稍后可重新检查")
        }
        save(prefs.edit().putLong("attempt", time))
        try {
            val result = PreferredReleaseClient(client,cnb).fetch(::sourceCache, time) { source, error ->
                val retry = (error as? UpdateNetworkException)?.retryAt?.takeIf { it > time } ?: 0
                save(prefs.edit().putLong("${cacheKey(source)}-retry", retry))
            }
            val response = result.response
            val info = response.update
            val kind = if (response.missing) "missing" else "release"
            val message = if (kind == "missing") "暂未发布正式版本" else when {
                info == null -> "版本信息暂不可用"
                info.versionCode <= BuildConfig.VERSION_CODE -> "当前已是最新版本"
                info.minSdk > Build.VERSION.SDK_INT -> "新版本暂不支持当前Android系统"
                else -> "发现新版本"
            }
            val edit = prefs.edit().putLong("retry", 0).putString("kind", kind).putString("message", message)
                .putString("source", result.source.name).putString("${cacheKey(result.source)}-kind", kind)
                .putLong("${cacheKey(result.source)}-retry", 0)
                .putString("${cacheKey(result.source)}-etag", response.etag)
            if (result.source == UpdateSource.GITHUB) edit.putString("etag", response.etag) else edit.remove("etag")
            if (info == null) {
                edit.remove("info").remove("${cacheKey(result.source)}-info")
            } else {
                val encoded = UpdateJson.encode(info).toString()
                edit.putString("info", encoded).putString("${cacheKey(result.source)}-info", encoded)
            }
            save(edit)
            describe(info, manual, message)
        } catch (error: Exception) {
            val retry = (error as? UpdateNetworkException)?.retryAt?.takeIf { it > time } ?: 0
            save(prefs.edit().putLong("retry", retry).putString("message", "检查失败，请稍后重试；其他功能可正常使用"))
            describe(cached, manual, "检查失败，请稍后重试；其他功能可正常使用")
        }
    }
    fun cached(): UpdatePackage? = runCatching { prefs.getString("info", null)?.let(UpdateJson::cached) }.getOrNull()
    private fun cacheKey(source: UpdateSource) = if (source == UpdateSource.CNB) "cnb:${BuildConfig.CNB_REPOSITORY}" else "github"
    private fun sourceCache(source: UpdateSource): ReleaseCache {
        val key = cacheKey(source)
        val legacy = source == UpdateSource.GITHUB && prefs.getString("source", UpdateSource.GITHUB.name) == UpdateSource.GITHUB.name
        val info = runCatching { prefs.getString("$key-info", null)?.let(UpdateJson::cached) }.getOrNull()
            ?.takeIf { it.source == source } ?: cached()?.takeIf { it.source == source }
        val kind = prefs.getString("$key-kind", if (legacy) prefs.getString("kind", null) else null)
        val etag = prefs.getString("$key-etag", if (legacy) prefs.getString("etag", null) else null)
        return ReleaseCache(info, kind == "missing", etag, prefs.getLong("$key-retry", 0))
    }
    fun ignored(info: UpdatePackage) = prefs.getLong("ignored", 0) == info.versionCode
    fun ignore(info: UpdatePackage) = synchronized(lock) { save(prefs.edit().putLong("ignored", info.versionCode)) }
    private fun describe(info: UpdatePackage?, manual: Boolean, message: String): UpdateCheck {
        val available = info?.takeIf { UpdateRules.available(it, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT) }
        return UpdateCheck(available, message, available != null && (manual || !ignored(available)))
    }
    private fun save(edit: SharedPreferences.Editor) { if (!edit.commit()) throw IOException("更新设置保存失败，请稍后重试") }
    companion object { private val lock = Any() }
}

internal object UpdateRuntime {
    val worker = Executors.newSingleThreadExecutor { Thread(it, "app-updates").apply { isDaemon = true } }
}
