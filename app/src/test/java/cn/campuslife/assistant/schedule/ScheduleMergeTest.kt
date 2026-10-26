package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ScheduleMergeTest {
    private val empty = ScheduleData(Semester(LocalDate.of(2026, 9, 7), 20))
    private val first = Course("00000000-0000-0000-0000-000000000001", "First", "", "", 1, 1, 2, 1, 20, WeekPattern.EVERY, null)
    private val second = first.copy(id = "00000000-0000-0000-0000-000000000002", name = "Second", weekday = 2)

    @Test fun twoStaleSnapshotsAddingIndependentCoursesKeepBoth() {
        val savedByB = ScheduleMerge.merge(empty, empty.add(second), empty)
        val savedByA = ScheduleMerge.merge(empty, empty.add(first), savedByB)
        assertEquals(setOf(first.id, second.id), savedByA.courses.map { it.id }.toSet())
        assertEquals(first, savedByA.courses.single { it.id == first.id })
        assertEquals(second, savedByA.courses.single { it.id == second.id })
    }

    @Test fun narrowCourseBaselinePreservesEveryUnrelatedLatestRecord() {
        val before = empty.add(first)
        val latest = before.add(second)
        val updated = ScheduleMerge.merge(before, before.update(first.copy(teacher = "Changed locally")), latest)
        assertEquals(2, updated.courses.size)
        assertEquals(second, updated.courses.single { it.id == second.id })
        assertEquals("Changed locally", updated.courses.single { it.id == first.id }.teacher)
        val removed = ScheduleMerge.merge(before, before.delete(first.id), latest)
        assertEquals(listOf(second), removed.courses)
    }

    @Test fun settingsOnlyBaselinePreservesCoursesAddedBeforeAndAfterTheFormOpened() {
        val latest = empty.add(first).add(second)
        val semesterEdit = empty.copy(semester = Semester(LocalDate.of(2026, 9, 7), 30))
        val merged = ScheduleMerge.merge(empty, semesterEdit, latest)
        assertEquals(30, merged.semester!!.totalWeeks)
        assertEquals(listOf(first, second), merged.courses)
        val periodEdit = empty.copy(periods = empty.periods.mapIndexed { index, period -> if (index == 0) period.copy(label = "Updated label") else period })
        val periodMerged = ScheduleMerge.merge(empty, periodEdit, latest)
        assertEquals("Updated label", periodMerged.periods.first().label)
        assertEquals(listOf(first, second), periodMerged.courses)
    }

    @Test fun unrelatedAdditionDoesNotRestoreADeletedCourseOrUndoAnEdit() {
        val before = empty.add(first).add(second)
        val third = first.copy(id = "00000000-0000-0000-0000-000000000003", name = "Third")
        val latest = before.delete(first.id).update(second.copy(name = "Changed remotely"))
        val merged = ScheduleMerge.merge(before, before.add(third), latest)
        assertEquals(setOf(second.id, third.id), merged.courses.map { it.id }.toSet())
        assertEquals("Changed remotely", merged.courses.single { it.id == second.id }.name)
    }

    @Test fun updatesToDifferentIdsCommuteAndDoNotLoseLatestSettings() {
        val before = empty.add(first).add(second)
        val latest = before.update(second.copy(classroom = "New classroom")).copy(semester = Semester(LocalDate.of(2026, 9, 14), 20))
        val merged = ScheduleMerge.merge(before, before.update(first.copy(teacher = "New teacher")), latest)
        assertEquals("New teacher", merged.courses.single { it.id == first.id }.teacher)
        assertEquals("New classroom", merged.courses.single { it.id == second.id }.classroom)
        assertEquals(LocalDate.of(2026, 9, 14), merged.semester!!.startMonday)
    }

    @Test fun sameIdEditsAndEditDeleteRacesAreRejectedWithoutMutatingLatest() {
        val before = empty.add(first)
        val proposed = before.update(first.copy(name = "Local edit"))
        val changed = before.update(first.copy(name = "Remote edit"))
        assertThrows(ScheduleConflictException::class.java) { ScheduleMerge.merge(before, proposed, changed) }
        assertThrows(ScheduleConflictException::class.java) { ScheduleMerge.merge(before, proposed, before.delete(first.id)) }
        assertThrows(ScheduleConflictException::class.java) { ScheduleMerge.merge(before, before.delete(first.id), changed) }
        assertEquals("Remote edit", changed.courses.single().name)
    }

    @Test fun duplicateIdentityAddConflictsButExactRetryIsIdempotent() {
        val proposed = empty.add(first)
        val latest = empty.add(first.copy(name = "Another screen's record"))
        assertThrows(ScheduleConflictException::class.java) { ScheduleMerge.merge(empty, proposed, latest) }
        assertEquals(proposed, ScheduleMerge.merge(empty, proposed, proposed))
        assertEquals(empty, ScheduleMerge.merge(proposed, empty, empty))
    }

    @Test fun unchangedSettingsNeverRevertAnotherScreensUpdates() {
        val latest = empty.copy(semester = Semester(LocalDate.of(2026, 9, 14), 30), periods = empty.periods + SchedulePeriod("Extra"))
        val merged = ScheduleMerge.merge(empty, empty.add(first), latest)
        assertEquals(30, merged.semester!!.totalWeeks)
        assertEquals(13, merged.periods.size)
    }

    @Test fun competingSemesterAndPeriodEditsAreRejected() {
        assertThrows(ScheduleConflictException::class.java) {
            ScheduleMerge.merge(empty, empty.copy(semester = Semester(LocalDate.of(2026, 9, 7), 15)), empty.copy(semester = Semester(LocalDate.of(2026, 9, 7), 25)))
        }
        assertThrows(ScheduleConflictException::class.java) {
            ScheduleMerge.merge(empty, empty.copy(periods = empty.periods.dropLast(1)), empty.copy(periods = empty.periods + SchedulePeriod("Extra")))
        }
    }

    @Test fun settingsAreValidatedAgainstCoursesAddedByAnotherScreen() {
        val latest = empty.add(first)
        val error = assertThrows(ScheduleConflictException::class.java) {
            ScheduleMerge.merge(empty, empty.copy(semester = Semester(LocalDate.of(2026, 9, 7), 5)), latest)
        }
        assertEquals(latest, error.latest)
        assertThrows(ScheduleConflictException::class.java) {
            ScheduleMerge.merge(empty, empty.copy(periods = listOf(SchedulePeriod("Only one"))), latest)
        }
    }

    @Test fun staleCourseIsValidatedAgainstLatestSemesterAndPeriodLimits() {
        val proposed = empty.add(first)
        assertThrows(ScheduleConflictException::class.java) {
            ScheduleMerge.merge(empty, proposed, empty.copy(semester = Semester(LocalDate.of(2026, 9, 7), 5)))
        }
        assertThrows(ScheduleConflictException::class.java) {
            ScheduleMerge.merge(empty, proposed, empty.copy(periods = listOf(SchedulePeriod("Only one"))))
        }
    }
}
