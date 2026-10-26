package cn.campuslife.assistant.schedule

import java.net.URI

object SchoolPortalPolicy {
    const val START_URL = "https://m.fjpit.com/#/pages/student/StudentSchedule/scheduleTable"
    private val endpoints = setOf("/semesters", "/semesterConfig", "/schedule", "/scheduleTime")
    private fun secure(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
        it.scheme == "https" && it.userInfo == null && (it.port == -1 || it.port == 443) && it.host != null
    }
    fun schoolPage(url: String): Boolean = secure(url)?.host?.lowercase()?.let {
        it == "fjpit.com" || it.endsWith(".fjpit.com")
    } == true
    fun timetableAuthorization(url: String, headers: Map<String, String>): String? {
        val uri = secure(url) ?: return null
        if (uri.host.lowercase() != "jw-api.fjpit.com" || !uri.path.startsWith("/api/") || uri.path.removePrefix("/api") !in endpoints) return null
        val baToken = headers.entries.firstOrNull { it.key.equals("ba-token", true) }
        if (baToken != null) return bearer("Bearer ${baToken.value}")
        return headers.entries.firstOrNull { it.key.equals("Authorization", true) }?.value?.let(::bearer)
    }
    private fun bearer(value: String): String? = value.takeIf {
            it.length in 15..8192 && it.startsWith("Bearer ") &&
                it.substring(7).matches(Regex("[A-Za-z0-9._~+/-]+=*"))
    }
}
