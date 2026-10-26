package cn.campuslife.assistant.reminder

import cn.campuslife.assistant.planner.*
import java.time.*

data class ReminderRequest(val kind: String, val itemId: String, val revision: String, val at: Instant) {
    val identity get()=ReminderRules.identity(kind,itemId)
    val token get()="$revision:${at.toEpochMilli()}"
}

data class ReminderPlan(val cancel: Set<String>,val schedule: List<ReminderRequest>)

object ReminderRules {
    /** Ordinary loads retain unchanged pending tokens, including an inexact alarm awaiting delivery.
     * System resets deliberately rebuild only future occurrences; delivery changes release only that item. */
    fun reconcile(old: PlannerState,current: PlannerState,now: Instant,zone: ZoneId,
                  existing: Set<String>,reset: Boolean=false,finished: ReminderRequest?=null): ReminderPlan {
        fun items(state: PlannerState): Map<String,Any> = buildMap {
            state.todos.forEach { put(identity("todo",it.id),it) }
            state.countdowns.forEach { put(identity("countdown",it.id),it) }
        }
        val before=items(old); val after=items(current)
        // Legacy todos stay readable, but can never retain or create a pending alarm.
        val active=current.countdowns.filter { it.reminder }.map { identity("countdown",it.id) }.toSet()
        val expired=finished?.takeIf { request ->
            Duration.between(request.at,now)>Duration.ofDays(1) &&
                runCatching { canDispatch(current,request,request.at,zone) }.getOrDefault(false)
        }?.identity
        val retained=existing.filter { key -> !reset && key in active && key!=expired && before[key]==after[key] &&
            old.delivered[key]==current.delivered[key] }.toSet()
        return ReminderPlan(existing.intersect(before.keys+after.keys)-retained,
            upcoming(current,now,zone).filterNot { it.identity in retained })
    }
    fun identity(kind: String, id: String): String {
        require(kind=="todo" || kind=="countdown")
        require(runCatching { java.util.UUID.fromString(id).toString()==id }.getOrDefault(false))
        return "$kind/$id"
    }
    fun upcoming(state: PlannerState, now: Instant, zone: ZoneId): List<ReminderRequest> {
        val requests=ArrayList<ReminderRequest>()
        state.countdowns.filter { it.reminder }.forEach { item ->
            val delivered=lastDelivery(state,"countdown",item.id,item.revision)
            val after=maxOf(now,delivered ?: now)
            val today=after.atZone(zone).toLocalDate()
            var date=PlannerRules.countdownDate(item,today)
            var at=date.atTime(item.reminderTime).atZone(zone).toInstant()
            if(at<=after && item.yearly) {
                date=PlannerRules.anniversary(item.date,date.year+1)
                at=date.atTime(item.reminderTime).atZone(zone).toInstant()
            }
            val request=ReminderRequest("countdown",item.id,item.revision,at)
            if(at>after && date.year<=2199) requests+=request
        }
        return requests
    }
    private fun lastDelivery(state: PlannerState,kind: String,id: String,revision: String): Instant? {
        val token=state.delivered[identity(kind,id)] ?: return null
        if(!token.startsWith("$revision:")) return null
        return token.substringAfter(':').toLongOrNull()?.let(Instant::ofEpochMilli)
    }
    /** Intent contains identity only; title/content always come from the validated saved document. */
    fun canDispatch(state: PlannerState, request: ReminderRequest, now: Instant, zone: ZoneId): Boolean {
        if(request.kind!="countdown") return false
        if(request.at>now || Duration.between(request.at,now)>Duration.ofDays(1)) return false
        if(lastDelivery(state,request.kind,request.itemId,request.revision)?.let { request.at<=it }==true) return false
        val date=request.at.atZone(zone).toLocalDate()
        val item=state.countdowns.find { it.id==request.itemId } ?: return false
        if(!item.reminder || item.revision!=request.revision || date<item.date) return false
        return (if(item.yearly) date==PlannerRules.anniversary(item.date,date.year) else date==item.date) &&
            date.atTime(item.reminderTime).atZone(zone).toInstant()==request.at
    }
}
