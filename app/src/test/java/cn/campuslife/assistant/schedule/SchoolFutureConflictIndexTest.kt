package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class SchoolFutureConflictIndexTest {
    @Test fun conflictDecisionsMatchDirectIntervalAndWeekCalculation() {
        val random = Random(7)
        val start = LocalDate.of(2026, 9, 7)
        val semester = Semester(start, 60)
        fun course(index: Int, name: String): Course {
            val first = random.nextInt(1, 25)
            val last = random.nextInt(first, 25)
            val week = random.nextInt(1, 61)
            return Course("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}", name, "", "",
                random.nextInt(1, 8), first, last, week, random.nextInt(week, 61), WeekPattern.entries.random(random), null)
        }
        val periods = (1..24).map { SchedulePeriod("P$it") }
        repeat(20) {
            val local = ScheduleData(semester, periods, (1..200).map { course(it, "Local $it") }.filter { c -> (c.startWeek..c.endWeek).any(c::occurs) }).checked()
            val school = ScheduleData(semester, periods, (1001..1100).map { course(it, "School $it") }.filter { c -> (c.startWeek..c.endWeek).any(c::occurs) }).checked()
            val expected = school.courses.sumOf { c -> (c.startWeek..c.endWeek).count { w -> c.occurs(w) &&
                local.courses.any { other -> other.weekday == c.weekday && other.occurs(w) && other.startPeriod <= c.endPeriod && c.startPeriod <= other.endPeriod } } }
            try {
                assertEquals(expected, SchoolFutureMerge.plan(local, school, emptyMap(), emptySet(), start).blockedOccurrences)
            } catch (e: IllegalArgumentException) {
                assertTrue("Only the 500-course cap can reject a plan: ${e.message}", e.message.orEmpty().contains("500"))
            }
        }
    }
    @Test fun allThirtyThousandOverlapsAreCountedAndLeaveLocalUnchanged() {
        val semester = Semester(LocalDate.of(2026, 9, 7), 60)
        fun make(index: Int) = Course("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}", "C$index", "", "", 7, 12, 12, 1, 60, WeekPattern.EVERY, null)
        val local = ScheduleData(semester, courses = (1..500).map(::make))
        val school = local.copy(courses = (1001..1500).map(::make))
        val plan = SchoolFutureMerge.plan(local, school, emptyMap(), emptySet(), semester.startMonday)
        assertEquals(30000, plan.blockedOccurrences)
        assertEquals(local, plan.data)
    }
}
