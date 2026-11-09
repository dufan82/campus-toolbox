package cn.campuslife.assistant.home

import cn.campuslife.assistant.schedule.Course
import cn.campuslife.assistant.schedule.ScheduleCalendar
import cn.campuslife.assistant.schedule.ScheduleData
import java.time.LocalDate
import java.time.LocalTime

data class HomeLesson(val date: LocalDate,val course: Course)

/** Display-only summary. Never guesses a semester, edits a course or loads online data. */
object HomeSchedulePreview {
    fun next(data: ScheduleData,today: LocalDate,now: LocalTime): HomeLesson? {
        val semester=data.semester ?: return null
        val end=semester.startMonday.plusDays(semester.totalWeeks*7L-1)
        var date=maxOf(today,semester.startMonday)
        while(date<=end) {
            val course=ScheduleCalendar.today(data,date).firstOrNull { course ->
                date!=today || data.periods.getOrNull(course.endPeriod-1)?.end?.let { it>now }!=false
            }
            if(course!=null) return HomeLesson(date,course)
            date=date.plusDays(1)
        }
        return null
    }
}
