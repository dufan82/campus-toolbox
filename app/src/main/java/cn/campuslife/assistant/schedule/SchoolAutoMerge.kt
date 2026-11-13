package cn.campuslife.assistant.schedule

import java.time.LocalDate
import java.util.UUID

data class SchoolChanges(val added: Int = 0, val adjusted: Int = 0, val cancelled: Int = 0) {
    val total get() = added + adjusted + cancelled
    fun summary() = "新增 $added 门，调整 $adjusted 门，取消 $cancelled 门"
}
enum class SchoolSkipReason(val explanation: String) {
    UNKNOWN_ORIGIN("来源未确认，不能自动认领或覆盖"),
    MANUAL_EDIT("手动修改或地点关联保护，保留本地课程"),
    DELETED("删除保护，不能恢复已删除课程"),
    REVOKED_SOURCE("存在编辑或删除保护，无法确认新名称是否为原课程，不能自动新增"),
    TIME_CONFLICT("与本地受保护课程时间冲突")
}

/** Evidence compares local content with the school, independently of write ownership. */
data class SchoolGroupDifference(val name: String, val local: List<Course>, val school: List<Course>,
    val reasons: List<SchoolSkipReason> = emptyList(), val blockers: List<Course> = emptyList()) {
    val blocked get() = reasons.isNotEmpty()
}

data class SchoolAutoPlan(val data: ScheduleData, val managed: Map<String, Course>, val changes: SchoolChanges, val conflicts: Int,
    val differences: List<SchoolGroupDifference> = emptyList())

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
            // IDs and local place links are not school-published content. Equality never grants ownership.
            listOf(date(c,w).toString(),c.name,c.teacher,c.classroom,c.startPeriod.toString(),c.endPeriod.toString())
        } }.groupingBy { it }.eachCount()
        val protected = local.courses.filter { it.id !in managed }
        val protectedNames = (protected.filter { weeks(it,true).isNotEmpty() } + registry.protectedCourses.values +
            registry.managed.filterKeys { it !in managed }.values).map { it.name }.toSet()
        val hasRevokedSource = registry.protectedIds.isNotEmpty() || registry.managed.any { (id,_) -> id !in managed }
        val oldGroups = managed.values.groupBy { it.name }
        val localGroups = local.courses.groupBy { it.name }
        val newGroups = school.courses.groupBy { it.name }
        val result = local.courses.toMutableList()
        val nextManaged = managed.toMutableMap()
        var added = 0; var adjusted = 0; var cancelled = 0; var conflicts = 0
        val differences = mutableListOf<SchoolGroupDifference>()
        fun part(c: Course, ws: List<Int>, future: Boolean): Course? {
            if (ws.isEmpty()) return null
            val key = "school-auto-v1|${c.id}|${c.name}|${c.teacher}|${c.classroom}|${c.weekday}|${c.startPeriod}|${c.endPeriod}|${ws.first()}|${ws.last()}|${c.pattern}"
            return c.copy(id = if (!future) c.id else UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString(), startWeek=ws.first(), endWeek=ws.last())
        }
        for (name in (oldGroups.keys + newGroups.keys).sorted()) {
            val old = oldGroups[name].orEmpty(); val incoming = newGroups[name].orEmpty()
            val localGroup = localGroups[name].orEmpty()
            // Compare actual content, not just registry-owned rows. Unknown/protected but identical
            // records are a no-op; they remain unowned/protected for subsequent real changes.
            if (signature(localGroup) == signature(incoming)) continue
            // Unidentified old deletion cannot be matched to a renamed/retimed new course: do not resurrect it.
            val unsafeNew = hasRevokedSource && old.isEmpty()
            val originals = (registry.protectedCourses.values + registry.managed.filterKeys { it !in managed }.values)
                .filter { it.name == name }
            val related = (localGroup + local.courses.filter { c -> originals.any { it.id == c.id } }).distinct()
            val blockers = protected.filter { p -> p.name != name && incoming.any { c -> p.weekday == c.weekday &&
                c.startPeriod <= p.endPeriod && p.startPeriod <= c.endPeriod && weeks(c,true).any(p::occurs) } }
            val reasons = mutableListOf<SchoolSkipReason>()
            if (name in protectedNames) {
                if (originals.any { original -> local.courses.none { it.id == original.id } }) reasons += SchoolSkipReason.DELETED
                if (related.any { it.id in registry.protectedIds || originals.any { original -> original.id == it.id } }) reasons += SchoolSkipReason.MANUAL_EDIT
                if (localGroup.any { c -> c.id !in managed && c.id !in registry.protectedIds && originals.none { it.id == c.id } }) reasons += SchoolSkipReason.UNKNOWN_ORIGIN
            }
            if (unsafeNew) reasons += SchoolSkipReason.REVOKED_SOURCE
            if (blockers.isNotEmpty()) reasons += SchoolSkipReason.TIME_CONFLICT
            differences += SchoolGroupDifference(name,related,incoming,reasons,blockers)
            if (reasons.isNotEmpty()) {
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
        return SchoolAutoPlan(data,nextManaged,SchoolChanges(added,adjusted,cancelled),conflicts,differences)
    }
}
