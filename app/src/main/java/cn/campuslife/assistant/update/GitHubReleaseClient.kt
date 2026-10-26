package cn.campuslife.assistant.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.io.ByteArrayOutputStream
import org.json.JSONObject

data class ReleaseResponse(val update: UpdatePackage? = null, val etag: String? = null, val missing: Boolean = false, val notModified: Boolean = false)
class UpdateNetworkException(message: String, val retryAt: Long = 0) : IOException(message)
class GitHubReleaseClient(private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun fetch(etag: String?): ReleaseResponse {
        val result = read(LATEST, etag, 512 * 1024, false)
        if (result.code == 404) return ReleaseResponse(missing = true)
        if (result.code == 304) return ReleaseResponse(notModified = true, etag = etag)
        val release = JSONObject(result.body)
        require(release.get("draft") == false && release.get("prerelease") == false) { "不是正式稳定版本" }
        val tag = release.getString("tag_name")
        require(tag.matches(Regex("v[A-Za-z0-9][A-Za-z0-9._-]{0,39}"))) { "发布标记不支持" }
        val id = UpdateJson.integer(release, "id")
        val assets = release.getJSONArray("assets")
        require(assets.length() in 1..100) { "发布附件不完整" }
        fun asset(name: String): JSONObject {
            val found = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == name }
            require(found.size == 1 && found.single().optString("state") == "uploaded") { "发布附件缺失或重复" }
            return found.single().also { require(UpdateRules.assetUrl(it.getString("browser_download_url"), tag, name)) { "附件地址不是本仓库发布文件" } }
        }
        val manifest = asset("update.json")
        require(UpdateJson.integer(manifest, "size") in 1..65536) { "版本文件过大" }
        val body = read(manifest.getString("browser_download_url"), null, 65536, true)
        if (body.code != 200) throw IOException("版本文件暂不可用")
        val info = UpdateJson.decode(JSONObject(body.body), id, release.optString("body").take(6000))
        require(info.tag == tag) { "版本文件与发布标记不一致" }
        val apk = asset(info.assetName)
        require(UpdateJson.integer(apk, "size") == info.size) { "附件大小与版本文件不一致" }
        if (!apk.isNull("digest")) require(apk.getString("digest") == "sha256:${info.sha256}") { "附件校验信息不一致" }
        return ReleaseResponse(info, result.etag)
    }

    private data class Response(val code: Int, val body: String = "", val etag: String? = null)
    private fun read(initial: String, etag: String?, limit: Int, redirects: Boolean): Response {
        var current = initial
        repeat(5) { hop ->
            val connection = open(URL(current))
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000; connection.readTimeout = 15000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "CampusToolbox-Android")
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (current == LATEST) {
                    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    etag?.takeIf { it.length <= 200 && it.none(Char::isISOControl) }?.let { connection.setRequestProperty("If-None-Match", it) }
                }
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(redirects && hop < 4) { "更新地址发生异常跳转" }
                    val next = URL(URL(current), connection.getHeaderField("Location") ?: throw IOException("更新地址无效")).toString()
                    val uri = URI(next)
                    require(uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) && uri.fragment == null &&
                        (uri.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com") || next == initial)) { "更新地址发生站外跳转" }
                    current = next
                } else {
                    if (current == LATEST && status in listOf(304, 404)) return Response(status)
                    if (status == 403 || status == 429) {
                        val now = System.currentTimeMillis()
                        val seconds = connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(60, 86400)
                        val reset = connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
                        val retry = maxOf(now + (seconds ?: 3600) * 1000, reset?.coerceAtMost(now + UpdateRules.INTERVAL) ?: 0)
                        throw UpdateNetworkException("GitHub暂时限流，请稍后再试", retry)
                    }
                    if (status != 200) throw UpdateNetworkException("暂时无法检查更新，请稍后再试")
                    if (connection.contentLengthLong > limit) throw IOException("更新信息过大")
                    val bytes = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            if (Thread.currentThread().isInterrupted || bytes.size() + count > limit) throw IOException("更新读取已停止")
                            bytes.write(buffer, 0, count)
                        }
                    }
                    return Response(status, bytes.toString("UTF-8"), connection.getHeaderField("ETag")?.take(200))
                }
            } finally { connection.disconnect() }
        }
        throw IOException("更新地址跳转过多")
    }
    companion object { const val LATEST = "https://api.github.com/repos/dufan82/campus-toolbox/releases/latest" }
}

internal object UpdateJson {
    fun integer(json: JSONObject, key: String): Long {
        val value = json.get(key)
        require(value is Int || value is Long) { "版本字段必须是整数" }
        return (value as Number).toLong()
    }
    fun decode(json: JSONObject, id: Long, notes: String): UpdatePackage {
        require(integer(json, "schemaVersion") == 1L && json.getString("packageName") == UpdateRules.PACKAGE && json.getString("channel") == "stable") { "版本文件不适用于此应用" }
        val sdk = integer(json, "minSdk"); require(sdk in 1..1000)
        return UpdatePackage(integer(json, "versionCode"), json.getString("versionName"), json.getString("tag"), sdk.toInt(),
            json.getString("assetName"), integer(json, "size"), json.getString("sha256"), notes, id).also { require(UpdateRules.valid(it)) { "版本信息无效" } }
    }
    fun encode(info: UpdatePackage): JSONObject = JSONObject().put("schemaVersion", 1).put("packageName", UpdateRules.PACKAGE).put("channel", "stable")
        .put("versionCode", info.versionCode).put("versionName", info.versionName).put("tag", info.tag).put("minSdk", info.minSdk)
        .put("assetName", info.assetName).put("size", info.size).put("sha256", info.sha256).put("releaseId", info.releaseId).put("notes", info.notes)
    fun cached(text: String): UpdatePackage = JSONObject(text).let { decode(it, integer(it, "releaseId"), it.getString("notes")) }
}
