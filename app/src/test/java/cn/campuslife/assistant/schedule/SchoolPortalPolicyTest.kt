package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test

class SchoolPortalPolicyTest {
    @Test fun publishedTimetableRequestsUseApiPrefixAndBaToken() {
        assertEquals("Bearer school_token_123456789", SchoolPortalPolicy.timetableAuthorization(
            "https://jw-api.fjpit.com/api/semesters", mapOf("ba-token" to "school_token_123456789")))
        assertNull(SchoolPortalPolicy.timetableAuthorization("https://jw-api.fjpit.com/api/auth/getUserInfo",
            mapOf("ba-token" to "school_token_123456789")))
    }
    @Test fun loginAllowsOnlyHttpsSchoolHostsWithoutCredentialsOrNonTlsPorts() {
        assertTrue(SchoolPortalPolicy.schoolPage("https://m.fjpit.com/#/pages/login/login"))
        assertTrue(SchoolPortalPolicy.schoolPage("https://auth.fjpit.com/login"))
        listOf("http://m.fjpit.com/", "https://fjpit.com.evil.test/", "https://evilfjpit.com/",
            "https://m.fjpit.com@evil.test/", "https://user@m.fjpit.com/", "file:///etc/passwd",
            "https://m.fjpit.com:8443/", "javascript:alert(1)").forEach {
            assertFalse(it, SchoolPortalPolicy.schoolPage(it))
        }
    }

    @Test fun observesOnlyTimetableApiAuthorizationAndNeverOtherHostsOrEndpoints() {
        val headers = mapOf("authorization" to "Bearer abcdefghijklmnop.12345678")
        assertEquals("Bearer abcdefghijklmnop.12345678", SchoolPortalPolicy.timetableAuthorization("https://jw-api.fjpit.com/api/semesters", headers))
        assertNull(SchoolPortalPolicy.timetableAuthorization("https://other.fjpit.com/api/semesters", headers))
        assertNull(SchoolPortalPolicy.timetableAuthorization("https://jw-api.fjpit.com/api/profile", headers))
        assertNull(SchoolPortalPolicy.timetableAuthorization("https://jw-api.fjpit.com/semesters", headers))
        assertNull(SchoolPortalPolicy.timetableAuthorization("http://jw-api.fjpit.com/api/schedule", headers))
        assertNull(SchoolPortalPolicy.timetableAuthorization("https://jw-api.fjpit.com/api/schedule", mapOf("Authorization" to "Bearer bad\r\nInjected: value")))
        assertNull(SchoolPortalPolicy.timetableAuthorization("https://jw-api.fjpit.com/api/schedule", emptyMap()))
    }
    @Test fun baTokenIsCaseInsensitiveAndCannotFallBackToAnOldHeaderOnLogout() {
        val url = "https://jw-api.fjpit.com/api/schedule"
        assertEquals("Bearer current_school_token", SchoolPortalPolicy.timetableAuthorization(url,
            mapOf("BA-TOKEN" to "current_school_token", "Authorization" to "Bearer stale_profile_token")))
        assertNull(SchoolPortalPolicy.timetableAuthorization(url,
            mapOf("ba-token" to "", "Authorization" to "Bearer stale_profile_token")))
        assertNull(SchoolPortalPolicy.timetableAuthorization(url, mapOf("ba-token" to "bad\r\nHeader")))
    }
}
