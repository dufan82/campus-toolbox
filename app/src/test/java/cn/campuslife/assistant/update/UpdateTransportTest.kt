package cn.campuslife.assistant.update

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException

class UpdateTransportTest {
    private val latest = "https://cnb.cool/fixture/project/-/releases/latest"
    private val asset = "https://cnb.cool/fixture/project/-/releases/download/v1.7.2/update.json"
    private val cdn = "https://asset.cnb.cool/fixture/file?signature=dynamic"
    private class Connection(url: URL, val code: Int = 200, val text: String = "abc", val headers: Map<String, String> = emptyMap(), val length: Long = -1) : HttpURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String) = headers[name]
        override fun getContentLengthLong() = length
        override fun getInputStream() = text.byteInputStream()
    }
    private fun http(open: (URL) -> HttpURLConnection) = ReleaseHttp(UpdateSource.CNB, latest, { it == asset }, open)
    @Test fun unsolicitedNotModifiedCannotBeUsedAsARelease() {
        val client = GitHubReleaseClient { url -> object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = 304
        } }
        assertThrows(Exception::class.java) { client.fetch(null) }
    }

    @Test fun anonymousLatestRequestUsesOnlyItsOwnValidatorAndBoundedTimeouts() {
        val calls = mutableListOf<Connection>()
        val result = http { Connection(it, 304).also(calls::add) }.read(latest, "cnb-1", 100)
        assertEquals(304, result.code)
        val call = calls.single()
        assertEquals("cnb-1", call.getRequestProperty("If-None-Match"))
        assertNull(call.getRequestProperty("Authorization"))
        assertNull(call.getRequestProperty("X-GitHub-Api-Version"))
        assertFalse(call.instanceFollowRedirects)
        assertEquals(10000, call.connectTimeout); assertEquals(15000, call.readTimeout)
        assertTrue(call.closed)
    }

    @Test fun latest404MeansMissingAndLatestRedirectIsRejectedBeforeContactingTarget() {
        assertEquals(404, http { Connection(it, 404) }.read(latest, null, 100).code)
        var calls = 0
        assertThrows(Exception::class.java) { http { calls++; Connection(it, 302, headers = mapOf("Location" to cdn)) }.read(latest, null, 100) }
        assertEquals(1, calls)
    }

    @Test fun officialAssetRedirectAllowsDynamicQueryWithoutForwardingEtag() {
        val calls = mutableListOf<Connection>()
        val result = http { url -> (if (url.toString() == asset) Connection(url, 302, headers = mapOf("Location" to cdn)) else Connection(url)).also(calls::add) }.read(asset, "latest-only", 100)
        assertEquals("abc", result.body)
        assertEquals(listOf(asset, cdn), calls.map { it.url.toString() })
        calls.forEach { assertNull(it.getRequestProperty("If-None-Match")); assertTrue(it.closed) }
    }

    @Test fun unsafeRedirectIsNeverOpened() {
        for (target in listOf("http://asset.cnb.cool/file", "https://asset.cnb.cool.evil.test/file", "https://other.test/file",
            "https://user@asset.cnb.cool/file", "https://asset.cnb.cool:8443/file", "https://asset.cnb.cool/file#fragment", latest + "?x=1")) {
            var calls = 0
            assertThrows(Exception::class.java) { http { calls++; Connection(it, 302, headers = mapOf("Location" to target)) }.read(asset, null, 100) }
            assertEquals(target, 1, calls)
        }
    }

    @Test fun redirectLoopStopsAfterFourHopsAndClosesEveryConnection() {
        val calls = mutableListOf<Connection>()
        assertThrows(Exception::class.java) { http { Connection(it, 302, headers = mapOf("Location" to cdn)).also(calls::add) }.read(asset, null, 100) }
        assertEquals(5, calls.size)
        assertTrue(calls.all { it.closed })
    }

    @Test fun initialCdnOrOtherRepositoryUrlIsRejectedBeforeOpeningConnection() {
        for (url in listOf(cdn, asset.replace("fixture", "other"), "$asset?token=x", asset.replace("https:", "http:"))) {
            var calls = 0
            assertThrows(Exception::class.java) { http { calls++; Connection(it) }.read(url, null, 100) }
            assertEquals(0, calls)
        }
    }

    @Test fun advertisedAndStreamedBodiesHaveIndependentSizeLimits() {
        assertThrows(IOException::class.java) { http { Connection(it, length = 101) }.read(latest, null, 100) }
        assertThrows(IOException::class.java) { http { Connection(it, text = "abcd") }.read(latest, null, 3) }
        assertEquals("abc", http { Connection(it) }.read(latest, null, 3).body)
    }

    @Test fun partialContentIsNotAcceptedAsMetadata() {
        assertThrows(Exception::class.java) { http { Connection(it, 206, headers = mapOf("Content-Range" to "bytes 0-2/100")) }.read(asset, null, 100) }
    }

    @Test fun apkProbeResolvesOnlyOfficialSignedHttpsAssetAndChecksTotalSize() {
        val calls = mutableListOf<Connection>()
        val url = http { target -> (if (target.toString() == asset) Connection(target, 302, headers = mapOf("Location" to cdn))
            else Connection(target, 206, "x", mapOf("Content-Range" to "bytes 0-0/134734336"), 1)).also(calls::add) }.resolveApk(asset, 134734336)
        assertEquals(cdn, url)
        calls.forEach { assertEquals("bytes=0-0", it.getRequestProperty("Range")); assertTrue(it.closed) }
    }

    @Test fun apkProbeRejectsWrongRangeSizeStatusAndBody() {
        for (response in listOf(Connection(URL(cdn), 206, "x", mapOf("Content-Range" to "bytes 0-0/4"), 1),
            Connection(URL(cdn), 200, "x", mapOf("Content-Range" to "bytes 0-0/3"), 1),
            Connection(URL(cdn), 206, "xx", mapOf("Content-Range" to "bytes 0-0/3"), 1),
            Connection(URL(cdn), 206, "x", mapOf("Content-Range" to "bytes 1-1/3"), 1))) {
            assertThrows(Exception::class.java) { http { if (it.toString() == asset) Connection(it, 302, headers = mapOf("Location" to cdn)) else response }.resolveApk(asset, 3) }
        }
    }

    @Test fun interruptedRequestDoesNotOpenANetworkConnection() {
        var calls = 0
        Thread.currentThread().interrupt()
        try { assertThrows(IOException::class.java) { http { calls++; Connection(it) }.read(latest, null, 100) } }
        finally { Thread.interrupted() }
        assertEquals(0, calls)
    }
}
