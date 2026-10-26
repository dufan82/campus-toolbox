package cn.campuslife.assistant.update

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class UpdateCacheTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Response(url: URL, val code: Int) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() = Unit
        override fun getResponseCode() = code
    }

    @Test fun noReleaseIsCachedAndManualRequestsAreDebouncedWithoutClaimingLatest() {
        val prefs = context.getSharedPreferences("update-cache-test", 0)
        prefs.edit().clear().commit()
        var now = 200_000_000L; var requests = 0
        val repo = UpdateRepository(prefs, GitHubReleaseClient { requests++; Response(it, 404) }) { now }
        assertEquals("暂未发布正式版本", repo.check(false).message)
        repo.check(false); repo.check(true)
        assertEquals(1, requests)
        now += 60_001; repo.check(true)
        assertEquals(2, requests)
        now += UpdateRules.INTERVAL + 1; repo.check(false)
        assertEquals(3, requests)
        prefs.edit().clear().commit()
    }

    @Test fun failedRequestsBackOffAndManualCheckCannotBypassServerLimit() {
        val prefs = context.getSharedPreferences("update-error-test", 0)
        prefs.edit().clear().commit()
        val now = System.currentTimeMillis(); var requests = 0
        val repo = UpdateRepository(prefs, GitHubReleaseClient { requests++; Response(it, 429) }) { now }
        assertTrue(repo.check(false).message.contains("稍后"))
        repo.check(true); repo.check(false)
        assertEquals(1, requests)
        prefs.edit().clear().commit()
    }
    @Test fun ordinaryNetworkFailureDoesNotLockManualRetryForAnHour() {
        val prefs = context.getSharedPreferences("update-offline-retry-test", 0); prefs.edit().clear().commit()
        var now = 200_000_000L; var requests = 0; var online = false
        val repo = UpdateRepository(prefs, GitHubReleaseClient {
            requests++; if (!online) throw java.io.IOException("offline fixture")
            Response(it, 404)
        }) { now }
        try {
            repo.check(false); online = true
            now += 1000; repo.check(true); assertEquals(1, requests)
            now += 60_001; assertEquals("暂未发布正式版本", repo.check(true).message)
            assertEquals(2, requests)
        } finally { prefs.edit().clear().commit() }
    }
}
