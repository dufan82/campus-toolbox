package cn.campuslife.assistant.schedule

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.view.View
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

/** Disposable OFFLINE emulator only. All school reads are replaced with synthetic data. */
class DailySchoolSyncDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val today get()=LocalDate.now()
    private fun data():ScheduleData {
        val start=today.with(DayOfWeek.MONDAY).minusWeeks(2)
        return ScheduleData(Semester(start,6),courses=listOf(Course("00000000-0000-0000-0000-000000000001",
            "Synthetic course","Synthetic teacher","101",today.dayOfWeek.value,1,2,1,6,WeekPattern.EVERY,null)))
    }
    @Before fun reset(){DailySchoolSync.setEnabled(context,false)}
    @After fun cleanup(){DailySchoolSync.setEnabled(context,false);DailySyncPreferences(context).write(DailySyncState())}
    @Test fun defaultIsDisabledAndNoJobExists() {
        context.getSharedPreferences("school-daily-sync-v1",0).edit().clear().commit()
        assertFalse(DailySyncPreferences(context).read().enabled)
        DailySyncScheduler.reconcile(context)
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(DailySyncScheduler.JOB_ID))
    }
    @Test fun tokenCiphertextSurvivesNewVaultAndNeverAppearsInTimetableOrPlaintextFile() {
        val token="Bearer synthetic-private-test-token"
        val vault=SchoolSyncVault(context);vault.write(SchoolSyncCredential(token,data().semester!!))
        assertEquals(token,SchoolSyncVault(context).read()?.authorization)
        val file=File(context.noBackupFilesDir,"school-sync-credential-v1")
        assertFalse(file.readText().contains(token));assertFalse(file.readText().contains("Bearer"))
        assertFalse(ScheduleStore.encode(data()).toString().contains(token))
        file.writeText("corrupted ciphertext");assertNull(SchoolSyncVault(context).read())
    }
    @Test fun disableAndLogoutClearBackgroundCredentialButRetainScheduleAndHistory() {
        val store=ScheduleStore(context);store.replaceWithBackup(store.load(),data(),true)
        DailySyncPreferences(context).write(DailySyncState(enabled=true,lastAttempt=123,lastSuccess=100))
        SchoolSyncVault(context).write(SchoolSyncCredential("Bearer synthetic-token",data().semester!!))
        DailySchoolSync.setEnabled(context,false)
        assertNull(SchoolSyncVault(context).read());assertEquals(data(),store.load())
        assertEquals(123L,DailySyncPreferences(context).read().lastAttempt)
        assertEquals(100L,DailySyncPreferences(context).read().lastSuccess)
        DailySchoolSync.setEnabled(context,true);DailySchoolSync.authorizeAfterImport(context,"Bearer synthetic-token",data())
        DailySchoolSync.forgetLogin(context)
        assertNull(SchoolSyncVault(context).read());assertEquals(SyncResult.NEEDS_LOGIN,DailySyncPreferences(context).read().result)
    }
    @Test fun persistedUniqueNetworkJobRestoresAndDisablingCancelsIt() {
        val state=DailySyncState(enabled=true,lastAttemptDay=today.toString())
        DailySyncPreferences(context).write(state)
        val now=ZonedDateTime.now()
        DailySyncScheduler.reconcile(context,reset=true,now=now)
        val jobs=context.getSystemService(JobScheduler::class.java)
        val first=jobs.getPendingJob(DailySyncScheduler.JOB_ID)!!
        assertTrue(first.isPersisted);assertEquals(JobInfo.NETWORK_TYPE_ANY,first.networkType)
        assertEquals(DailySyncScheduler.TASK_NAME,first.extras.getString("name"))
        assertEquals(DailySyncTiming.next(now).toInstant().toEpochMilli(),first.extras.getLong("plannedAt"))
        DailySyncScheduler.reconcile(context);assertEquals(1,jobs.allPendingJobs.count {it.id==DailySyncScheduler.JOB_ID})
        jobs.cancel(DailySyncScheduler.JOB_ID)
        DailySyncScheduler.reconcile(context,reset=true,now=now) // same recovery path used by BOOT/TIME/TIMEZONE receiver
        assertNotNull(jobs.getPendingJob(DailySyncScheduler.JOB_ID))
        DailySchoolSync.setEnabled(context,false);assertNull(jobs.getPendingJob(DailySyncScheduler.JOB_ID))
        DailySchoolSync.setEnabled(context,true);assertTrue(DailySyncPreferences(context).read().enabled)
        assertEquals(SyncResult.NEEDS_LOGIN,DailySyncPreferences(context).read().result)
    }
    @Test fun explicitConfirmedLoginResumesTomorrowAndDisabledImportDoesNotStoreToken() {
        assertFalse(DailySchoolSync.authorizeAfterImport(context,"Bearer synthetic-token",data()))
        assertNull(SchoolSyncVault(context).read())
        DailySchoolSync.setEnabled(context,true)
        assertTrue(DailySchoolSync.authorizeAfterImport(context,"Bearer synthetic-token",data()))
        assertEquals(SyncResult.READY,DailySyncPreferences(context).read().result)
        assertNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(DailySyncScheduler.JOB_ID))
    }
    @Test fun orphanedInterruptedProcessIsRecoveredButCompletedDayIsNotRepeated() {
        DailySyncPreferences(context).write(DailySyncState(enabled=true,result=SyncResult.CHECKING,lastAttemptDay=today.toString()))
        DailySyncScheduler.reconcile(context,reset=true)
        val recovered=DailySyncPreferences(context).read()
        assertEquals(SyncResult.INTERRUPTED,recovered.result);assertEquals("",recovered.lastAttemptDay)
        assertNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(DailySyncScheduler.JOB_ID))
    }
    @Test fun noChangeLeavesBothScheduleAndUndoBytesIdenticalAndChangedPlanBacksUp() {
        val store=ScheduleStore(context);store.replaceWithBackup(store.load(),data(),true)
        val file=File(context.filesDir,ScheduleStore.FILE_NAME);val undo=File(context.filesDir,ScheduleStore.IMPORT_UNDO_FILE)
        val bytes=file.readBytes();val undoBytes=undo.takeIf {it.exists()}?.readBytes()
        store.applyAutomatic(store.loadSnapshot(),data(),today)
        assertArrayEquals(bytes,file.readBytes());assertArrayEquals(undoBytes,undo.takeIf {it.exists()}?.readBytes())
        val incoming=data().copy(courses=data().courses.map {it.copy(classroom="202")})
        store.applyAutomatic(store.loadSnapshot(),incoming,today)
        assertEquals(data(),store.previousImport())
        assertTrue(store.load().courses.any {it.classroom=="202"})
        assertArrayEquals(bytes,undo.readBytes())
    }
    @Test fun manualDeletionStoresOriginalFingerprintAndCannotBeResurrected() {
        val store=ScheduleStore(context);val old=data();store.replaceWithBackup(store.load(),old,true)
        store.commit(old,old.delete(old.courses.single().id))
        val snapshot=ScheduleStore(context).loadSnapshot()
        assertEquals(old.courses.single(),snapshot.registry.protectedCourses[old.courses.single().id])
        val plan=store.applyAutomatic(snapshot,old,today)
        assertTrue(plan.data.courses.isEmpty());assertTrue(plan.conflicts>0)
        assertEquals(snapshot.registry,store.loadSnapshot().registry)
    }
    @Test fun legacyProtectedIdsStillReadAndMalformedProtectedSnapshotsRejectRatherThanDrop() {
        val old=data();val c=old.courses.single()
        val json=ScheduleStore.encodeSnapshot(ScheduleSnapshot(old,SchoolRegistry(emptyMap(),setOf(c.id))))
        json.remove("schoolProtectedCourses")
        val parsed=ScheduleStore.decodeSnapshot(json)
        assertEquals(setOf(c.id),parsed.registry.protectedIds);assertTrue(parsed.registry.protectedCourses.isEmpty())
        json.put("schoolProtectedCourses","wrong-type")
        assertThrows(IllegalArgumentException::class.java){ScheduleStore.decodeSnapshot(json)}
    }
    @Test fun compareAndSwapPreventsConcurrentManualEditsBeingOverwritten() {
        val store=ScheduleStore(context);store.replaceWithBackup(store.load(),data(),true)
        val before=store.loadSnapshot()
        val manual=data().copy(courses=data().courses.map {it.copy(teacher="Mine",placeId="local-place")})
        store.commit(before.data,manual)
        val bytes=File(context.filesDir,ScheduleStore.FILE_NAME).readBytes()
        assertThrows(ScheduleConflictException::class.java){store.applyAutomatic(before,data(),today)}
        assertArrayEquals(bytes,File(context.filesDir,ScheduleStore.FILE_NAME).readBytes())
    }
    @Test fun engineWithFakeSchoolSourceUpdatesOnDeviceWithoutNotificationPermission() {
        val store=ScheduleStore(context);store.replaceWithBackup(store.load(),data(),true)
        DailySyncPreferences(context).write(DailySyncState(enabled=true))
        SchoolSyncVault(context).write(SchoolSyncCredential("Bearer synthetic-token",data().semester!!))
        val source=object:DailySyncRepository by DailySchoolSync.repository(context) {
            override fun fetch(credential:SchoolSyncCredential,cancelled:AtomicBoolean)=
                SchoolFetch(data().copy(courses=data().courses.map {it.copy(teacher="Changed")}))
        }
        assertEquals(SyncResult.UPDATED,DailySyncEngine(source).run(ZonedDateTime.now()))
        assertTrue(store.load().courses.any {it.teacher=="Changed"})
        assertEquals(SyncResult.SKIPPED,DailySyncEngine(source).run(ZonedDateTime.now()))
    }
    @Test fun settingsDisplaysSwitchTimeAndStatusAfterRecreationWithoutStartingSync() {
        ActivityScenario.launch(DailySyncSettingsActivity::class.java).use {scenario->
            Thread.sleep(300);scenario.onActivity {a->
                val toggle=a.window.decorView.findViewWithTag<Switch>("school_sync_switch")
                assertNotNull(toggle);assertFalse(toggle.isChecked)
                assertNotNull(a.window.decorView.findViewWithTag<TextView>("school_sync_attempt"))
            }
            scenario.recreate();Thread.sleep(300)
            scenario.onActivity {a->assertFalse(a.window.decorView.findViewWithTag<Switch>("school_sync_switch").isChecked)}
        }
        assertFalse(DailySyncPreferences(context).read().enabled)
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(DailySyncScheduler.JOB_ID))
    }
}
