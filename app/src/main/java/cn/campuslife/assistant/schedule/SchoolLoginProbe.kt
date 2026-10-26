package cn.campuslife.assistant.schedule

import org.json.JSONTokener
import java.net.URI

/** Reads only the school's persisted user token in its own page; no JS-to-native interface. */
object SchoolLoginProbe {
    val script = """
        (function() {
            if (location.origin !== 'https://m.fjpit.com') return null;
            try {
                var state = null;
                if (window.uni && typeof window.uni.getStorageSync === 'function') {
                    state = window.uni.getStorageSync('user');
                }
                if (!state) state = localStorage.getItem('user');
                for (var i = 0; i < 3; i++) {
                    if (typeof state === 'string') state = JSON.parse(state);
                    else if (state && state.type && state.data) state = state.data;
                    else break;
                }
                var token = state && state.tokenInfo && state.tokenInfo.token;
                if (typeof token !== 'string' || token.length < 8 || token.length > 8185) return null;
                return 'Bearer ' + token;
            } catch (e) { return null; }
        })()
    """.trimIndent()

    fun authorization(pageUrl: String?, result: String?): String? {
        if (pageUrl == null || result == null || result.length > 8300) return null
        val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        if (!SchoolPortalPolicy.schoolPage(pageUrl) || uri.host != "m.fjpit.com") return null
        val value = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull() ?: return null
        return SchoolPortalPolicy.timetableAuthorization("https://jw-api.fjpit.com/api/semesters", mapOf("Authorization" to value))
    }
}
