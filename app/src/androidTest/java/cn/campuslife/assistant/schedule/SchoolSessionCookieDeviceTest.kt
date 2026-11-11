package cn.campuslife.assistant.schedule

import android.webkit.CookieManager
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Offline connections only; never logs or exports any real school credentials. */
class SchoolSessionCookieDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val api = "https://jw-api.fjpit.com/api/semesters"
    private val cookieName = "campus_session_offline_test"
    private fun cookie(value: String) {
        val done = CountDownLatch(1)
        inst.runOnMainSync {
            CookieManager.getInstance().setCookie(api, "$cookieName=$value; Path=/; Secure; HttpOnly; SameSite=Lax") { done.countDown() }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }
    private fun clearCookie() {
        val done = CountDownLatch(1)
        inst.runOnMainSync {
            CookieManager.getInstance().setCookie(api, "$cookieName=; Path=/; Secure; Max-Age=0") { done.countDown() }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }
    private class Connection(url: URL, private val body: String) : HttpURLConnection(url) {
        val output = ByteArrayOutputStream()
        var closed = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { closed = true }
        override fun getResponseCode() = if (getRequestProperty("Cookie")?.contains("campus_session_offline_test=synthetic-device") == true) 200 else 403
        override fun getInputStream() = body.byteInputStream()
        override fun getOutputStream() = output
    }

    @Test fun authenticatedSchoolReadUsesTheExistingInAppWebSessionForAllFourEndpoints() {
        cookie("synthetic-device")
        val calls = mutableListOf<Connection>()
        val client = SchoolPortalClient.network("Bearer school_cookie_offline_12345", AtomicBoolean()) { url ->
            val response = when (url.path) {
                "/api/semesters" -> """{"semesters":[{"value":"2026-2027-1","label":"秋季","isCurrent":true}]}"""
                "/api/semesterConfig" -> """{"semestersConfig":{"startDate":"2026-09-07","totalWeeks":1}}"""
                "/api/schedule" -> """{"list":[]}"""
                "/api/scheduleTime" -> """{"1":{"jcdm":1,"jcmc":"第1节","jcskkssj":"08:30:00","jcskjssj":"09:15:00"}}"""
                else -> error("No other endpoints are allowed")
            }
            Connection(url, """{"code":1,"data":$response}""").also { calls.add(it) }
        }
        try {
            val term = client.terms().single()
            client.collect(term, AtomicBoolean()) { _, _ -> }
            assertEquals(listOf("/api/semesters", "/api/semesterConfig", "/api/schedule", "/api/scheduleTime"), calls.map { it.url.path })
            assertTrue(calls.all { it.closed && !it.instanceFollowRedirects })
            assertTrue(calls.all { it.getRequestProperty("Cookie").contains("$cookieName=synthetic-device") })
        } finally { clearCookie() }
    }

    @Test fun schoolPublishedLoginExpiryCodesAreNotMistakenForMalformedSemesterData() {
        for (code in listOf(303, 409)) {
            assertThrows(SchoolAuthenticationException::class.java) {
                SchoolPortalJson.semesters(JSONObject("""{"code":$code,"msg":"synthetic private message"}"""))
            }
        }
    }
}
