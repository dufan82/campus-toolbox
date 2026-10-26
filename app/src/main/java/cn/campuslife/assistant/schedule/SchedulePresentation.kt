package cn.campuslife.assistant.schedule

data class ScheduleBlock(val weekday: Int, val startPeriod: Int, val endPeriod: Int, val courses: List<Course>)

/** Display rules only: never modifies the stored timetable or merges course records. */
object SchedulePresentation {
    fun periodLabel(index: Int) = "第${index}节"
    fun periodRange(course: Course) = if (course.startPeriod == course.endPeriod) periodLabel(course.startPeriod)
        else "第${course.startPeriod}–${course.endPeriod}节"
    fun colorIndex(name: String) = Math.floorMod(name.hashCode(), 6)
    fun blocks(courses: List<Course>): List<ScheduleBlock> {
        val result = mutableListOf<ScheduleBlock>()
        for ((day, entries) in courses.groupBy { it.weekday }.toSortedMap()) {
            var members = mutableListOf<Course>()
            var end = 0
            fun flush() { if (members.isNotEmpty()) result.add(ScheduleBlock(day, members.first().startPeriod, end, members.toList())) }
            for (course in entries.sortedWith(compareBy({ it.startPeriod }, { it.endPeriod }, { it.id }))) {
                if (members.isNotEmpty() && course.startPeriod > end) { flush(); members = mutableListOf(); end = 0 }
                members.add(course); end = maxOf(end, course.endPeriod)
            }
            flush()
        }
        return result
    }
}
