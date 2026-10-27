package cn.campuslife.assistant.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Synthetic protocol fixtures, not a configured or published application repository. */
class CnbReleaseProtocolTest {
    private val repo = "fixture/project"
    private val base = "https://cnb.cool/$repo/-/releases/download/v1.7.2/"
    private val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private fun metadata() = JSONObject().put("schemaVersion", 1).put("packageName", UpdateRules.PACKAGE).put("channel", "stable")
        .put("versionCode", 19).put("versionName", "1.7.2").put("tag", "v1.7.2").put("minSdk", 26)
        .put("assetName", "campus-toolbox-1.7.2.apk").put("size", 3).put("sha256", hash)
    private fun sha(text: String) = UpdateRules.digest(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    private fun release(metadata: String): JSONObject {
        fun asset(name: String, size: Long, hash: String) = JSONObject().put("name", name).put("size", size)
            .put("browser_download_url", base + name).put("hash_algo", "sha256").put("hash_value", hash)
        return JSONObject().put("draft", false).put("prerelease", false).put("is_latest", true).put("id", "123")
            .put("tag_name", "v1.7.2").put("body", "合成说明").put("assets", org.json.JSONArray()
                .put(asset("update.json", metadata.toByteArray().size.toLong(), sha(metadata)))
                .put(asset("campus-toolbox-1.7.2.apk", 3, hash)))
    }
    private class Connection(url: URL, val text: String, val code: Int = 200) : HttpURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = text.byteInputStream()
        override fun getHeaderField(name: String) = if (name == "ETag") "cnb-123" else null
    }
    private fun fetch(release: JSONObject, metadata: String) = CnbReleaseClient(repo) { url ->
        Connection(url, if (url.path.endsWith("/latest")) release.toString() else metadata)
    }.fetch(null)

    @Test fun publicWebsiteProtocolAcceptsNumericStringIdAndCnbHashesWithoutGithubFields() {
        val metadata = metadata().toString()
        val calls = mutableListOf<Connection>()
        val result = CnbReleaseClient(repo) { url -> Connection(url, if (url.path.endsWith("/latest")) release(metadata).toString() else metadata).also(calls::add) }.fetch(null)
        assertEquals(UpdateSource.CNB, result.update!!.source)
        assertEquals(repo, result.update.repository)
        assertEquals(123L, result.update.releaseId)
        assertEquals(19L, result.update.versionCode)
        assertEquals(base + "campus-toolbox-1.7.2.apk", result.update.url)
        assertEquals("cnb-123", result.etag)
        assertEquals(listOf("/$repo/-/releases/latest", "/$repo/-/releases/download/v1.7.2/update.json"), calls.map { it.url.path })
        calls.forEach { assertEquals("cnb.cool", it.url.host); assertNull(it.getRequestProperty("Authorization")); assertTrue(it.closed) }
    }

    @Test fun draftPrereleaseNonLatestAndNonStringIdAreRejected() {
        val metadata = metadata().toString()
        for (bad in listOf(release(metadata).put("draft", true), release(metadata).put("prerelease", true),
            release(metadata).put("is_latest", false), release(metadata).put("id", 123), release(metadata).put("id", "9223372036854775808")))
            assertThrows(Exception::class.java) { fetch(bad, metadata) }
    }

    @Test fun metadataMustBelongToSameStableReleaseAndUseAnIntegerVersionCode() {
        for (bad in listOf(metadata().put("versionCode", "19"), metadata().put("versionCode", 19.1), metadata().put("channel", "beta"),
            metadata().put("packageName", "other.app"), metadata().put("tag", "v1.7.3"), metadata().put("schemaVersion", 2))) {
            val text = bad.toString()
            assertThrows(Exception::class.java) { fetch(release(text), text) }
        }
    }

    @Test fun missingDuplicatedOrUntrustedAttachmentsCannotBecomeUpdates() {
        val metadata = metadata().toString()
        val changed = listOf<(JSONObject) -> Unit>(
            { it.getJSONArray("assets").getJSONObject(1).put("hash_value", "0".repeat(64)) },
            { it.getJSONArray("assets").getJSONObject(1).put("size", 4) },
            { it.getJSONArray("assets").getJSONObject(1).put("hash_algo", "md5") },
            { it.getJSONArray("assets").getJSONObject(1).remove("hash_value") },
            { it.getJSONArray("assets").getJSONObject(1).put("browser_download_url", base.replace("fixture", "other") + "campus-toolbox-1.7.2.apk") },
            { it.getJSONArray("assets").getJSONObject(0).put("browser_download_url", base + "update.json?signature=remote") },
            { it.getJSONArray("assets").put(it.getJSONArray("assets").getJSONObject(1)) },
            { it.getJSONArray("assets").remove(0) }
        )
        for (change in changed) assertThrows(Exception::class.java) { fetch(release(metadata).also(change), metadata) }
    }

    @Test fun metadataBytesMustMatchBothDeclaredSizeAndSha256() {
        val metadata = metadata().toString()
        for (bad in listOf(release(metadata).also { it.getJSONArray("assets").getJSONObject(0).put("size", 1) },
            release(metadata).also { it.getJSONArray("assets").getJSONObject(0).put("hash_value", "0".repeat(64)) }))
            assertThrows(Exception::class.java) { fetch(bad, metadata) }
        assertThrows(Exception::class.java) { fetch(release(metadata), metadata + " ") }
    }

    @Test fun remoteRepositorySourceAndSignerCannotChangeLocalTrust() {
        val metadata = metadata().put("source", "GITHUB").put("repository", "attacker/project").put("signerSha256", "remote-certificate").toString()
        val info = requireNotNull(fetch(release(metadata), metadata).update)
        assertEquals(UpdateSource.CNB, info.source)
        assertEquals(repo, info.repository)
        val identity = ApkIdentity(UpdateRules.PACKAGE, 19, 26, setOf("remote-certificate"), false)
        assertFalse(UpdateRules.compatible(info, identity, 18, 35, setOf("installed-certificate")))
    }

    @Test fun legacyGithubCacheDefaultsToGithubAndUnconfiguredCnbCacheIsRejected() {
        val info = UpdatePackage(19, "1.7.2", "v1.7.2", 26, "app.apk", 3, hash, "fixture", 123)
        val legacy = UpdateJson.encode(info).apply { remove("source"); remove("repository") }
        assertEquals(info, UpdateJson.cached(legacy.toString()))
        val other = info.copy(source = UpdateSource.CNB, repository = "unconfigured/project")
        val encoded = UpdateJson.encode(other)
        assertEquals("CNB", encoded.getString("source")); assertEquals("unconfigured/project", encoded.getString("repository"))
        assertThrows(Exception::class.java) { UpdateJson.cached(encoded.toString()) }
    }
}
