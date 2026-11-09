package cn.campuslife.assistant.home

import cn.campuslife.assistant.schedule.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class HomeSchedulePreviewTest {
    private val monday=LocalDate.of(2026,11,2)
    private val course=Course("00000000-0000-0000-0000-000000000001","课程","教师","教室",1,1,1,1,2,WeekPattern.EVERY,null)
    private val data=ScheduleData(Semester(monday,2),listOf(SchedulePeriod("第一节",LocalTime.of(8,30),LocalTime.of(9,15))),listOf(course))
    @Test fun emptyDataDoesNotInventLesson() { assertNull(HomeSchedulePreview.next(ScheduleData(),monday,LocalTime.NOON)) }
    @Test fun findsActualTodayAndDoesNotMutate() { assertEquals(HomeLesson(monday,course),HomeSchedulePreview.next(data,monday,LocalTime.of(8,0)));assertEquals(listOf(course),data.courses) }
    @Test fun skipsFinishedLessonAndFindsNextWeek() { assertEquals(monday.plusDays(7),HomeSchedulePreview.next(data,monday,LocalTime.of(10,0))?.date) }
    @Test fun semesterEndHasNoFutureLesson() { assertNull(HomeSchedulePreview.next(data,monday.plusDays(14),LocalTime.NOON)) }
    @Test fun unknownTimesStayVisibleRatherThanGuessedFinished() { assertEquals(monday,HomeSchedulePreview.next(data.copy(periods=listOf(SchedulePeriod("第一节"))),monday,LocalTime.NOON)?.date) }
}
