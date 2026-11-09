package cn.campuslife.assistant.schedule

import java.time.LocalDate
import java.util.UUID

data class SchoolChanges(val added: Int = 0, val adjusted: Int = 0, val cancelled: Int = 0) {
    val total get() = added + adjusted + cancelled
    fun summary() = "新增 $added 门，调整 $adjusted 门，取消 $cancelled 门"
}
data class SchoolAutoPlan(val data: ScheduleData, val managed: Map<String, Course>, val changes: SchoolChanges, val conflicts: Int)

/** No stable school ID is published. Compare exact dated occurrences, retaining unchanged records verbatim.
 * A changed/protected course name is treated as one conservative conflict group, not a whole-term veto.
 */
object SchoolAutoMerge {
    fun plan(snapshot: ScheduleSnapshot, school: ScheduleData, today: LocalDate): SchoolAutoPlan {
        val local = snapshot.data.checked(); school.checked()
        require(local.semester != null && local.semester == school.semester) { "学期不一致，原课表未改" }
        require(local.periods == school.periods) { "作息不一致，原课表未改" }
        val registry = snapshot.registry
        val managed = registry.managed.filter { (id, c) -> local.courses.find { it.id == id } == c && id !in registry.protectedIds }
        val semester = local.semester
        fun date(c: Course, week: Int) = semester.startMonday.plusWeeks((week - 1).toLong()).plusDays((c.weekday - 1).toLong())
        fun weeks(c: Course, future: Boolean) = (c.startWeek..c.endWeek).filter { c.occurs(it) && (!date(c,it).isBefore(today)) == future }
        fun signature(cs: List<Course>) = cs.flatMap { c -> weeks(c,true).map { w ->
            listOf(date(c,w).toString(),c.name,c.teacher,c.classroom,c.startPeriod.toString(),c.endPeriod.toString(),c.placeId.orEmpty())
        } }.groupingBy { it }.eachCount()
        val protected = local.courses.filter { it.id !in managed }
        val protectedNames = (protected.filter { weeks(it,true).isNotEmpty() } + registry.protectedCourses.values +
            registry.managed.filterKeys { it !in managed }.values).map { it.name }.toSet()
        val hasRevokedSource = registry.protectedIds.isNotEmpty() || registry.managed.any { (id,_) -> id !in managed }
        val oldGroups = managed.values.groupBy { it.name }
        val newGroups = school.courses.groupBy { it.name }
        val result = local.courses.toMutableList()
        val nextManaged = managed.toMutableMap()
        var added = 0; var adjusted = 0; var cancelled = 0; var conflicts = 0
        fun overlaps(c: Course, w: Int) = protected.any { p -> p.weekday == c.weekday && p.occurs(w) &&
            c.startPeriod <= p.endPeriod && p.startPeriod <= c.endPeriod }
        fun part(c: Course, ws: List<Int>, future: Boolean): Course? {
            if (ws.isEmpty()) return null
            val key = "school-auto-v1|${c.id}|${c.name}|${c.teacher}|${c.classroom}|${c.weekday}|${c.startPeriod}|${c.endPeriod}|${ws.first()}|${ws.last()}|${c.pattern}"
            return c.copy(id = if (!future) c.id else UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString(), startWeek=ws.first(), endWeek=ws.last())
        }
        for (name in (oldGroups.keys + newGroups.keys).sorted()) {
            val old = oldGroups[name].orEmpty(); val incoming = newGroups[name].orEmpty()
            if (signature(old) == signature(incoming)) continue
            // Unidentified old deletion cannot be matched to a renamed/retimed new course: do not resurrect it.
            val unsafeNew = hasRevokedSource && old.isEmpty()
            if (name in protectedNames || unsafeNew || incoming.any { c -> weeks(c,true).any { overlaps(c,it) } }) {
                conflicts++; continue
            }
            val oldFuture = old.any { weeks(it,true).isNotEmpty() }
            val newFuture = incoming.any { weeks(it,true).isNotEmpty() }
            old.forEach { c -> result.remove(c); nextManaged.remove(c.id); part(c,weeks(c,false),false)?.let(result::add) }
            incoming.forEach { c -> part(c,weeks(c,true),true)?.let { result.add(it); nextManaged[it.id]=it } }
            when { !oldFuture && newFuture -> added++; oldFuture && !newFuture -> cancelled++; else -> adjusted++ }
        }
        val data = if (result == local.courses) local else local.copy(courses=result).checked()
        require(data.courses.map { it.id }.distinct().size == data.courses.size) { "课程ID冲突，原课表未改" }
        return SchoolAutoPlan(data,nextManaged,SchoolChanges(added,adjusted,cancelled),conflicts)
    }
}
