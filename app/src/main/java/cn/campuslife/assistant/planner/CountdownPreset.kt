package cn.campuslife.assistant.planner

/** Shortcuts only. Saved records still contain the user's title, not an unstable option index. */
enum class CountdownPreset(val label: String, val yearlyDefault: Boolean = false) {
    BIRTHDAY("生日",true), ANNIVERSARY("纪念日",true), FITNESS("体测"), EXAM("考试"),
    COMPETITION("比赛"), HOLIDAY("假期"), CUSTOM("自定义");

    companion object {
        fun fromTitle(title: String) = entries.firstOrNull { it!=CUSTOM && it.label==title } ?: CUSTOM
    }
}
