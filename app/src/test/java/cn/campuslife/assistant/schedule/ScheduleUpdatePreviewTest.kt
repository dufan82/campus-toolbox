package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ScheduleUpdatePreviewTest {
    private val monday = LocalDate.of(2026, 9, 7)
    private val semester = Semester(monday, 4)
    private val base = ScheduleData(semester)
    private val course = Course("00000000-0000-0000-0000-000000000001", "Math", "A", "R1", 1, 1, 2, 1, 4, WeekPattern.EVERY, null)

    @Test fun comparisonOnlyIncludesTodayAndFutureAndNeverMutatesInput() {
        val local = base.add(course)
        val school = base.add(course.copy(teacher = "B"))
        val result = ScheduleUpdatePreview.compare(local, school, monday.plusWeeks(1))
        assertEquals(3, result.onlyLocal.size)
        assertEquals(3, result.onlySchool.size)
        assertTrue((result.onlyLocal + result.onlySchool).all { !it.date.isBefore(monday.plusWeeks(1)) })
        assertEquals("A", local.courses.single().teacher)
        assertEquals("B", school.courses.single().teacher)
    }

    @Test fun sameOccurrenceMatchesDespiteDifferentIdsAndLocationLinks() {
        val local = base.add(course.copy(placeId = "library"))
        val school = base.add(course.copy(id = "00000000-0000-0000-0000-000000000002"))
        val result = ScheduleUpdatePreview.compare(local, school, monday)
        assertTrue(result.onlyLocal.isEmpty())
        assertTrue(result.onlySchool.isEmpty())
    }

    @Test fun duplicateOccurrencesAreCountedAndOddWeeksAreRespected() {
        val odd = course.copy(pattern = WeekPattern.ODD)
        val local = base.add(odd).add(odd.copy(id = "00000000-0000-0000-0000-000000000002"))
        val result = ScheduleUpdatePreview.compare(local, base.add(odd), monday)
        assertEquals(listOf(monday, monday.plusWeeks(2)), result.onlyLocal.map { it.date })
        assertTrue(result.onlySchool.isEmpty())
    }

    @Test fun emptyAndDifferentSemestersAreComparedByActualDate() {
        val changed = base.copy(semester = Semester(monday.plusWeeks(1), 4)).add(course)
        val result = ScheduleUpdatePreview.compare(ScheduleData(), changed, monday)
        assertEquals(4, result.onlySchool.size)
        assertEquals(monday.plusWeeks(1), result.onlySchool.first().date)
        assertTrue(result.onlyLocal.isEmpty())
        assertTrue(ScheduleUpdatePreview.compare(base.add(course), changed, monday.plusWeeks(6)).onlyLocal.isEmpty())
    }
    @Test fun rejectsInvalidInputInsteadOfProducingPartialPreview() {
        val invalid = base.copy(courses = listOf(course.copy(endWeek = 5)))
        assertThrows(IllegalArgumentException::class.java) {
            ScheduleUpdatePreview.compare(base, invalid, monday)
        }
        assertTrue(base.courses.isEmpty())
    }

    @Test fun dayBoundaryExcludesYesterdayButIncludesToday() {
        val tuesday = course.copy(weekday = 2, startWeek = 1, endWeek = 1)
        val input = base.add(course.copy(endWeek = 1)).add(tuesday.copy(id = "00000000-0000-0000-0000-000000000002"))
        val result = ScheduleUpdatePreview.compare(input, base, monday.plusDays(1))
        assertEquals(listOf(monday.plusDays(1)), result.onlyLocal.map { it.date })
    }
}
