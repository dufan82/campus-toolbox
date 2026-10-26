package cn.campuslife.assistant.reminder

import cn.campuslife.assistant.planner.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class RetiredTodoReminderTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-24T00:00:00Z")
    private val todoId = "c26c5403-90a7-4d6f-98e3-ed2c4d56e155"
    private val countdown = CountdownItem("c26c5403-90a7-4d6f-98e3-ed2c4d56e156", "Date fixture",
        LocalDate.of(2026,10,24), reminder=true)
    private fun todo(repeat: Repeat) = TodoItem(todoId,"Legacy fixture",LocalDate.of(2026,10,24),
        LocalTime.of(9,0),repeat=repeat,reminder=true)

    @Test fun legacyTodosNeverCreateFutureAlarmsButCountdownStillDoes() {
        Repeat.entries.forEach { repeat ->
            val state = PlannerState(todos=listOf(todo(repeat)),countdowns=listOf(countdown))
            val requests = ReminderRules.upcoming(state,now,zone)
            assertEquals(listOf("countdown/${countdown.id}"),requests.map { it.identity })
            assertEquals(Instant.parse("2026-10-24T01:00:00Z"),requests.single().at)
        }
    }

    @Test fun oldPendingTodoDeliveryIsRejectedWithoutChangingLegacyRecords() {
        Repeat.entries.forEach { repeat ->
            val item = todo(repeat)
            val state = PlannerState(todos=listOf(item),countdowns=listOf(countdown))
            val old = ReminderRequest("todo",item.id,item.revision,Instant.parse("2026-10-24T01:00:00Z"))
            assertFalse(ReminderRules.canDispatch(state,old,old.at.plusSeconds(60),zone))
            assertEquals(listOf(item),state.todos)
            assertTrue(state.delivered.isEmpty())
        }
    }

    @Test fun loadCancelsOnlyLegacyTodoPendingAndRetainsDueCountdown() {
        val state = PlannerState(todos=listOf(todo(Repeat.DAILY)),countdowns=listOf(countdown))
        val todoKey = "todo/$todoId"
        val countdownKey = "countdown/${countdown.id}"
        val plan = ReminderRules.reconcile(state,state,Instant.parse("2026-10-24T01:01:00Z"),zone,
            setOf(todoKey,countdownKey))
        assertEquals(setOf(todoKey),plan.cancel)
        assertTrue(plan.schedule.isEmpty())
    }

    @Test fun bootAndUpgradeResetRebuildOnlyCountdownWithoutDeletingTodos() {
        val state = PlannerState(todos=listOf(todo(Repeat.WEEKLY)),countdowns=listOf(countdown))
        val plan = ReminderRules.reconcile(state,state,now,zone,setOf("todo/$todoId"),reset=true)
        assertEquals(setOf("todo/$todoId"),plan.cancel)
        assertEquals(listOf("countdown/${countdown.id}"),plan.schedule.map { it.identity })
        assertEquals(1,state.todos.size)
    }
}
