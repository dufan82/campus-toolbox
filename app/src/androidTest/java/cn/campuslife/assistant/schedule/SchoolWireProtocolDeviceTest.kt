package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class SchoolWireProtocolDeviceTest {
    private class Connection(url: URL, private val response: String) : HttpURLConnection(url) {
        val body = ByteArrayOutputStream()
        var closed = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { closed = true }
        override fun getResponseCode() = 200
        override fun getInputStream() = response.byteInputStream()
        override fun getOutputStream() = body
    }

    @Test fun nativeRequestsMatchSchoolApiPrefixBaTokenAndServerHeadersWithoutNetwork() {
        lateinit var connection: Connection
        val client = SchoolPortalClient.network("Bearer school_wire_token_12345", AtomicBoolean(false)) { url ->
            Connection(url, """{"code":1,"data":{"semesters":[{"value":"2026-2027-1","label":"秋季","isCurrent":true}]}}""").also { connection = it }
        }
        assertEquals("2026-2027-1", client.terms().single().id)
        assertEquals("https://jw-api.fjpit.com/api/semesters", connection.url.toString())
        assertEquals("school_wire_token_12345", connection.getRequestProperty("ba-token"))
        assertEquals("1", connection.getRequestProperty("server"))
        assertEquals("h5", connection.getRequestProperty("platform"))
        assertNull(connection.getRequestProperty("Authorization"))
        assertEquals("GET", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.closed)
    }

    @Test fun completeTermPostsUseTheSameProtocolAndKeepAllWeekBodies() {
        val calls = mutableListOf<Connection>()
        val client = SchoolPortalClient.network("Bearer school_wire_token_12345", AtomicBoolean(false)) { url ->
            val data = when (url.path) {
                "/api/semesterConfig" -> """{"semestersConfig":{"startDate":"2026-09-07","totalWeeks":2}}"""
                "/api/schedule" -> """{"list":[{"course":"合成课程","teacherName":"A","spaceName":"教室","DayIndex":1,"startNode":1,"endNode":1}]}"""
                "/api/scheduleTime" -> """{"1":{"jcdm":1,"jcmc":"第1节","jcskkssj":"08:30:00","jcskjssj":"09:15:00"}}"""
                else -> error("Unexpected school endpoint")
            }
            Connection(url, """{"code":1,"data":$data}""").also { calls.add(it) }
        }
        val result = client.collect(SchoolTerm("2026-2027-1", "当前"), AtomicBoolean(false)) { _, _ -> }
        assertEquals(listOf("/api/semesterConfig", "/api/schedule", "/api/scheduleTime", "/api/schedule", "/api/scheduleTime"), calls.map { it.url.path })
        assertEquals(listOf(1, 2), calls.filter { it.url.path == "/api/schedule" }.map { JSONObject(it.body.toString("UTF-8")).getInt("week") })
        calls.forEach {
            assertEquals("POST", it.requestMethod)
            assertEquals("school_wire_token_12345", it.getRequestProperty("ba-token"))
            assertEquals("1", it.getRequestProperty("server"))
            assertTrue(it.closed)
        }
        assertEquals("合成课程", result.data.courses.single().name)
        assertEquals("08:30", result.data.periods.first().start.toString())
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun malformedAuthorizationIsRejectedBeforeOpeningAConnection() {
        var opened = false
        val client = SchoolPortalClient.network("Bearer bad\r\nHeader", AtomicBoolean(false)) {
            opened = true; error("Must not open a connection")
        }
        assertTrue(runCatching { client.terms() }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(opened)
    }
}
