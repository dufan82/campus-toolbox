package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DailySyncEngineTest {
    private val now=ZonedDateTime.of(2026,9,21,7,5,0,0,ZoneId.of("Asia/Shanghai"))
    private val course=Course("00000000-0000-0000-0000-000000000001","Math","Teacher","101",1,1,2,1,6,WeekPattern.EVERY,null)
    private val data=ScheduleData(Semester(LocalDate.of(2026,9,7),6),courses=listOf(course))
    private inner class Fake : DailySyncRepository {
        var status=DailySyncState(enabled=true)
        var credential: SchoolSyncCredential?=SchoolSyncCredential("Bearer synthetic-test-token",data.semester!!)
        var local=ScheduleSnapshot(data,SchoolRegistry(mapOf(course.id to course)))
        var incoming=SchoolFetch(data)
        var fetches=0;var writes=0;var notices=0;var fail:Exception?=null
        var onFetch:()->Unit={};var denyNotifications=false
        override fun state()=status
        override fun update(generation:Long,transform:(DailySyncState)->DailySyncState) { if(status.enabled && status.generation==generation)status=transform(status) }
        override fun credential()=credential
        override fun clearCredential(generation:Long){if(status.generation==generation)credential=null}
        override fun snapshot()=local
        override fun fetch(credential:SchoolSyncCredential,cancelled:AtomicBoolean):SchoolFetch {fetches++;onFetch();fail?.let {throw it};return incoming}
        override fun apply(expected:ScheduleSnapshot,school:ScheduleData,today:LocalDate,generation:Long):SchoolAutoPlan {
            check(status.enabled && status.generation==generation);check(local==expected)
            val p=SchoolAutoMerge.plan(expected,school,today)
            if(p.data!=local.data){writes++;local=ScheduleSnapshot(p.data,local.registry.copy(managed=p.managed))};return p
        }
        override fun notify(changes:SchoolChanges){if(denyNotifications)throw SecurityException("denied");notices++}
    }
    @Test fun noChangeHasNoWriteOrNotificationButRecordsCheckAndSuccess() {
        val f=Fake();DailySyncEngine(f).run(now);assertEquals(0,f.writes);assertEquals(0,f.notices)
        assertEquals(SyncResult.NO_CHANGE,f.status.result);assertTrue(f.status.lastSuccess>0)
    }
    @Test fun changedCoursesCommitAndNotifyOnceEvenWithoutNotificationPermission() {
        for(deny in listOf(false,true)){val f=Fake();f.denyNotifications=deny
            f.incoming=SchoolFetch(data.copy(courses=listOf(course.copy(classroom="202"))))
            DailySyncEngine(f).run(now);assertEquals(1,f.writes);assertEquals(SyncResult.UPDATED,f.status.result)
            assertEquals(if(deny)0 else 1,f.notices)
        }
    }
    @Test fun expiredAuthStopsFurtherRequestsUntilNewLoginAndRetainsData() {
        val f=Fake();f.fail=SchoolAuthenticationException();DailySyncEngine(f).run(now)
        assertEquals(SyncResult.NEEDS_LOGIN,f.status.result);assertNull(f.credential);assertEquals(0,f.writes)
        DailySyncEngine(f).run(now.plusDays(1));assertEquals(1,f.fetches)
    }
    @Test fun networkIncompleteSemesterAndPeriodsFailuresNeverWrite() {
        val fakes=listOf(Fake().apply {fail=IOException()},Fake().apply {incoming=SchoolFetch(data,complete=false)},
            Fake().apply {incoming=SchoolFetch(data.copy(semester=Semester(LocalDate.of(2026,9,14),6)))},
            Fake().apply {incoming=SchoolFetch(data.copy(periods=data.periods.dropLast(1)))})
        fakes.forEach { f->DailySyncEngine(f).run(now);assertEquals(0,f.writes);assertEquals(data,f.local.data);assertEquals(0L,f.status.lastSuccess) }
    }
    @Test fun duplicateDayAndConcurrentRunsOnlyFetchOnce() {
        val f=Fake();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        f.onFetch={entered.countDown();release.await()}
        val worker=thread {DailySyncEngine(f).run(now)};entered.await()
        assertEquals(SyncResult.SKIPPED,DailySyncEngine(f).run(now));release.countDown();worker.join()
        DailySyncEngine(f).run(now);assertEquals(1,f.fetches)
    }
    @Test fun disableBeforeOrDuringFetchPreventsCommitAndReenableAllowsNextAttempt() {
        val f=Fake();f.status=f.status.copy(enabled=false);DailySyncEngine(f).run(now);assertEquals(0,f.fetches)
        f.status=f.status.copy(enabled=true,generation=1)
        f.onFetch={f.status=f.status.copy(enabled=false,generation=2)}
        DailySyncEngine(f).run(now);assertEquals(0,f.writes)
        f.onFetch={};f.status=f.status.copy(enabled=true,generation=3,lastAttemptDay="")
        DailySyncEngine(f).run(now);assertEquals(2,f.fetches)
    }
    @Test fun nextSevenUsesLocalTimeAndRebootCatchupDoesNotDuplicateCompletedDay() {
        assertEquals("2026-09-22T07:00+08:00[Asia/Shanghai]",DailySyncTiming.next(now).toString())
        val before=now.withHour(6);assertEquals(7,DailySyncTiming.next(before).hour);assertEquals(before.dayOfMonth,DailySyncTiming.next(before).dayOfMonth)
        val ny=ZonedDateTime.of(2026,3,7,8,0,0,0,ZoneId.of("America/New_York"))
        assertEquals(22,Duration.between(ny,DailySyncTiming.next(ny)).toHours())
        assertEquals(now.toInstant(),DailySyncTiming.due(DailySyncState(enabled=true),now).toInstant())
        assertEquals(22,DailySyncTiming.due(DailySyncState(enabled=true,lastAttemptDay="2026-09-21"),now).dayOfMonth)
    }
    @Test fun socketTimeoutIsNotAnImmediateRetryLoop() {
        val f=Fake();f.fail=java.net.SocketTimeoutException();DailySyncEngine(f).run(now)
        assertEquals(SyncResult.NETWORK_ERROR,f.status.result)
        DailySyncEngine(f).run(now);assertEquals(1,f.fetches)
    }
    @Test fun missingCredentialNeverFetchesAndNewLoginCanResume() {
        val f=Fake();f.credential=null;DailySyncEngine(f).run(now)
        assertEquals(0,f.fetches);assertEquals(SyncResult.NEEDS_LOGIN,f.status.result)
        f.credential=SchoolSyncCredential("Bearer new-synthetic-token",data.semester!!)
        f.status=f.status.copy(generation=1,result=SyncResult.READY,lastAttemptDay="")
        DailySyncEngine(f).run(now);assertEquals(1,f.fetches)
    }
    @Test fun concurrentManualEditOrChangedAuthenticationGenerationRejectsStaleCommit() {
        val f=Fake();f.incoming=SchoolFetch(data.copy(courses=listOf(course.copy(teacher="Changed"))))
        f.onFetch={f.local=f.local.copy(data=data.copy(courses=listOf(course.copy(teacher="Mine"))))}
        DailySyncEngine(f).run(now);assertEquals(0,f.writes);assertEquals("Mine",f.local.data.courses.single().teacher)
        assertEquals(SyncResult.INVALID_DATA,f.status.result)
    }
    @Test fun cancelledRunNeverSavesAndCanBeRescheduledWithSameDay() {
        val f=Fake();val stop=AtomicBoolean();f.onFetch={stop.set(true)}
        DailySyncEngine(f).run(now,stop);assertEquals(0,f.writes);assertEquals("",f.status.lastAttemptDay)
    }
}
