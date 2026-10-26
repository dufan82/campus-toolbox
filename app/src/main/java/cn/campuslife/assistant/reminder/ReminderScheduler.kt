package cn.campuslife.assistant.reminder

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import cn.campuslife.assistant.planner.*
import java.time.Instant
import java.time.ZoneId

class ReminderScheduler(context: Context) {
    private val context=context.applicationContext
    private val manager get()=context.getSystemService(NotificationManager::class.java)
    private val alarms get()=context.getSystemService(AlarmManager::class.java)

    fun ensureChannel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"倒计时",NotificationManager.IMPORTANCE_DEFAULT).apply {
            description="仅在你主动开启某条记录的提醒后使用"
            lockscreenVisibility=Notification.VISIBILITY_PRIVATE
        })
    }
    fun unavailableReason(): String? = notificationBlockedReason() ?: lastError
    private fun notificationBlockedReason(): String? {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            return "通知权限未授予，记录已保存；提醒暂不显示。可在系统设置中开启。"
        if(!manager.areNotificationsEnabled()) return "系统已关闭应用通知，记录仍可正常使用；可在系统设置中开启通知。"
        if(manager.getNotificationChannel(CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE)
            return "“倒计时”通知渠道已关闭，记录仍可正常使用；可在系统设置中开启。"
        return null
    }
    fun reconcile(old: PlannerState, current: PlannerState, reset: Boolean=false, finished: ReminderRequest?=null) {
        val keys=(old.todos.map { "todo/${it.id}" }+old.countdowns.map { "countdown/${it.id}" }+
            current.todos.map { "todo/${it.id}" }+current.countdowns.map { "countdown/${it.id}" }).toSet()
        val existing=keys.filter { pending(it,null,PendingIntent.FLAG_NO_CREATE)!=null }.toSet()
        val plan=ReminderRules.reconcile(old,current,Instant.now(),ZoneId.systemDefault(),existing,reset,finished)
        keys.forEach { key ->
            if(key in plan.cancel) pending(key,null,PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
            val oldRevision=old.todos.find { "todo/${it.id}"==key }?.revision ?: old.countdowns.find { "countdown/${it.id}"==key }?.revision
            val newRevision=current.todos.find { "todo/${it.id}"==key }?.revision ?: current.countdowns.find { "countdown/${it.id}"==key }?.revision
            if(key.startsWith("todo/") || oldRevision!=newRevision) manager.cancel(key,0)
        }
        lastError=null
        val requests=plan.schedule
        if(requests.isEmpty()) return
        ensureChannel()
        if(unavailableReason()!=null) return
        try {
            requests.forEach { request ->
                // Deliberately inexact; no SCHEDULE_EXACT_ALARM permission or service.
                val operation=requireNotNull(pending(request.identity,request,PendingIntent.FLAG_UPDATE_CURRENT))
                try {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,request.at.toEpochMilli(),operation)
                } catch(error: RuntimeException) {
                    // A token created before a failed set() is not evidence of a scheduled alarm.
                    operation.cancel()
                    throw error
                }
            }
        } catch(error: RuntimeException) {
            lastError="系统未能安排提醒。记录已保存，请稍后重新打开倒计时页面检查。"
        }
    }
    private fun pending(identity: String, request: ReminderRequest?, flags: Int): PendingIntent? {
        val intent=Intent(context,PlannerReminderReceiver::class.java).setAction(ACTION)
            .setData(Uri.parse("campus-planner://reminder/$identity"))
        request?.let { intent.putExtra("kind",it.kind).putExtra("itemId",it.itemId)
            .putExtra("revision",it.revision).putExtra("at",it.at.toEpochMilli()) }
        // Consumed tokens must disappear even if the process stops between delivered persistence
        // and reconciliation. The next read can then rebuild the future repeat instead of retaining
        // an orphan. All lookups use the same ONE_SHOT/IMMUTABLE instance flags.
        return PendingIntent.getBroadcast(context,0,intent,flags or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
    }
    fun notifySaved(state: PlannerState, request: ReminderRequest): Boolean {
        if(request.kind!="countdown") return false
        ensureChannel()
        if(notificationBlockedReason()!=null) return false
        val title=state.countdowns.find { it.id==request.itemId }?.title
        if(title==null) return false
        val launch=PendingIntent.getActivity(context,0,Intent(context,PlannerActivity::class.java)
            .setData(Uri.parse("campus-planner://open/${request.identity}"))
            .putExtra("tab",1)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val generic=Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("校园工具箱提醒").setContentText("打开查看倒计时").build()
        val notification=Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("倒计时提醒").setContentText(title)
            .setContentIntent(launch).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(generic).setCategory(Notification.CATEGORY_REMINDER).build()
        return try { manager.notify(request.identity,0,notification); true } catch(error: SecurityException) { false }
    }
    companion object {
        const val CHANNEL="planner-reminders-v1"
        const val ACTION="cn.campuslife.assistant.PLANNER_REMINDER"
        @Volatile private var lastError: String?=null
    }
}
