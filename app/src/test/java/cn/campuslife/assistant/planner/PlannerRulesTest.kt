package cn.campuslife.assistant.planner

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class PlannerRulesTest {
    private val id = "c26c5403-90a7-4d6f-98e3-ed2c4d56e155"
    @Test fun monthlyRepeatKeepsOriginalDayAcrossShortMonths() {
        val anchor = LocalDate.of(2024, 1, 31)
        assertEquals(LocalDate.of(2024, 2, 29), PlannerRules.nextDate(anchor, Repeat.MONTHLY, anchor))
        assertEquals(LocalDate.of(2024, 3, 31), PlannerRules.nextDate(anchor, Repeat.MONTHLY, LocalDate.of(2024, 2, 29)))
        assertEquals(LocalDate.of(2025, 2, 28), PlannerRules.nextDate(anchor, Repeat.MONTHLY, LocalDate.of(2025, 2, 1)))
    }
    @Test fun completingOverdueRepeatSkipsOldOccurrencesAndRetainsIdentity() {
        val todo = TodoItem(id, "Fixture", LocalDate.of(2020, 1, 31), repeat = Repeat.MONTHLY)
        val next = PlannerRules.complete(todo, LocalDate.of(2026, 10, 24))
        assertEquals(id, next.id)
        assertEquals(LocalDate.of(2026, 10, 31), next.dueDate)
        assertEquals(todo.anchorDate, next.anchorDate)
        assertFalse(next.completed)
        assertNotEquals(todo.revision, next.revision)
    }
    @Test fun dailyWeeklyAndNonRecurringCompletion() {
        val start = LocalDate.of(2026, 10, 1)
        assertEquals(LocalDate.of(2026, 10, 25), PlannerRules.nextDate(start, Repeat.DAILY, LocalDate.of(2026, 10, 24)))
        assertEquals(LocalDate.of(2026, 10, 29), PlannerRules.nextDate(start, Repeat.WEEKLY, LocalDate.of(2026, 10, 24)))
        assertTrue(PlannerRules.complete(TodoItem(id, "Fixture"), start).completed)
    }
    @Test fun leapAnniversaryUsesLastDayAndReturnsToLeapDay() {
        val item = CountdownItem(id, "Fixture", LocalDate.of(2024, 2, 29), yearly = true)
        assertEquals(LocalDate.of(2025, 2, 28), PlannerRules.countdownDate(item, LocalDate.of(2025, 2, 1)))
        assertEquals(LocalDate.of(2028, 2, 29), PlannerRules.countdownDate(item, LocalDate.of(2028, 2, 1)))
        assertEquals(0L, PlannerRules.days(item, LocalDate.of(2025, 2, 28)))
        assertEquals(-2L, PlannerRules.days(item.copy(yearly = false), LocalDate.of(2024, 3, 2)))
    }
    @Test fun calendarDaysDoNotDivideMillisecondsAcrossDstOrZones() {
        val now = Instant.parse("2026-03-08T07:30:00Z")
        val item = CountdownItem(id, "Fixture", LocalDate.of(2026, 3, 9))
        assertEquals(1L, PlannerRules.days(item, now.atZone(ZoneId.of("America/New_York")).toLocalDate()))
        assertEquals(2L, PlannerRules.days(item, now.atZone(ZoneId.of("Pacific/Honolulu")).toLocalDate()))
    }
    @Test fun malformedModelInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TodoItem("bad", "Fixture") }
        assertThrows(IllegalArgumentException::class.java) { TodoItem(id, " ") }
        assertThrows(IllegalArgumentException::class.java) { TodoItem(id, "a".repeat(121)) }
        assertThrows(IllegalArgumentException::class.java) { TodoItem(id, "Fixture", repeat = Repeat.DAILY) }
        assertThrows(IllegalArgumentException::class.java) { TodoItem(id, "Fixture", reminder = true) }
        assertThrows(IllegalArgumentException::class.java) { CountdownItem(id, "Fixture", LocalDate.of(1800, 1, 1)) }
        assertThrows(IllegalArgumentException::class.java) { PlannerState(version = 2) }
        assertThrows(IllegalArgumentException::class.java) { PlannerState(todos = listOf(TodoItem(id,"A"), TodoItem(id,"B"))) }
    }
    @Test fun recurringDueDateMustMatchItsOriginalCalendarRule() {
        assertThrows(IllegalArgumentException::class.java) {
            TodoItem(id,"Fixture",LocalDate.of(2026,3,28),repeat=Repeat.MONTHLY,anchorDate=LocalDate.of(2026,1,31))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TodoItem(id,"Fixture",LocalDate.of(2026,10,25),repeat=Repeat.WEEKLY,anchorDate=LocalDate.of(2026,10,1))
        }
    }
}
