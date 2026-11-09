package cn.campuslife.assistant.schedule

import java.time.LocalDate

/** Comparison only. The schema does not establish ownership of existing courses. */
data class ScheduleOccurrence(
    val date: LocalDate, val name: String, val teacher: String, val classroom: String,
    val startPeriod: Int, val endPeriod: Int
)
data class ScheduleUpdateDifference(val onlyLocal: List<ScheduleOccurrence>, val onlySchool: List<ScheduleOccurrence>)

object ScheduleUpdatePreview {
    fun compare(local: ScheduleData, school: ScheduleData, today: LocalDate): ScheduleUpdateDifference {
        local.checked(); school.checked()
        fun occurrences(data: ScheduleData): List<ScheduleOccurrence> {
            val semester = data.semester ?: return emptyList()
            return data.courses.flatMap { course ->
                (course.startWeek..course.endWeek).filter(course::occurs).mapNotNull { week ->
                    val date = semester.startMonday.plusWeeks((week - 1).toLong()).plusDays((course.weekday - 1).toLong())
                    if (date.isBefore(today)) null else ScheduleOccurrence(date, course.name, course.teacher,
                        course.classroom, course.startPeriod, course.endPeriod)
                }
            }
        }
        val order = compareBy<ScheduleOccurrence>({ it.date }, { it.startPeriod }, { it.endPeriod },
            { it.name }, { it.teacher }, { it.classroom })
        fun subtract(first: List<ScheduleOccurrence>, second: List<ScheduleOccurrence>): List<ScheduleOccurrence> {
            val remaining = second.groupingBy { it }.eachCount().toMutableMap()
            return first.filter { row ->
                val count = remaining[row] ?: 0
                if (count == 0) true else { remaining[row] = count - 1; false }
            }.sortedWith(order)
        }
        val localRows = occurrences(local)
        val schoolRows = occurrences(school)
        return ScheduleUpdateDifference(subtract(localRows, schoolRows), subtract(schoolRows, localRows))
    }
}
