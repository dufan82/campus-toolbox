package cn.campuslife.assistant.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ReleaseProtocolTest {
    private val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private fun metadata() = JSONObject().put("schemaVersion", 1).put("packageName", "cn.campuslife.assistant")
        .put("channel", "stable").put("versionCode", 18).put("versionName", "1.7.1").put("tag", "v1.7.1")
        .put("minSdk", 26).put("assetName", "campus-toolbox-1.7.1.apk").put("size", 3).put("sha256", hash)
    private fun release(draft: Boolean = false) = JSONObject("""{"id":123,"tag_name":"v1.7.1","draft":$draft,"prerelease":false,"body":"合成更新说明","assets":[{"id":1,"name":"update.json","state":"uploaded","size":300,"browser_download_url":"https://github.com/dufan82/campus-toolbox/releases/download/v1.7.1/update.json"},{"id":2,"name":"campus-toolbox-1.7.1.apk","state":"uploaded","size":3,"digest":"sha256:$hash","browser_download_url":"https://github.com/dufan82/campus-toolbox/releases/download/v1.7.1/campus-toolbox-1.7.1.apk"}]}""")
    private class Connection(url: URL, val text: String, val code: Int = 200, val headers: Map<String, String> = emptyMap()) : HttpURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { closed = true }
        override fun getResponseCode() = code
        override fun getInputStream() = text.byteInputStream()
        override fun getHeaderField(name: String) = headers[name]
    }

    @Test fun latestAndMetadataRequestsAreAnonymousAndBindApkToSameStableRelease() {
        val calls = mutableListOf<Connection>()
        val client = GitHubReleaseClient { url -> Connection(url, if (url.host == "api.github.com") release().toString() else metadata().toString(), headers = mapOf("ETag" to "release-123")).also { calls.add(it) } }
        val result = client.fetch(null)
        assertEquals(18L, result.update!!.versionCode)
        assertEquals("合成更新说明", result.update.notes)
        assertEquals(listOf("/repos/dufan82/campus-toolbox/releases/latest", "/dufan82/campus-toolbox/releases/download/v1.7.1/update.json"), calls.map { it.url.path })
        assertEquals("release-123", result.etag)
        calls.forEach { assertNull(it.getRequestProperty("Authorization")); assertFalse(it.instanceFollowRedirects); assertTrue(it.closed) }
    }

    @Test fun draftPreReleaseAndMismatchedMetadataCannotProduceUpdate() {
        for (bad in listOf(release(true), release().put("prerelease", true))) {
            var calls = 0
            val client = GitHubReleaseClient { url -> calls++; Connection(url, bad.toString()) }
            assertThrows(Exception::class.java) { client.fetch(null) }; assertEquals(1, calls)
        }
        for (bad in listOf(metadata().put("versionCode", "18"), metadata().put("packageName", "other"), metadata().put("sha256", "0".repeat(64)), metadata().put("tag", "v9.9.9"))) {
            val client = GitHubReleaseClient { url -> Connection(url, if (url.host == "api.github.com") release().toString() else bad.toString()) }
            assertThrows(Exception::class.java) { client.fetch(null) }
        }
    }

    @Test fun noReleaseNotModifiedAndRateLimitHaveDifferentOutcomes() {
        assertTrue(GitHubReleaseClient { Connection(it, "", 404) }.fetch(null).missing)
        val calls = mutableListOf<Connection>()
        val cached = GitHubReleaseClient { Connection(it, "", 304).also(calls::add) }.fetch("etag-1")
        assertTrue(cached.notModified); assertEquals("etag-1", calls.single().getRequestProperty("If-None-Match"))
        val error = assertThrows(UpdateNetworkException::class.java) { GitHubReleaseClient { Connection(it, "", 429, mapOf("Retry-After" to "120")) }.fetch(null) }
        assertTrue(error.retryAt > System.currentTimeMillis() + 110_000)
    }

    @Test fun metadataRedirectCannotLeaveOfficialHttpsHostsAndBodyIsBounded() {
        var count = 0
        val client = GitHubReleaseClient { url ->
            count++
            if (count == 1) Connection(url, release().toString()) else Connection(url, "", 302, mapOf("Location" to "http://evil.test/update.json"))
        }
        assertThrows(Exception::class.java) { client.fetch(null) }; assertEquals(2, count)
        assertThrows(Exception::class.java) { GitHubReleaseClient { Connection(it, "x".repeat(600_000)) }.fetch(null) }
    }
}
