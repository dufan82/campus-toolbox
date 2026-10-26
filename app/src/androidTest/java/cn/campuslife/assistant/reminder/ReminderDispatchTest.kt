package cn.campuslife.assistant.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.planner.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Invoke on a dedicated emulator, once denied and once granted via pm outside the test process. */
class ReminderDispatchTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun <T> worker(block: ()->T): T {
        val done=CountDownLatch(1)
        val result=AtomicReference<Result<T>>()
        PlannerRuntime.execute { result.set(runCatching(block)); done.countDown() }
        assertTrue("Planner worker timeout",done.await(8,TimeUnit.SECONDS))
        return result.get().getOrThrow()
    }
    private fun fixture(test: (CountdownItem,ReminderRequest)->Unit) {
        assumeTrue(Build.HARDWARE in setOf("ranchu","goldfish") || Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator"))
        val old=worker { PlannerStore(context).read() }
        val date=ZonedDateTime.now().withSecond(0).withNano(0)
        val item=CountdownItem(PlannerRules.newId(),"Synthetic reminder fixture",date.toLocalDate(),reminder=true,reminderTime=date.toLocalTime())
        val request=ReminderRequest("countdown",item.id,item.revision,date.toInstant())
        worker { PlannerStore(context).write(PlannerState(countdowns=listOf(item))) }
        try { test(item,request) } finally {
            worker {
                val current=PlannerStore(context).read()
                PlannerStore(context).write(old)
                ReminderScheduler(context).reconcile(current,old)
                context.getSystemService(NotificationManager::class.java).cancel(request.identity,0)
            }
        }
    }
    @Test fun deniedNotificationPermissionStillPersistsCrudAndCannotEmitNotification() {
        assumeTrue(Build.VERSION.SDK_INT>=33)
        assumeTrue(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_DENIED)
        fixture { item,request ->
            assertNotNull(ReminderScheduler(context).unavailableReason())
            val intent=Intent(context,PlannerReminderReceiver::class.java).setAction(ReminderScheduler.ACTION)
                .setData(Uri.parse("campus-planner://reminder/${request.identity}"))
            val original=PendingIntent.getBroadcast(context,0,intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            assertFalse(worker { PlannerReminderReceiver.dispatch(context,request) })
            assertThrows(PendingIntent.CanceledException::class.java) { original.send() }
            assertNull(PendingIntent.getBroadcast(context,0,intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT))
            val result=AtomicReference<Result<PlannerState>>()
            val done=CountDownLatch(1)
            PlannerRepository.get(context).change({ it.copy(countdowns=it.countdowns.map { countdown ->
                if(countdown.id==item.id) countdown.copy(title="Synthetic edit",revision=PlannerRules.newId()) else countdown
            }) }) { result.set(it); done.countDown() }
            assertTrue(done.await(8,TimeUnit.SECONDS))
            assertTrue(result.get().isSuccess)
            assertEquals("Synthetic edit",worker { PlannerStore(context).read().countdowns.single().title })
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.tag==request.identity })
        }
    }
    @Test fun explicitReceiverDispatchUsesSavedContentPrivateVisibilityAndDeduplicates() {
        assumeTrue(Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
        fixture { item,request ->
            val intent=Intent(context,PlannerReminderReceiver::class.java).setAction(ReminderScheduler.ACTION)
                .setData(Uri.parse("campus-planner://reminder/${request.identity}"))
                .putExtra("kind",request.kind).putExtra("itemId",request.itemId).putExtra("revision",request.revision)
                .putExtra("at",request.at.toEpochMilli()).putExtra("title","Untrusted injected text")
            val original=PendingIntent.getBroadcast(context,0,intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            original.send()
            val manager=context.getSystemService(NotificationManager::class.java)
            val deadline=System.nanoTime()+8_000_000_000
            while(System.nanoTime()<deadline && manager.activeNotifications.none { it.tag==request.identity }) Thread.sleep(40)
            val notification=manager.activeNotifications.single { it.tag==request.identity }.notification
            assertEquals(item.title,notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(Notification.VISIBILITY_PRIVATE,notification.visibility)
            assertNotEquals(item.title,notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertNotNull(notification.contentIntent)
            assertThrows(PendingIntent.CanceledException::class.java) { original.send() }
            assertEquals(request.token,worker { PlannerStore(context).read().delivered[request.identity] })
            assertFalse(worker { PlannerReminderReceiver.dispatch(context,request) })
            val future=item.copy(revision=PlannerRules.newId())
            // Keep the date consistent even when a fixture happens to run just before midnight.
            val futureDate=ZonedDateTime.now().plusMinutes(10).withSecond(0).withNano(0)
            val edited=future.copy(date=futureDate.toLocalDate(),reminderTime=futureDate.toLocalTime())
            worker {
                val old=PlannerStore(context).read()
                val next=old.copy(countdowns=listOf(edited))
                PlannerStore(context).write(next); ReminderScheduler(context).reconcile(old,next)
            }
            val pendingIntent=PendingIntent.getBroadcast(context,0,Intent(context,PlannerReminderReceiver::class.java)
                .setAction(ReminderScheduler.ACTION).setData(intent.data),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            assertNotNull(pendingIntent)
            assertTrue(pendingIntent!!.isImmutable)
            assertFalse(worker { PlannerReminderReceiver.dispatch(context,request) })
            worker {
                val old=PlannerStore(context).read(); val removed=old.without(item.id)
                PlannerStore(context).write(removed); ReminderScheduler(context).reconcile(old,removed)
            }
            assertNull(PendingIntent.getBroadcast(context,0,Intent(context,PlannerReminderReceiver::class.java)
                .setAction(ReminderScheduler.ACTION).setData(intent.data),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT))
            assertFalse(worker { PlannerReminderReceiver.dispatch(context,request) })
        }
    }
    @Test fun justDuePendingAlarmSurvivesLoadAndUnrelatedEditUntilChangedDisabledOrDeleted() {
        assumeTrue(Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
        for(yearly in listOf(false,true)) fixture { item,request ->
            val repeating=item.copy(yearly=yearly)
            worker { PlannerStore(context).write(PlannerState(countdowns=listOf(repeating))) }
            // Model an inexact transport delay: the request's original occurrence is already due,
            // but its platform delivery is held in the future while repository callbacks run.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val intent=Intent(context,PlannerReminderReceiver::class.java).setAction(ReminderScheduler.ACTION)
                    .setData(Uri.parse("campus-planner://reminder/${request.identity}"))
                    .putExtra("kind",request.kind).putExtra("itemId",request.itemId).putExtra("revision",request.revision)
                    .putExtra("at",request.at.toEpochMilli())
                fun pending()=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                val original=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,System.currentTimeMillis()+300_000,original)
                assertTrue(ReminderRules.canDispatch(worker { PlannerStore(context).read() },request,java.time.Instant.now(),java.time.ZoneId.systemDefault()))
                fun saved(change: (PlannerState)->PlannerState) {
                    val done=CountDownLatch(1); val result=AtomicReference<Result<PlannerState>>()
                    PlannerRepository.get(context).change(change) { result.set(it); done.countDown() }
                    assertTrue(done.await(8,TimeUnit.SECONDS)); result.get().getOrThrow()
                }
                val loaded=CountDownLatch(1); val loadResult=AtomicReference<Result<PlannerState>>()
                PlannerRepository.get(context).load { loadResult.set(it); loaded.countDown() }
                assertTrue(loaded.await(8,TimeUnit.SECONDS)); loadResult.get().getOrThrow()
                assertEquals("Ordinary load must retain the original delayed PendingIntent",original,pending())
                saved { it.copy(todos=it.todos+TodoItem(PlannerRules.newId(),"Synthetic unrelated item")) }
                assertEquals("An unrelated save must not replace the delayed PendingIntent",original,pending())
                assertNull(worker { PlannerStore(context).read().delivered[request.identity] })

                saved { state -> state.copy(countdowns=state.countdowns.map { countdown ->
                    if(countdown.id!=item.id) countdown else {
                        val next=countdown.date.plusDays(1)
                        countdown.copy(date=next,revision=PlannerRules.newId())
                    }
                }) }
                assertThrows(PendingIntent.CanceledException::class.java) { original.send() }
                assertNotNull("Changing the date must arm the new future request",pending())
                saved { state -> state.copy(countdowns=state.countdowns.map { if(it.id==item.id) it.copy(reminder=false,revision=PlannerRules.newId()) else it }) }
                assertNull("Disabling the item must cancel its alarm",pending())
                saved { state -> state.copy(countdowns=state.countdowns.map { if(it.id==item.id) it.copy(reminder=true,revision=PlannerRules.newId()) else it }) }
                assertNotNull(pending())
                saved { it.without(item.id) }
                assertNull("Deleting the item must cancel its alarm",pending())
            }
        }
    }
    @Test fun consumedOneShotTokenCanRearmRepeatAfterDeliveryWasPersisted() {
        assumeTrue(Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
        fixture { item,request ->
            worker { PlannerStore(context).write(PlannerState(countdowns=listOf(item.copy(yearly=true)))) }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val intent=Intent(context,PlannerReminderReceiver::class.java).setAction(ReminderScheduler.ACTION)
                    .setData(Uri.parse("campus-planner://reminder/${request.identity}"))
                    .putExtra("kind",request.kind).putExtra("itemId",request.itemId).putExtra("revision",request.revision)
                    .putExtra("at",request.at.toEpochMilli())
                fun pending()=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                val original=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                worker {
                    val state=PlannerStore(context).read()
                    PlannerStore(context).write(state.copy(delivered=mapOf(request.identity to request.token)))
                }
                // The receiver waits on this main thread. Platform ONE_SHOT consumption occurs at send.
                original.send()
                assertNull("A consumed token must not masquerade as an alarm still awaiting delivery",pending())
                val done=CountDownLatch(1); val result=AtomicReference<Result<PlannerState>>()
                PlannerRepository.get(context).load { result.set(it); done.countDown() }
                assertTrue(done.await(8,TimeUnit.SECONDS)); result.get().getOrThrow()
                val next=pending()
                assertNotNull("Load must rebuild the next repeat after consumption",next)
                assertNotEquals(original,next)
                assertThrows(PendingIntent.CanceledException::class.java) { original.send() }
                assertFalse(worker { PlannerReminderReceiver.dispatch(context,request) })
                assertEquals("A duplicate old request must leave the new future alarm intact",next,pending())
            }
        }
    }
    @Test fun expiredRequestCannotNotifyAndReleasesItsOldTokenForNextRepeat() {
        assumeTrue(Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
        fixture { item,request ->
            val oldDate=item.date.minusYears(1)
            val repeating=item.copy(date=oldDate,yearly=true)
            val localExpired=request.copy(at=oldDate.atTime(item.reminderTime)
                .atZone(java.time.ZoneId.systemDefault()).toInstant())
            worker { PlannerStore(context).write(PlannerState(countdowns=listOf(repeating))) }
            val intent=Intent(context,PlannerReminderReceiver::class.java).setAction(ReminderScheduler.ACTION)
                .setData(Uri.parse("campus-planner://reminder/${request.identity}"))
            val original=PendingIntent.getBroadcast(context,0,intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            assertFalse(worker { PlannerReminderReceiver.dispatch(context,localExpired) })
            assertThrows(PendingIntent.CanceledException::class.java) { original.send() }
            assertNotNull(PendingIntent.getBroadcast(context,0,intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT))
            assertNull(worker { PlannerStore(context).read().delivered[request.identity] })
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.tag==request.identity })
        }
    }
}
