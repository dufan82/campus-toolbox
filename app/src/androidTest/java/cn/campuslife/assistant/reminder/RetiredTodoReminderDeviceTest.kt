package cn.campuslife.assistant.reminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.planner.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Isolated files and fresh alarm identities; never reads or replaces the user's planner. */
class RetiredTodoReminderDeviceTest {
    private fun isolated(): Context {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(base.cacheDir,"retired-todo-${PlannerRules.newId()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = dir
        }
    }
    private fun intent(context: Context,request: ReminderRequest)=Intent(context,PlannerReminderReceiver::class.java)
        .setAction(ReminderScheduler.ACTION).setData(Uri.parse("campus-planner://reminder/${request.identity}"))
        .putExtra("kind",request.kind).putExtra("itemId",request.itemId)
        .putExtra("revision",request.revision).putExtra("at",request.at.toEpochMilli())
    private fun pending(context: Context,request: ReminderRequest,flags: Int)=PendingIntent.getBroadcast(context,0,
        intent(context,request),flags or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
    private fun <T> worker(block: ()->T): T {
        val done=CountDownLatch(1);val result=AtomicReference<Result<T>>()
        PlannerRuntime.execute { result.set(runCatching(block));done.countDown() }
        assertTrue(done.await(8,TimeUnit.SECONDS));return result.get().getOrThrow()
    }
    private fun fixture(block: (Context,PlannerState,ReminderRequest,ReminderRequest)->Unit) {
        val context=isolated()
        val date=ZonedDateTime.now().withSecond(0).withNano(0)
        val todo=TodoItem(PlannerRules.newId(),"Legacy fixture",date.toLocalDate(),date.toLocalTime(),repeat=Repeat.DAILY,reminder=true)
        val countdown=CountdownItem(PlannerRules.newId(),"Countdown fixture",date.toLocalDate(),reminder=true,reminderTime=date.toLocalTime())
        val state=PlannerState(todos=listOf(todo),countdowns=listOf(countdown),
            delivered=mapOf("todo/${todo.id}" to "${todo.revision}:${date.minusDays(1).toInstant().toEpochMilli()}"))
        val retired=ReminderRequest("todo",todo.id,todo.revision,date.toInstant())
        val retained=ReminderRequest("countdown",countdown.id,countdown.revision,date.toInstant())
        worker { PlannerStore(context).write(state) }
        try { block(context,state,retired,retained) } finally {
            val alarms=context.getSystemService(AlarmManager::class.java)
            val notifications=context.getSystemService(NotificationManager::class.java)
            listOf(retired,retained).forEach { request ->
                pending(context,request,PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it);it.cancel() }
                notifications.cancel(request.identity,0)
            }
        }
    }

    @Test fun ordinaryLoadCancelsRetiredTokenButPreservesCountdownAndLegacyFile() = fixture { context,state,todo,countdown ->
        val retired=pending(context,todo,PendingIntent.FLAG_UPDATE_CURRENT)!!
        val retained=pending(context,countdown,PendingIntent.FLAG_UPDATE_CURRENT)!!
        val alarms=context.getSystemService(AlarmManager::class.java)
        listOf(retired,retained).forEach { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,System.currentTimeMillis()+300_000,it) }
        val file=File(context.filesDir,"planner-v1.json");val bytes=file.readBytes()
        val done=CountDownLatch(1);val result=AtomicReference<Result<PlannerState>>()
        PlannerRepository.get(context).load { result.set(it);done.countDown() }
        assertTrue(done.await(8,TimeUnit.SECONDS));assertEquals(state,result.get().getOrThrow())
        assertNull(pending(context,todo,PendingIntent.FLAG_NO_CREATE))
        assertEquals(retained,pending(context,countdown,PendingIntent.FLAG_NO_CREATE))
        assertArrayEquals(bytes,file.readBytes())
    }

    @Test fun oldDeliveryAndDirectNotifierCannotNotifyOrConsumeLegacyTodo() = fixture { context,state,todo,countdown ->
        pending(context,todo,PendingIntent.FLAG_UPDATE_CURRENT)
        val retained=pending(context,countdown,PendingIntent.FLAG_UPDATE_CURRENT)
        val file=File(context.filesDir,"planner-v1.json");val bytes=file.readBytes()
        assertFalse(worker { PlannerReminderReceiver.dispatch(context,todo) })
        assertFalse(ReminderScheduler(context).notifySaved(state,todo))
        assertEquals(state,worker { PlannerStore(context).read() })
        assertArrayEquals(bytes,file.readBytes())
        assertNull(pending(context,todo,PendingIntent.FLAG_NO_CREATE))
        assertEquals(retained,pending(context,countdown,PendingIntent.FLAG_NO_CREATE))
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.tag==todo.identity })
    }

    @Test fun upgradeAndBootReconciliationCancelLegacyTokenWithoutEditingRecords() = fixture { context,state,todo,_ ->
        pending(context,todo,PendingIntent.FLAG_UPDATE_CURRENT)
        val file=File(context.filesDir,"planner-v1.json");val bytes=file.readBytes()
        worker { ReminderScheduler(context).reconcile(state,state,reset=true) }
        assertNull(pending(context,todo,PendingIntent.FLAG_NO_CREATE))
        assertArrayEquals(bytes,file.readBytes())
    }
}
