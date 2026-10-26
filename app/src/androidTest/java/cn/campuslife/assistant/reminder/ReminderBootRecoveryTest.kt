package cn.campuslife.assistant.reminder

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.planner.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Two explicit phases around a real reboot, dedicated emulator only. Never ordinary user data. */
class ReminderBootRecoveryTest {
    @Test fun explicitRebootRestoresFutureAlarmWithoutOpeningPlanner() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val phase=InstrumentationRegistry.getArguments().getString("bootPhase")
        assumeTrue(phase in setOf("seed","verify") && Build.HARDWARE in setOf("ranchu","goldfish"))
        val context=instrumentation.targetContext
        val store=PlannerStore(context)
        val backup=File(context.filesDir,"reminder-boot-fixture-backup.json")
        val id="80000000-0000-4000-8000-000000000001"
        val identity="countdown/$id"
        fun pending()=PendingIntent.getBroadcast(context,0,Intent(context,PlannerReminderReceiver::class.java)
            .setAction(ReminderScheduler.ACTION).setData(Uri.parse("campus-planner://reminder/$identity")),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        if(phase=="seed") {
            check(!backup.exists()) { "Previous reboot fixture must be recovered before starting another" }
            val old=store.read();backup.writeText(PlannerStore.encode(old).toString())
            val state=PlannerState(countdowns=listOf(CountdownItem(id,"Synthetic reboot fixture",LocalDate.now().plusDays(7),reminder=true)))
            store.write(state);ReminderScheduler(context).reconcile(old,state)
            assertNotNull("Future alarm must be scheduled before reboot",pending())
        } else {
            val current=store.read()
            try {
                assertTrue(backup.isFile)
                assertTrue(current.countdowns.any { it.id==id })
                // sys.boot_completed can become 1 before this application's BOOT_COMPLETED
                // receiver has finished its asynchronous planner-io work. Observe, never reschedule here.
                val deadline=android.os.SystemClock.uptimeMillis()+15000
                while(pending()==null && android.os.SystemClock.uptimeMillis()<deadline)
                    android.os.SystemClock.sleep(100)
                assertNotNull("Boot receiver must recreate the lost alarm without opening planner",pending())
            } finally {
                if(backup.isFile) {
                    val restored=PlannerStore.decode(JSONObject(backup.readText()))
                    store.write(restored);ReminderScheduler(context).reconcile(current,restored);backup.delete()
                }
            }
        }
    }
}
