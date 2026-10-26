package cn.campuslife.assistant.schedule

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

enum class WeekPattern { EVERY, ODD, EVEN;
    fun includes(week: Int) = when (this) { EVERY -> true; ODD -> week % 2 == 1; EVEN -> week % 2 == 0 }
}

data class Semester(val startMonday: LocalDate, val totalWeeks: Int) {
    fun checked() = apply {
        require(startMonday.dayOfWeek == DayOfWeek.MONDAY) { "学期起始日必须是周一" }
        require(startMonday.year in 1900..2200 && totalWeeks in 1..60) { "年份应为1900–2200，总周数应为1–60" }
    }
}

data class SchedulePeriod(val label: String, val start: LocalTime? = null, val end: LocalTime? = null)
data class Course(
    val id: String, val name: String, val teacher: String, val classroom: String,
    val weekday: Int, val startPeriod: Int, val endPeriod: Int,
    val startWeek: Int, val endWeek: Int, val pattern: WeekPattern, val placeId: String?
) {
    fun occurs(week: Int) = week in startWeek..endWeek && pattern.includes(week)
}

data class ScheduleData(
    val semester: Semester? = null,
    val periods: List<SchedulePeriod> = (1..12).map { SchedulePeriod("第${it}节") },
    val courses: List<Course> = emptyList()
) {
    fun checked() = apply {
        semester?.checked()
        require(periods.size in 1..24) { "节次数量应为1–24" }
        var previousEnd: LocalTime? = null
        periods.forEach { period ->
            text(period.label, 24, false)
            require((period.start == null) == (period.end == null)) { "每节的开始和结束时间须同时填写或同时留空" }
            period.start?.let { start ->
                require(start.second == 0 && start.nano == 0 && period.end!!.second == 0 && period.end.nano == 0) { "时间请精确到分钟" }
                require(start < period.end && (previousEnd == null || start >= previousEnd)) { "节次时间须先后有序，且结束晚于开始" }
                previousEnd = period.end
            }
        }
        require(courses.size <= 500) { "课程最多500条" }
        require(courses.map { it.id }.distinct().size == courses.size) { "课程ID重复" }
        courses.forEach { course ->
            require(course.id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "课程ID无效" }
            text(course.name, 80, false); text(course.teacher, 80); text(course.classroom, 120)
            require(course.weekday in 1..7) { "星期应为1–7" }
            require(course.startPeriod in 1..periods.size && course.endPeriod in course.startPeriod..periods.size) { "课程节次超出范围，请先调整课程或节次设置" }
            require(semester != null && course.startWeek in 1..semester.totalWeeks && course.endWeek in course.startWeek..semester.totalWeeks) { "课程周次超出学期范围，请先调整课程" }
            require((course.startWeek..course.endWeek).any { course.pattern.includes(it) }) { "所选单双周在起止周内没有上课周" }
            require(course.placeId == null || course.placeId.matches(Regex("[a-z0-9][a-z0-9_-]{0,99}"))) { "地点ID无效" }
        }
    }

    fun add(course: Course): ScheduleData {
        require(courses.none { it.id == course.id }) { "课程ID已存在" }
        return copy(courses = courses + course).checked()
    }
    fun update(course: Course): ScheduleData {
        require(courses.any { it.id == course.id }) { "课程已不存在，请关闭编辑后重试" }
        return copy(courses = courses.map { if (it.id == course.id) course else it }).checked()
    }
    fun delete(id: String): ScheduleData {
        require(courses.any { it.id == id }) { "课程已不存在，请刷新后重试" }
        return copy(courses = courses.filterNot { it.id == id }).checked()
    }

    companion object {
        private fun text(value: String, max: Int, optional: Boolean = true) {
            require(value.length <= max && (optional || value.isNotBlank()) && value.none { it.isISOControl() }) { "文字为空、过长或包含控制字符" }
        }
    }
}

enum class SemesterPhase { BEFORE, ACTIVE, AFTER }
data class SemesterPosition(val phase: SemesterPhase, val week: Int?)

object ScheduleCalendar {
    fun position(semester: Semester, date: LocalDate): SemesterPosition {
        semester.checked()
        val days = ChronoUnit.DAYS.between(semester.startMonday, date)
        return when {
            days < 0 -> SemesterPosition(SemesterPhase.BEFORE, null)
            days >= semester.totalWeeks * 7L -> SemesterPosition(SemesterPhase.AFTER, null)
            else -> SemesterPosition(SemesterPhase.ACTIVE, (days / 7).toInt() + 1)
        }
    }
    fun week(data: ScheduleData, week: Int): List<Course> {
        val semester = data.semester ?: return emptyList()
        if (week !in 1..semester.totalWeeks) return emptyList()
        return data.courses.filter { it.occurs(week) }.sortedWith(compareBy({ it.weekday }, { it.startPeriod }, { it.name }, { it.id }))
    }
    fun today(data: ScheduleData, date: LocalDate): List<Course> {
        val position = data.semester?.let { position(it, date) } ?: return emptyList()
        return position.week?.let { week(data, it).filter { course -> course.weekday == date.dayOfWeek.value } } ?: emptyList()
    }
    fun overlaps(first: Course, second: Course): Boolean = first.weekday == second.weekday &&
        first.startPeriod <= second.endPeriod && second.startPeriod <= first.endPeriod &&
        (maxOf(first.startWeek, second.startWeek)..minOf(first.endWeek, second.endWeek)).any { first.occurs(it) && second.occurs(it) }

    fun conflicts(data: ScheduleData, course: Course) = data.courses.filter { it.id != course.id && overlaps(it, course) }
    fun canNavigate(course: Course, visibleIds: Set<String>, pointIds: Set<String>) =
        course.placeId != null && course.placeId in visibleIds && course.placeId in pointIds
}
