package cn.campuslife.assistant.feedback

import java.net.URI

enum class FeedbackCategory(val label: String) {
    SUGGESTION("功能建议"), BUG("功能异常"), SCHEDULE("课表问题"), DESIGN("界面设计建议"), OTHER("其他")
}

data class FeedbackDraft(
    val category: FeedbackCategory? = null,
    val content: String = "",
    val contact: String = ""
)

enum class FeedbackError(val message: String) {
    CATEGORY_REQUIRED("请选择反馈类型"), CONTENT_REQUIRED("请填写反馈内容"), CONTENT_TOO_LONG("反馈内容最多1000字")
}

object FeedbackRules {
    const val MAX_CONTENT_LENGTH = 1000

    /** Validate developer configuration only; never prefill or attach local/user data. */
    fun formUrl(configured: String): String? {
        if (configured.any { it.isISOControl() }) return null
        val value = configured.trim()
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.isOpaque || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || (uri.port != -1 && uri.port !in 1..65535)) return null
        // Android Intent scheme matching is case-sensitive; preserve the rest of the URL verbatim.
        return "https:" + uri.toASCIIString().substringAfter(':')
    }

    fun validate(draft: FeedbackDraft): FeedbackError? = when {
        draft.category == null -> FeedbackError.CATEGORY_REQUIRED
        draft.content.isBlank() -> FeedbackError.CONTENT_REQUIRED
        draft.content.length > MAX_CONTENT_LENGTH -> FeedbackError.CONTENT_TOO_LONG
        else -> null
    }
}
