package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test

class SchoolImportReminderTest {
    @Test fun reminderRequiresARecordedSuccessfulImportAndSevenFullDays() {
        val week = 7L * 24 * 60 * 60 * 1000
        val first = 2_000_000_000L
        assertFalse(SchoolImportReminder.isDue(null, first + week))
        assertFalse(SchoolImportReminder.isDue(first, first + week - 1))
        assertTrue(SchoolImportReminder.isDue(first, first + week))
        assertTrue(SchoolImportReminder.isDue(first, first + 2 * week))
    }
    @Test fun invalidOrFutureTimestampsNeverCauseAFalseReminder() {
        assertFalse(SchoolImportReminder.isDue(0, 100))
        assertFalse(SchoolImportReminder.isDue(-1, Long.MAX_VALUE))
        assertFalse(SchoolImportReminder.isDue(100, 99))
    }
    @Test fun existingTimetableWithoutTimestampSuggestsCheckingButNotSevenDays() {
        assertTrue(SchoolImportReminder.needsInitialCheck(null, true))
        assertTrue(SchoolImportReminder.needsInitialCheck(0, true))
        assertFalse(SchoolImportReminder.needsInitialCheck(null, false))
        assertFalse(SchoolImportReminder.needsInitialCheck(1234, true))
        assertFalse(SchoolImportReminder.isDue(null, 1_000_000_000))
    }
    @Test fun partiallyBlockedUpdateMustNotCountAsFullyChecked() {
        assertFalse(SchoolImportReminder.mayRecordFutureSuccess(1))
        assertTrue(SchoolImportReminder.mayRecordFutureSuccess(0))
    }
}
