package cn.campuslife.assistant.schedule

import java.time.LocalDate

data class SchoolCheckPreview(val plan: SchoolAutoPlan, val initialImport: Boolean, val confirmableLegacy:Set<String> = emptySet())

/** Defaults match the daily job. Only explicit, previewed source confirmation can adopt unknown rows. */
object SchoolUpdateCheck {
    fun preview(snapshot: ScheduleSnapshot, school: ScheduleData, today: LocalDate, confirmedLegacy:Set<String> = emptySet()): SchoolCheckPreview {
        if (snapshot.data.semester != null) {
            val baseline=SchoolAutoMerge.plan(snapshot,school,today)
            val semester=snapshot.data.semester
            fun future(c:Course)=(c.startWeek..c.endWeek).any {w->c.occurs(w) &&
                !semester.startMonday.plusWeeks((w-1).toLong()).plusDays((c.weekday-1).toLong()).isBefore(today)}
            val allowed=baseline.differences.filter {d->
                SchoolSkipReason.UNKNOWN_ORIGIN in d.reasons &&
                d.reasons.all {it==SchoolSkipReason.UNKNOWN_ORIGIN || it==SchoolSkipReason.REVOKED_SOURCE} &&
                d.local.isNotEmpty() && d.school.any(::future) &&
                d.local.all {c->c.id !in snapshot.registry.managed && c.id !in snapshot.registry.protectedIds &&
                    c.placeId==null && snapshot.registry.protectedCourses.values.none {it.id==c.id || it.name==c.name}}
            }.associate {it.name to it.local}
            require(confirmedLegacy.all {it in allowed}) {"选中的课程已变化或受保护，请重新检查；原课表未改。"}
            val plan=if(confirmedLegacy.isEmpty())baseline else {
                val adopted=confirmedLegacy.flatMap {allowed.getValue(it)}.associateBy {it.id}
                SchoolAutoMerge.plan(snapshot.copy(registry=snapshot.registry.copy(managed=snapshot.registry.managed+adopted)),school,today)
            }
            return SchoolCheckPreview(plan,false,allowed.keys)
        }
        require(confirmedLegacy.isEmpty()) {"首次导入不能确认旧课程来源。"}
        require(snapshot.data.courses.isEmpty() && snapshot.registry == SchoolRegistry()) { "本地存在课程或保护记录，请先核对学期设置。" }
        require(snapshot.data.periods == ScheduleData().periods || snapshot.data.periods == school.periods) { "本地节次已调整，请先核对学期和节次设置。" }
        val empty = school.copy(courses = emptyList())
        return SchoolCheckPreview(SchoolAutoMerge.plan(ScheduleSnapshot(empty, SchoolRegistry()), school, today), true)
    }
}
