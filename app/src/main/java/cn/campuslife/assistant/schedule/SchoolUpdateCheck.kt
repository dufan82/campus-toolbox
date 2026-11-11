package cn.campuslife.assistant.schedule

import java.time.LocalDate

data class SchoolCheckPreview(val plan: SchoolAutoPlan, val initialImport: Boolean)

/** Manual checks use exactly the same conservative ownership rules as the daily job. */
object SchoolUpdateCheck {
    fun preview(snapshot: ScheduleSnapshot, school: ScheduleData, today: LocalDate): SchoolCheckPreview {
        if (snapshot.data.semester != null) return SchoolCheckPreview(SchoolAutoMerge.plan(snapshot, school, today), false)
        require(snapshot.data.courses.isEmpty() && snapshot.registry == SchoolRegistry()) { "本地存在课程或保护记录，请先核对学期设置。" }
        require(snapshot.data.periods == ScheduleData().periods || snapshot.data.periods == school.periods) { "本地节次已调整，请先核对学期和节次设置。" }
        val empty = school.copy(courses = emptyList())
        return SchoolCheckPreview(SchoolAutoMerge.plan(ScheduleSnapshot(empty, SchoolRegistry()), school, today), true)
    }
}
