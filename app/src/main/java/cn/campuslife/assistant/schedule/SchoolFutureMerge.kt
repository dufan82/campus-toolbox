package cn.campuslife.assistant.schedule

import java.time.LocalDate
import java.util.UUID

data class SchoolFuturePlan(val data: ScheduleData, val managed: Map<String, Course>, val blockedOccurrences: Int)

/** Produces a plan only; inputs and disk are never modified. */
object SchoolFutureMerge {
    fun plan(local: ScheduleData, school: ScheduleData, managed: Map<String, Course>, selected: Set<String>, today: LocalDate,
             protectedIds: Set<String> = emptySet()): SchoolFuturePlan {
        local.checked(); school.checked()
        require(local.semester != null && local.semester == school.semester) { "学期不一致，无法安全合并；请保留原课表核对。" }
        require(local.periods == school.periods) { "本地与学校作息设置不同，无法安全合并。" }
        val old = local.courses.associateBy { it.id }
        // A protected ID may refer to a deleted or rescheduled imported course. Without its
        // original dated slots, importing even a different ID could silently recreate it.
        require(protectedIds.isEmpty() && managed.all { (id, course) -> old[id] == course }) {
            "存在编辑或删除过的学校课程，无法可靠识别其原时段，暂不能自动合并；原课表未改。"
        }
        require(selected.all { it in old && it !in managed && it !in protectedIds }) { "选中的课程已变化或被标记为手动保护，请重新预览。" }
        val editable = local.courses.filter { it.id in selected || (it.id !in protectedIds && managed[it.id] == it) }
        val editableIds = editable.map { it.id }.toSet()
        val protected = local.courses.filter { it.id !in editableIds }
        val semester = local.semester
        fun date(c: Course, week: Int) = semester.startMonday.plusWeeks((week - 1).toLong()).plusDays((c.weekday - 1).toLong())
        fun part(c: Course, weeks: List<Int>, tag: String): Course? {
            if (weeks.isEmpty()) return null
            val start = weeks.first(); val end = weeks.last()
            val same = start == c.startWeek && end == c.endWeek
            val id = if (same || tag == "past") c.id else UUID.nameUUIDFromBytes("schedule-future-v1|$tag|${c.id}|$start|$end|${c.pattern}".toByteArray(Charsets.UTF_8)).toString()
            return c.copy(id = id, startWeek = start, endWeek = end)
        }
        val result = protected.toMutableList()
        editable.forEach { c -> part(c, (c.startWeek..c.endWeek).filter { c.occurs(it) && date(c, it).isBefore(today) }, "past")?.let(result::add) }
        // One 24-bit period mask per weekday and teaching week; no repeated
        // 500-course scan for each of the up to 30,000 school occurrences.
        val occupied = IntArray(7 * 61)
        fun mask(course: Course): Int = ((1 shl (course.endPeriod - course.startPeriod + 1)) - 1) shl (course.startPeriod - 1)
        protected.forEach { course ->
            val bits = mask(course)
            (course.startWeek..course.endWeek).filter(course::occurs).forEach { week ->
                val index = (course.weekday - 1) * 61 + week
                occupied[index] = occupied[index] or bits
            }
        }
        var blocked = 0
        val added = mutableListOf<Course>()
        school.courses.forEach { c ->
            val weeks = (c.startWeek..c.endWeek).filter { c.occurs(it) && !date(c, it).isBefore(today) }
            val bits = mask(c)
            val allowed = weeks.filter { week ->
                val conflict = occupied[(c.weekday - 1) * 61 + week] and bits != 0
                if (conflict) blocked++
                !conflict
            }
            // A blocked week can create holes: partition on the original pattern's expected step.
            val step = if (c.pattern == WeekPattern.EVERY) 1 else 2
            val groups = mutableListOf<MutableList<Int>>()
            allowed.forEach { week ->
                if (groups.isEmpty() || week - groups.last().last() != step) groups.add(mutableListOf())
                groups.last().add(week)
            }
            groups.forEach { group -> part(c, group, "school")?.let(added::add) }
        }
        result.addAll(added)
        require(result.map { it.id }.distinct().size == result.size) { "课程 ID 冲突，未保存；请先核对课程。" }
        val data = local.copy(courses = result).checked()
        return SchoolFuturePlan(data, added.associateBy { it.id }, blocked)
    }
}
