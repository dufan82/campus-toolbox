package cn.campuslife.assistant.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.campuslife.assistant.planner.*
import java.time.Instant
import java.time.ZoneId

class PlannerReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action!=ReminderScheduler.ACTION) return
        val pending=goAsync()
        PlannerRuntime.execute {
            try {
                val request=runCatching {
                    ReminderRequest(requireNotNull(intent.getStringExtra("kind")),requireNotNull(intent.getStringExtra("itemId")),
                        requireNotNull(intent.getStringExtra("revision")),Instant.ofEpochMilli(intent.getLongExtra("at",-1)))
                }.getOrNull() ?: return@execute
                dispatch(context,request)
            } catch(error: Exception) {
                // Corrupt/unreadable data is preserved. Never print a title or the incoming extras.
            } finally { pending.finish() }
        }
    }
    companion object {
        /** Runs on planner-io, shared with CRUD. Exposed for integration checks, never trusts supplied text. */
        fun dispatch(context: Context, request: ReminderRequest): Boolean {
            val store=PlannerStore(context)
            val state=store.read()
            val scheduler=ReminderScheduler(context)
            if(!ReminderRules.canDispatch(state,request,Instant.now(),ZoneId.systemDefault())) {
                scheduler.reconcile(state,state,finished=request)
                return false
            }
            val saved=state.copy(delivered=state.delivered+(request.identity to request.token))
            store.write(saved)
            return try { scheduler.notifySaved(saved,request) }
            finally { scheduler.reconcile(state,saved) }
        }
    }
}

class PlannerRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action !in ACTIONS) return
        val pending=goAsync()
        PlannerRuntime.execute {
            try {
                val state=PlannerStore(context).read()
                ReminderScheduler(context).reconcile(state,state,reset=true)
            } catch(error: Exception) {
                // No map, photo, remote work or private logging from a broadcast.
            } finally { pending.finish() }
        }
    }
    companion object {
        private val ACTIONS=setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
