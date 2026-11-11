package cn.campuslife.assistant.story

import java.net.URI

object StoryRules {
    fun characterCount(value: String): Int = value.trim().let { it.codePointCount(0,it.length) }
    fun validId(value: String) = value.matches(Regex("story-[a-z0-9][a-z0-9-]{0,69}"))
    fun fontSize(value: Int) = value.coerceIn(16,18)
    fun progress(value: Double) = if (value.isFinite()) value.coerceIn(0.0,1.0) else 0.0
    fun filter(entries: List<Story>, category: StoryCategory?, favoritesOnly: Boolean, favorites: Set<String>) =
        entries.filter { (category==null || it.category==category) && (!favoritesOnly || it.id in favorites) }
    fun next(entries: List<Story>, currentId: String): Story? =
        if (entries.isEmpty()) null else entries[(entries.indexOfFirst { it.id==currentId }+1)%entries.size]
    fun validate(draft: StoryDraft): List<StoryDraftError> = buildList {
        if (characterCount(draft.title) !in 2..30) add(StoryDraftError.TITLE)
        if (draft.category==null) add(StoryDraftError.CATEGORY)
        if (characterCount(draft.body) !in 100..5000) add(StoryDraftError.BODY)
        if (characterCount(draft.nickname)>30) add(StoryDraftError.NICKNAME)
        if (!draft.originalRights) add(StoryDraftError.ORIGINAL_RIGHTS)
        if (!draft.displayPermission) add(StoryDraftError.DISPLAY_PERMISSION)
    }
    /** Parses only: this does not claim that a configured site exists or has been audited. */
    fun publicFormUrl(raw: String): String? = runCatching {
        require(raw.isNotEmpty() && raw==raw.trim() && raw.none { it.isWhitespace() || it.isISOControl() })
        val uri=URI(raw)
        require(uri.scheme=="https" && uri.userInfo==null && uri.host!=null && uri.fragment==null)
        require(uri.host.contains('.') && !uri.host.endsWith(".localhost") && uri.host!="127.0.0.1")
        require(uri.port==-1 || uri.port==443)
        uri.toASCIIString()
    }.getOrNull()
}
