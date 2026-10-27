package cn.campuslife.assistant.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class ReleaseResponse(val update: UpdatePackage? = null, val etag: String? = null, val missing: Boolean = false, val notModified: Boolean = false)
class UpdateNetworkException(message: String, val retryAt: Long = 0) : IOException(message)
class GitHubReleaseClient(open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) : ReleaseClient {
    private val http = ReleaseHttp(UpdateSource.GITHUB, LATEST, { url ->
        val path = runCatching { URL(url).path.substringAfter("/releases/download/") }.getOrDefault("")
        UpdateRules.assetUrl(url, path.substringBefore('/'), path.substringAfter('/', ""))
    }, open)
    override fun fetch(etag: String?): ReleaseResponse {
        val result = http.read(LATEST, etag, 512 * 1024)
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
        val body = http.read(manifest.getString("browser_download_url"), null, 65536)
        if (body.code != 200) throw IOException("版本文件暂不可用")
        val info = UpdateJson.decode(JSONObject(body.body), id, release.optString("body").take(6000))
        require(info.tag == tag) { "版本文件与发布标记不一致" }
        val apk = asset(info.assetName)
        require(UpdateJson.integer(apk, "size") == info.size) { "附件大小与版本文件不一致" }
        if (!apk.isNull("digest")) require(apk.getString("digest") == "sha256:${info.sha256}") { "附件校验信息不一致" }
        return ReleaseResponse(info, result.etag)
    }

    companion object { const val LATEST = "https://api.github.com/repos/dufan82/campus-toolbox/releases/latest" }
}

internal object UpdateJson {
    fun integer(json: JSONObject, key: String): Long {
        val value = json.get(key)
        require(value is Int || value is Long) { "版本字段必须是整数" }
        return (value as Number).toLong()
    }
    fun decode(json: JSONObject, id: Long, notes: String, source: UpdateSource = UpdateSource.GITHUB, repository: String = ""): UpdatePackage {
        require(integer(json, "schemaVersion") == 1L && json.getString("packageName") == UpdateRules.PACKAGE && json.getString("channel") == "stable") { "版本文件不适用于此应用" }
        val sdk = integer(json, "minSdk"); require(sdk in 1..1000)
        return UpdatePackage(integer(json, "versionCode"), json.getString("versionName"), json.getString("tag"), sdk.toInt(),
            json.getString("assetName"), integer(json, "size"), json.getString("sha256"), notes, id, source, repository).also { require(UpdateRules.valid(it)) { "版本信息无效" } }
    }
    fun encode(info: UpdatePackage): JSONObject = JSONObject().put("schemaVersion", 1).put("packageName", UpdateRules.PACKAGE).put("channel", "stable")
        .put("versionCode", info.versionCode).put("versionName", info.versionName).put("tag", info.tag).put("minSdk", info.minSdk)
        .put("assetName", info.assetName).put("size", info.size).put("sha256", info.sha256).put("releaseId", info.releaseId).put("notes", info.notes)
        .put("source", info.source.name).put("repository", info.repository)
    fun cached(text: String): UpdatePackage = JSONObject(text).let {
        val source = if (it.has("source")) UpdateSource.valueOf(it.getString("source")) else UpdateSource.GITHUB
        decode(it, integer(it, "releaseId"), it.getString("notes"), source, it.optString("repository", "")).also { info ->
            require(UpdateRules.trusted(info)) { "更新缓存不属于已配置仓库" }
        }
    }
}
