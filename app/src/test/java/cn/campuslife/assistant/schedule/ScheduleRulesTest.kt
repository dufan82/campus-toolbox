package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ScheduleRulesTest {
    private val monday = LocalDate.of(2026, 9, 7)
    private val semester = Semester(monday, 20)
    private fun course(id: String = "00000000-0000-0000-0000-000000000001") = Course(
        id, "示例课程", "", "", 1, 1, 2, 1, 20, WeekPattern.EVERY, null
    )
    private fun data() = ScheduleData(semester = semester)

    @Test fun datesBeforeStartAndAfterLastSundayAreNotClampedIntoTheSemester() {
        assertEquals(SemesterPhase.BEFORE, ScheduleCalendar.position(semester, monday.minusDays(1)).phase)
        assertNull(ScheduleCalendar.position(semester, monday.minusDays(1)).week)
        assertEquals(1, ScheduleCalendar.position(semester, monday).week)
        assertEquals(20, ScheduleCalendar.position(semester, monday.plusWeeks(20).minusDays(1)).week)
        assertEquals(SemesterPhase.AFTER, ScheduleCalendar.position(semester, monday.plusWeeks(20)).phase)
    }

    @Test fun todayUsesWeekdayWeekRangeAndParityIncludingLastWeek() {
        val odd = course().copy(pattern = WeekPattern.ODD)
        val even = course("00000000-0000-0000-0000-000000000002").copy(pattern = WeekPattern.EVEN)
        val schedule = data().copy(courses = listOf(odd, even)).checked()
        assertEquals(listOf(odd.id), ScheduleCalendar.today(schedule, monday).map { it.id })
        assertEquals(listOf(even.id), ScheduleCalendar.today(schedule, monday.plusWeeks(19)).map { it.id })
        assertTrue(ScheduleCalendar.today(schedule, monday.plusDays(1)).isEmpty())
        assertTrue(ScheduleCalendar.today(schedule, monday.minusWeeks(1)).isEmpty())
        assertTrue(ScheduleCalendar.today(schedule, monday.plusWeeks(20)).isEmpty())
    }

    @Test fun conflictsRequireAnActualSharedWeekAndInclusivePeriodIntersection() {
        val first = course().copy(startWeek = 2, endWeek = 5, pattern = WeekPattern.ODD)
        val other = course("00000000-0000-0000-0000-000000000002").copy(startPeriod = 2, endPeriod = 3)
        assertTrue(ScheduleCalendar.overlaps(first, other))
        assertFalse(ScheduleCalendar.overlaps(first, other.copy(pattern = WeekPattern.EVEN)))
        assertFalse(ScheduleCalendar.overlaps(first, other.copy(startWeek = 4, endWeek = 4)))
        assertFalse(ScheduleCalendar.overlaps(first, other.copy(startPeriod = 3)))
        assertFalse(ScheduleCalendar.overlaps(first, other.copy(weekday = 2)))
        assertFalse(ScheduleCalendar.overlaps(first, other.copy(startWeek = 6)))
    }

    @Test fun bothConflictingCoursesRemainVisibleAndSelfUpdateIsNotAConflict() {
        val first = course()
        val second = course("00000000-0000-0000-0000-000000000002").copy(name = "另一课程")
        val schedule = data().add(first).add(second)
        assertEquals(2, ScheduleCalendar.today(schedule, monday).size)
        assertEquals(listOf(second), ScheduleCalendar.conflicts(schedule, first))
        assertTrue(ScheduleCalendar.conflicts(data().add(first), first.copy(name = "改名")).isEmpty())
    }

    @Test fun crudPreservesStableIdentityAndRejectsMissingOrDuplicateIds() {
        val first = course()
        val added = data().add(first)
        val edited = added.update(first.copy(name = "修改后"))
        assertEquals(first.id, edited.courses.single().id)
        assertEquals("修改后", edited.courses.single().name)
        assertTrue(edited.delete(first.id).courses.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { added.add(first) }
        assertThrows(IllegalArgumentException::class.java) { data().update(first) }
        assertThrows(IllegalArgumentException::class.java) { data().delete(first.id) }
        assertEquals(listOf(first), added.courses)
    }

    @Test fun validationRejectsBadDatesRangesLengthsIdsAndPeriodChangesThatLoseCourses() {
        assertThrows(IllegalArgumentException::class.java) { Semester(monday.plusDays(1), 20).checked() }
        assertThrows(IllegalArgumentException::class.java) { Semester(monday, 0).checked() }
        listOf(course().copy(name = " "), course().copy(name = "x".repeat(81)),
            course().copy(id = "not-uuid"), course().copy(weekday = 8), course().copy(startPeriod = 0),
            course().copy(endPeriod = 25), course().copy(startWeek = 0), course().copy(endWeek = 21),
            course().copy(startWeek = 4, endWeek = 3), course().copy(placeId = "../bad"),
            course().copy(startWeek = 2, endWeek = 2, pattern = WeekPattern.ODD)
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { data().add(invalid) } }
        assertThrows(IllegalArgumentException::class.java) { ScheduleData().add(course()) }
        assertThrows(IllegalArgumentException::class.java) { data().add(course()).copy(periods = listOf(SchedulePeriod("第一节"))).checked() }
        assertThrows(IllegalArgumentException::class.java) { data().add(course()).copy(semester = Semester(monday, 5)).checked() }
    }

    @Test fun customPeriodTimesMustBePairedOrderedAndNeverInventedByDefault() {
        assertTrue(data().periods.all { it.start == null && it.end == null })
        val period = SchedulePeriod("实验课", LocalTime.of(8, 30), LocalTime.of(9, 15))
        assertEquals(period, data().copy(periods = listOf(period)).checked().periods.single())
        assertThrows(IllegalArgumentException::class.java) { data().copy(periods = listOf(period.copy(end = null))).checked() }
        assertThrows(IllegalArgumentException::class.java) { data().copy(periods = listOf(period.copy(end = LocalTime.of(8, 0)))).checked() }
        assertThrows(IllegalArgumentException::class.java) { data().copy(periods = listOf(period, period)).checked() }
    }

    @Test fun unresolvedPlaceNeverChangesOrDeletesTheCourse() {
        val linked = course().copy(placeId = "custom-example")
        val schedule = data().add(linked)
        assertFalse(ScheduleCalendar.canNavigate(linked, setOf("custom-example"), emptySet()))
        assertFalse(ScheduleCalendar.canNavigate(linked, emptySet(), setOf("custom-example")))
        assertTrue(ScheduleCalendar.canNavigate(linked, setOf("custom-example"), setOf("custom-example")))
        assertEquals(linked, schedule.courses.single())
    }
}
