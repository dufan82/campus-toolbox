package cn.campuslife.assistant.story

enum class StoryCategory(val label: String) { LIFE("人生"), GROWTH("成长"), WARMTH("温暖"), TWIST("反转"), OTHER("其他") }

/** Content markers, not a real author identity or a user submission. */
data class Story(
    val id: String, val title: String, val summary: String, val category: StoryCategory, val body: String,
    val authorLabel: String = "原创短篇", val source: String = "本项目创作", val estimatedMinutes: Int? = null
) {
    val readingMinutes: Int get() = estimatedMinutes ?: ((StoryRules.characterCount(body)+299)/300).coerceAtLeast(1)
}

data class StoryDraft(
    val title: String = "", val category: StoryCategory? = null, val body: String = "",
    val nickname: String = "", val originalRights: Boolean = false, val displayPermission: Boolean = false
) {
    val nicknameLabel: String get() = nickname.trim().ifEmpty { "匿名" }
}

data class StoryLocalState(
    val favorites: Set<String> = emptySet(), val progress: Map<String, Double> = emptyMap(),
    val fontSp: Int = 17, val draft: StoryDraft = StoryDraft()
)

enum class StoryDraftError(val message: String) {
    TITLE("标题请填写2–30字"), CATEGORY("请选择分类"), BODY("正文请填写100–5000字"),
    NICKNAME("昵称最多30字，可留空使用匿名"), ORIGINAL_RIGHTS("请确认原创或合法投稿与展示权限"),
    DISPLAY_PERMISSION("请确认审核与展示授权说明")
}
