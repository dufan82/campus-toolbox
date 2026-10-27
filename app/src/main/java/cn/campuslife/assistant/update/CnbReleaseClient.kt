package cn.campuslife.assistant.update

import cn.campuslife.assistant.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

class CnbReleaseClient internal constructor(private val repository: String = BuildConfig.CNB_REPOSITORY,
    open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) : ReleaseClient {
    init { require(UpdateRules.cnbRepository(repository)) { "尚未配置国内更新仓库" } }
    private val latest = "https://cnb.cool/$repository/-/releases/latest"
    private val http = ReleaseHttp(UpdateSource.CNB, latest, { url ->
        val path = runCatching { URL(url).path.substringAfter("/-/releases/download/") }.getOrDefault("")
        UpdateRules.cnbAssetUrl(url, repository, path.substringBefore('/'), path.substringAfter('/', ""))
    }, open)

    override fun fetch(etag: String?): ReleaseResponse {
        val result = http.read(latest, etag, 512 * 1024)
        if (result.code == 404) return ReleaseResponse(missing = true)
        if (result.code == 304) return ReleaseResponse(notModified = true, etag = etag)
        val release = JSONObject(result.body)
        require(release.get("draft") == false && release.get("prerelease") == false && release.get("is_latest") == true) { "不是正式稳定版本" }
        val idText = release.get("id")
        require(idText is String && idText.matches(Regex("[1-9][0-9]{0,18}"))) { "发布编号无效" }
        val id = requireNotNull(idText.toLongOrNull()) { "发布编号无效" }
        val tag = release.getString("tag_name")
        require(tag.matches(Regex("v[A-Za-z0-9][A-Za-z0-9._-]{0,39}"))) { "发布标记不支持" }
        val assets = release.getJSONArray("assets")
        require(assets.length() in 1..100) { "发布附件不完整" }
        fun asset(name: String): JSONObject {
            val found = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == name }
            require(found.size == 1) { "发布附件缺失或重复" }
            return found.single().also {
                require(UpdateRules.cnbAssetUrl(it.getString("browser_download_url"), repository, tag, name)) { "附件地址不是本仓库发布文件" }
                require(it.getString("hash_algo") == "sha256" && it.getString("hash_value").matches(Regex("[a-f0-9]{64}"))) { "附件缺少SHA256校验" }
            }
        }
        val manifest = asset("update.json")
        val manifestSize = UpdateJson.integer(manifest, "size")
        require(manifestSize in 1..65536) { "版本文件过大" }
        val metadata = http.read(manifest.getString("browser_download_url"), null, 65536)
        require(metadata.bytes.size.toLong() == manifestSize &&
            UpdateRules.digest(MessageDigest.getInstance("SHA-256").digest(metadata.bytes)) == manifest.getString("hash_value")) { "版本文件大小或校验不一致" }
        val info = UpdateJson.decode(JSONObject(metadata.body), id, release.optString("body").take(6000), UpdateSource.CNB, repository)
        require(info.tag == tag) { "版本文件与发布标记不一致" }
        val apk = asset(info.assetName)
        require(UpdateJson.integer(apk, "size") == info.size && apk.getString("hash_value") == info.sha256) { "附件大小或校验与版本文件不一致" }
        return ReleaseResponse(info, result.etag)
    }

    internal fun resolveApk(info: UpdatePackage): String {
        require(info.source == UpdateSource.CNB && info.repository == repository && UpdateRules.valid(info)) { "更新来源不一致" }
        return http.resolveApk(info.url, info.size)
    }
}
