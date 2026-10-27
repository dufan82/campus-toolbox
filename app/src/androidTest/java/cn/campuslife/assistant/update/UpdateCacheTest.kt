package cn.campuslife.assistant.update

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import cn.campuslife.assistant.BuildConfig

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
        val repo = UpdateRepository(prefs, GitHubReleaseClient { requests++; Response(it, 404) }, cnb = null) { now }
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
        val repo = UpdateRepository(prefs, GitHubReleaseClient { requests++; Response(it, 429) }, cnb = null) { now }
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
        }, cnb = null) { now }
        try {
            repo.check(false); online = true
            now += 1000; repo.check(true); assertEquals(1, requests)
            now += 60_001; assertEquals("暂未发布正式版本", repo.check(true).message)
            assertEquals(2, requests)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun validatorFromCnbIsNeverSentToGithubAfterFallbackOrUpgrade() {
        val prefs = context.getSharedPreferences("update-source-etag-test", 0); prefs.edit().clear().commit()
        prefs.edit().putString("source", "CNB").putString("etag", "cnb-validator").commit()
        val calls = mutableListOf<Response>()
        try {
            val repo = UpdateRepository(prefs, GitHubReleaseClient { Response(it, 404).also(calls::add) }, cnb = null) { 200_000_000L }
            repo.check(true)
            assertNull(calls.single().getRequestProperty("If-None-Match"))
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun oldGithubCacheSurvivesConditionalFetchAndIgnoreOnlyAffectsAutomaticOffer() {
        val prefs = context.getSharedPreferences("update-legacy-cache-test", 0); prefs.edit().clear().commit()
        val info = UpdatePackage(BuildConfig.VERSION_CODE.toLong() + 1, "1.7.3", "v1.7.3", 26, "app.apk", 3, "a".repeat(64), "fixture", 123)
        val legacy = UpdateJson.encode(info).apply { remove("source"); remove("repository") }
        prefs.edit().putString("info", legacy.toString()).putString("etag", "old-github").putString("kind", "release").commit()
        var now = 200_000_000L
        val calls = mutableListOf<Response>()
        val repo = UpdateRepository(prefs, GitHubReleaseClient { Response(it, 304).also(calls::add) }, cnb = null) { now }
        try {
            repo.ignore(info)
            assertFalse(repo.check(false).offer)
            assertEquals("old-github", calls.single().getRequestProperty("If-None-Match"))
            assertEquals(info, repo.cached())
            now += 60_001
            assertTrue(repo.check(true).offer)
            assertEquals("old-github", prefs.getString("github-etag", null))
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun configuredDomesticUpdateIsOfferedWithoutContactingGithubAndKeepsItsOwnCache() {
        val prefs=context.getSharedPreferences("update-domestic-cache-test",0); prefs.edit().clear().commit()
        val info=UpdatePackage(BuildConfig.VERSION_CODE.toLong()+1,"99.0.0","v99.0.0",26,"app.apk",3,"a".repeat(64),
            "fixture",123,UpdateSource.CNB,"campus-toolbox/campus-toolbox")
        var githubRequests=0; var domesticRequests=0; var now=200_000_000L
        val domestic=ReleaseClient { etag ->
            domesticRequests++
            if(etag==null) ReleaseResponse(info,"domestic-validator")
            else { assertEquals("domestic-validator",etag); ReleaseResponse(notModified=true,etag=etag) }
        }
        val repo=UpdateRepository(prefs,GitHubReleaseClient { githubRequests++; Response(it,404) },domestic) { now }
        try {
            assertTrue("A verified domestic update must be offered before querying GitHub",repo.check(false).offer)
            assertEquals(0,githubRequests); assertEquals(1,domesticRequests)
            assertEquals(info,repo.cached())
            repo.ignore(info); assertFalse(repo.check(false).offer)
            now+=60_001; assertTrue(repo.check(true).offer)
            assertEquals(0,githubRequests); assertEquals(2,domesticRequests)
            assertEquals("domestic-validator",prefs.getString("cnb:campus-toolbox/campus-toolbox-etag",null))
            assertNull(prefs.getString("github-etag",null))
        } finally { prefs.edit().clear().commit() }
    }
}
