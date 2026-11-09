package cn.campuslife.assistant.schedule

import android.app.job.JobScheduler
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Two-stage disposable-emulator probe: seed, adb reboot, verify. No credentials and no school requests. */
class DailySyncRebootDeviceTest {
    @Test fun persistedJobAcrossActualDeviceRestart() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val stage=InstrumentationRegistry.getArguments().getString("syncBootStage")
        val marker=context.getSharedPreferences("sync-reboot-test-only",0)
        if(stage=="verify") {
            try {
                assertTrue(marker.contains("uptime"))
                assertTrue(SystemClock.elapsedRealtime()<marker.getLong("uptime",0))
                assertTrue(DailySyncPreferences(context).read().enabled)
                val job=context.getSystemService(JobScheduler::class.java).getPendingJob(DailySyncScheduler.JOB_ID)
                assertNotNull(job);assertTrue(job!!.isPersisted)
                assertNull(SchoolSyncVault(context).read())
            }finally {
                DailySchoolSync.setEnabled(context,false);DailySyncPreferences(context).write(DailySyncState())
                marker.edit().clear().commit()
            }
        }else if(stage=="seed") {
            DailySchoolSync.setEnabled(context,false)
            DailySyncPreferences(context).write(DailySyncState(enabled=true,generation=162,lastAttemptDay=LocalDate.now().toString()))
            DailySyncScheduler.reconcile(context,reset=true)
            marker.edit().putLong("uptime",SystemClock.elapsedRealtime()).commit()
            assertNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(DailySyncScheduler.JOB_ID))
            assertNull(SchoolSyncVault(context).read())
        }else {
            // Normal suite does not restart its own instrumentation process.
            assertNotNull(context.getSystemService(JobScheduler::class.java))
        }
    }
}
