package cn.campuslife.assistant.reminder

import cn.campuslife.assistant.planner.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ReminderRulesTest {
    private val id = "c26c5403-90a7-4d6f-98e3-ed2c4d56e155"
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-24T00:00:00Z")
    private fun countdown() = CountdownItem(id, "Fixture", LocalDate.of(2026,10,24), reminder=true)
    @Test fun disabledAndPastOneShotHaveNoAlarm() {
        assertTrue(ReminderRules.upcoming(PlannerState(countdowns=listOf(countdown().copy(reminder=false))), now, zone).isEmpty())
        assertTrue(ReminderRules.upcoming(PlannerState(countdowns=listOf(countdown())), now.plusSeconds(7200), zone).isEmpty())
    }
    @Test fun recurringAnniversarySkipsYearsOfOldDates() {
        val item = countdown().copy(date=LocalDate.of(2020,10,31), yearly=true)
        val alarm = ReminderRules.upcoming(PlannerState(countdowns=listOf(item)), now, zone).single()
        assertEquals(Instant.parse("2026-10-31T01:00:00Z"), alarm.at)
    }
    @Test fun identitiesAreStableUniqueAndCancelDoesNotDependOnRevision() {
        val original = ReminderRules.upcoming(PlannerState(countdowns=listOf(countdown())), now, zone).single()
        val changed = ReminderRules.upcoming(PlannerState(countdowns=listOf(countdown().copy(revision=PlannerRules.newId()))), now, zone).single()
        assertEquals(original.identity, changed.identity)
        assertNotEquals(original.token, changed.token)
        assertNotEquals(original.identity, ReminderRules.identity("todo", id))
    }
    @Test fun staleDeletedChangedEarlyAndAlreadyDeliveredRequestsCannotNotify() {
        val state = PlannerState(countdowns=listOf(countdown()))
        val alarm = ReminderRules.upcoming(state, now, zone).single()
        assertFalse(ReminderRules.canDispatch(state, alarm, now, zone))
        assertTrue(ReminderRules.canDispatch(state, alarm, alarm.at.plusSeconds(60), zone))
        assertFalse(ReminderRules.canDispatch(PlannerState(), alarm, alarm.at, zone))
        assertFalse(ReminderRules.canDispatch(state.copy(countdowns=listOf(countdown().copy(reminder=false))), alarm, alarm.at, zone))
        assertFalse(ReminderRules.canDispatch(state.copy(countdowns=listOf(countdown().copy(revision=PlannerRules.newId()))), alarm, alarm.at, zone))
        assertFalse(ReminderRules.canDispatch(state.copy(delivered=mapOf(alarm.identity to alarm.token)), alarm, alarm.at, zone))
        assertFalse(ReminderRules.canDispatch(state, alarm, alarm.at.plusSeconds(86401), zone))
        assertFalse(ReminderRules.canDispatch(state, alarm, alarm.at, ZoneId.of("UTC")))
    }
    @Test fun leapAnniversaryAndDstUseLocalWallTime() {
        val item = CountdownItem(id,"Fixture",LocalDate.of(2024,2,29),yearly=true,reminder=true)
        val alarm = ReminderRules.upcoming(PlannerState(countdowns=listOf(item)), Instant.parse("2025-02-01T00:00:00Z"), zone).single()
        assertEquals(Instant.parse("2025-02-28T01:00:00Z"),alarm.at)
        val dst = countdown().copy(date=LocalDate.of(2026,3,8),reminderTime=LocalTime.of(2,30))
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), ReminderRules.upcoming(PlannerState(countdowns=listOf(dst)),Instant.parse("2026-03-08T00:00:00Z"),ZoneId.of("America/New_York")).single().at)
    }
    @Test fun clockRollbackAfterDeliveryStillSchedulesNextRepeatWithoutDuplicate() {
        val item=countdown().copy(yearly=true)
        val delivered="${item.revision}:1792803600000"
        val state=PlannerState(countdowns=listOf(item),delivered=mapOf("countdown/$id" to delivered))
        val request=ReminderRules.upcoming(state,now,zone).single()
        assertEquals(Instant.parse("2027-10-24T01:00:00Z"),request.at)
    }
    @Test fun rollbackCannotReplayAnEarlierOccurrenceAfterALaterOneWasDelivered() {
        val item=countdown().copy(yearly=true)
        val previous=ReminderRequest("countdown",id,item.revision,Instant.parse("2026-10-24T01:00:00Z"))
        val state=PlannerState(countdowns=listOf(item),delivered=mapOf("countdown/$id" to "${item.revision}:1792890000000"))
        assertFalse(ReminderRules.canDispatch(state,previous,Instant.parse("2026-10-24T01:01:00Z"),zone))
    }
    @Test fun ordinaryReadRetainsJustDuePendingOneShotAndRepeat() {
        for(yearly in listOf(false,true)) {
            val item=countdown().copy(yearly=yearly)
            val state=PlannerState(countdowns=listOf(item))
            val request=ReminderRules.upcoming(state,now,zone).single()
            val delayed=request.at.plusSeconds(60)
            assertTrue(ReminderRules.canDispatch(state,request,delayed,zone))
            val plan=ReminderRules.reconcile(state,state,delayed,zone,setOf(request.identity))
            assertFalse(plan.cancel.contains(request.identity))
            assertTrue(plan.schedule.none { it.identity==request.identity })
        }
    }
    @Test fun unrelatedEditKeepsDueCountdownButChangeDisableAndDeleteCancelOriginal() {
        val countdown=CountdownItem(PlannerRules.newId(),"Fixture",LocalDate.of(2026,10,24),reminder=true)
        val unrelated=TodoItem(id,"Legacy fixture")
        val state=PlannerState(todos=listOf(unrelated),countdowns=listOf(countdown))
        val request=ReminderRules.upcoming(state,now,zone).single()
        val edited=state.copy(todos=listOf(unrelated.copy(title="Edited",revision=PlannerRules.newId())))
        val delayed=request.at.plusSeconds(60)
        assertFalse(ReminderRules.reconcile(state,edited,delayed,zone,setOf(request.identity)).cancel.contains(request.identity))
        val changed=state.copy(countdowns=listOf(countdown.copy(date=LocalDate.of(2026,10,25),revision=PlannerRules.newId())))
        val changePlan=ReminderRules.reconcile(state,changed,delayed,zone,setOf(request.identity))
        assertTrue(changePlan.cancel.contains(request.identity))
        assertEquals(Instant.parse("2026-10-25T01:00:00Z"),changePlan.schedule.single().at)
        for(next in listOf(state.without(countdown.id),state.copy(countdowns=listOf(countdown.copy(reminder=false))))) {
            val plan=ReminderRules.reconcile(state,next,delayed,zone,setOf(request.identity))
            assertTrue(plan.cancel.contains(request.identity)); assertTrue(plan.schedule.isEmpty())
        }
    }
    @Test fun deliveryAdvancesOnlyItsOwnRepeatAndKeepsAnotherDueAlarm() {
        val repeating=countdown().copy(yearly=true)
        val countdown=CountdownItem(PlannerRules.newId(),"Fixture",LocalDate.of(2026,10,24),reminder=true)
        val before=PlannerState(countdowns=listOf(repeating,countdown))
        val requests=ReminderRules.upcoming(before,now,zone)
        val fired=requests.single { it.itemId==id }
        val waiting=requests.single { it.itemId==countdown.id }
        val saved=before.copy(delivered=mapOf(fired.identity to fired.token))
        val plan=ReminderRules.reconcile(before,saved,fired.at.plusSeconds(60),zone,requests.map { it.identity }.toSet())
        assertEquals(setOf(fired.identity),plan.cancel)
        assertFalse(plan.cancel.contains(waiting.identity))
        assertEquals(Instant.parse("2027-10-24T01:00:00Z"),plan.schedule.single().at)
    }
    @Test fun systemResetOrMissingPendingRearmsFutureWithoutReplayingOldPeriod() {
        val item=countdown().copy(yearly=true)
        val state=PlannerState(countdowns=listOf(item))
        val key="countdown/$id"
        val delayed=Instant.parse("2026-10-24T01:01:00Z")
        val restored=ReminderRules.reconcile(state,state,delayed,zone,setOf(key),reset=true)
        assertEquals(setOf(key),restored.cancel)
        assertEquals(Instant.parse("2027-10-24T01:00:00Z"),restored.schedule.single().at)
        val missing=ReminderRules.reconcile(state,state,delayed,zone,emptySet())
        assertEquals(Instant.parse("2027-10-24T01:00:00Z"),missing.schedule.single().at)
        val before=ReminderRules.reconcile(state,state,now,zone,emptySet(),reset=true)
        assertEquals(Instant.parse("2026-10-24T01:00:00Z"),before.schedule.single().at)
    }
    @Test fun expiredUndeliveredBroadcastReleasesItsStalePendingAndArmsFuture() {
        val state=PlannerState(countdowns=listOf(countdown().copy(yearly=true)))
        val request=ReminderRules.upcoming(state,now,zone).single()
        val later=Instant.parse("2026-10-25T01:01:00Z")
        val plan=ReminderRules.reconcile(state,state,later,zone,setOf(request.identity),finished=request)
        assertEquals(setOf(request.identity),plan.cancel)
        assertEquals(Instant.parse("2027-10-24T01:00:00Z"),plan.schedule.single().at)
    }
    @Test fun consumedPendingDoesNotBlockNextRepeatAndNextPendingCanBeRetained() {
        val before=PlannerState(countdowns=listOf(countdown().copy(yearly=true)))
        val fired=ReminderRules.upcoming(before,now,zone).single()
        val saved=before.copy(delivered=mapOf(fired.identity to fired.token))
        val delayed=fired.at.plusSeconds(60)
        val rebuilt=ReminderRules.reconcile(saved,saved,delayed,zone,emptySet())
        assertEquals(Instant.parse("2027-10-24T01:00:00Z"),rebuilt.schedule.single().at)
        val retained=ReminderRules.reconcile(saved,saved,delayed,zone,setOf(fired.identity))
        assertTrue(retained.cancel.isEmpty()); assertTrue(retained.schedule.isEmpty())
    }
    @Test fun duplicateOrChangedOldBroadcastDoesNotCancelNewFuturePending() {
        val before=PlannerState(countdowns=listOf(countdown().copy(yearly=true)))
        val fired=ReminderRules.upcoming(before,now,zone).single()
        val saved=before.copy(delivered=mapOf(fired.identity to fired.token))
        val later=fired.at.plusSeconds(90000)
        val duplicate=ReminderRules.reconcile(saved,saved,later,zone,setOf(fired.identity),finished=fired)
        assertTrue(duplicate.cancel.isEmpty()); assertTrue(duplicate.schedule.isEmpty())
        val edited=saved.copy(countdowns=listOf(saved.countdowns.single().copy(revision=PlannerRules.newId())))
        val stale=ReminderRules.reconcile(edited,edited,later,zone,setOf(fired.identity),finished=fired)
        assertTrue(stale.cancel.isEmpty()); assertTrue(stale.schedule.isEmpty())
    }
}
