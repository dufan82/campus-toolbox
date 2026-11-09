package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SchoolFutureMergeTest {
    private val monday = LocalDate.of(2026, 9, 7)
    private val base = ScheduleData(Semester(monday, 6))
    private val original = Course("00000000-0000-0000-0000-000000000001", "Old", "", "", 1, 1, 2, 1, 6, WeekPattern.EVERY, null)
    private val school = original.copy(id = "00000000-0000-0000-0000-000000000002", name = "New")
    private fun occurrences(data: ScheduleData) = (1..6).flatMap { w -> ScheduleCalendar.week(data, w).map { w to it.name } }

    @Test fun splitsAtTodayAndPreservesPastWhileUpdatingTodayAndFuture() {
        val local = base.add(original); val incoming = base.add(school)
        val plan = SchoolFutureMerge.plan(local, incoming, mapOf(original.id to original), emptySet(), monday.plusWeeks(2))
        assertEquals(listOf(1 to "Old", 2 to "Old", 3 to "New", 4 to "New", 5 to "New", 6 to "New"), occurrences(plan.data))
        assertEquals(1, plan.managed.size)
        assertEquals(0, plan.blockedOccurrences)
        assertEquals(local, base.add(original))
    }
    @Test fun defaultsToProtectAndReportsOverlapsRatherThanDiscardingManualCourses() {
        val local = base.add(original)
        val plan = SchoolFutureMerge.plan(local, base.add(school), emptyMap(), emptySet(), monday.plusWeeks(2))
        assertEquals(local, plan.data)
        assertEquals(4, plan.blockedOccurrences)
        assertTrue(plan.managed.isEmpty())
    }
    @Test fun selectedLegacyIsOnlyChangedByExplicitSelection() {
        val local = base.add(original)
        val plan = SchoolFutureMerge.plan(local, base.add(school), emptyMap(), setOf(original.id), monday.plusWeeks(2))
        assertEquals(listOf(1 to "Old", 2 to "Old", 3 to "New", 4 to "New", 5 to "New", 6 to "New"), occurrences(plan.data))
    }
    @Test fun editedOrDeletedImportedRecordsBlockUnsafeRecreation() {
        val edited = original.copy(teacher = "user edited", weekday = 2)
        val local = base.add(edited)
        assertThrows(IllegalArgumentException::class.java) { SchoolFutureMerge.plan(local, base.add(school), mapOf(original.id to original), emptySet(), monday) }
        assertThrows(IllegalArgumentException::class.java) { SchoolFutureMerge.plan(local, base.add(school), emptyMap(), emptySet(), monday, setOf(original.id)) }
        assertThrows(IllegalArgumentException::class.java) { SchoolFutureMerge.plan(base, base.add(school), emptyMap(), emptySet(), monday, setOf(original.id)) }
        assertEquals(edited, local.courses.single())
    }
    @Test fun oddEvenAndWeekdayBoundaryAreExact() {
        val tuesday = original.copy(weekday = 2, pattern = WeekPattern.ODD)
        val newTuesday = school.copy(weekday = 2, pattern = WeekPattern.EVEN)
        val local = base.add(tuesday)
        val plan = SchoolFutureMerge.plan(local, base.add(newTuesday), mapOf(tuesday.id to tuesday), emptySet(), monday.plusWeeks(2))
        assertEquals(listOf(1 to "Old", 4 to "New", 6 to "New"), occurrences(plan.data))
        val afterTuesday = SchoolFutureMerge.plan(local, base.add(newTuesday), mapOf(tuesday.id to tuesday), emptySet(), monday.plusWeeks(2).plusDays(2))
        assertEquals(listOf(1 to "Old", 3 to "Old", 4 to "New", 6 to "New"), occurrences(afterTuesday.data))
    }
    @Test fun rejectsDifferentSemesterPeriodsUnknownSelectionAndCourseOverflow() {
        assertThrows(IllegalArgumentException::class.java) { SchoolFutureMerge.plan(base, base.copy(semester = Semester(monday.plusWeeks(1), 6)), emptyMap(), emptySet(), monday) }
        assertThrows(IllegalArgumentException::class.java) { SchoolFutureMerge.plan(base, base.copy(periods = base.periods.dropLast(1)), emptyMap(), emptySet(), monday) }
        assertThrows(IllegalArgumentException::class.java) { SchoolFutureMerge.plan(base, base, emptyMap(), setOf(original.id), monday) }
        val many = (1..500).map { i -> original.copy(id = "00000000-0000-0000-0000-${i.toString().padStart(12, '0')}", name = "Local $i", weekday = 2) }
        assertThrows(IllegalArgumentException::class.java) { SchoolFutureMerge.plan(base.copy(courses = many), base.add(school), emptyMap(), emptySet(), monday) }
    }
    @Test fun repeatedWeeklyUpdatesRetainPastOccurrencesAndUniqueStableIds() {
        val first = SchoolFutureMerge.plan(base.add(original), base.add(school), mapOf(original.id to original), emptySet(), monday.plusWeeks(1))
        val second = SchoolFutureMerge.plan(first.data, base.add(school), first.managed, emptySet(), monday.plusWeeks(2))
        assertEquals(listOf(1 to "Old", 2 to "New", 3 to "New", 4 to "New", 5 to "New", 6 to "New"), occurrences(second.data))
        assertEquals(second.data.courses.size, second.data.courses.map { it.id }.distinct().size)
        assertEquals(original.id, second.data.courses.single { it.name == "Old" }.id)
    }
    @Test fun collisionsAreRejectedNotOverwritten() {
        val local = base.add(original.copy(id = school.id, weekday = 2))
        assertThrows(IllegalArgumentException::class.java) { SchoolFutureMerge.plan(local, base.add(school), emptyMap(), emptySet(), monday) }
    }
}
