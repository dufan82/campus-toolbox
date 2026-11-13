package cn.campuslife.assistant.daily

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class Daily60SessionTest {
    private class Worker: AbstractExecutorService() {
        val tasks=ArrayDeque<Runnable>(); var stopped=false
        override fun execute(command: Runnable) { tasks.add(command) }
        override fun shutdown() {stopped=true}
        override fun shutdownNow(): MutableList<Runnable> {stopped=true;return mutableListOf()}
        override fun isShutdown()=stopped
        override fun isTerminated()=stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit)=stopped
        fun run() { while(tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    @Test fun openingReadsCacheOnWorkerThenOneGetAndRapidRefreshIsDebounced() {
        val worker=Worker();val ui=ArrayDeque<()->Unit>();var calls=0;var reads=0;var nanos=0L
        val cached=Daily60Snapshot(Daily60TestImage.image(),1000)
        val cache=object:Daily60Cache {
            override fun read(): Daily60Snapshot {reads++;return cached}
            override fun write(snapshot: Daily60Snapshot) {}
        }
        val repo=Daily60Repository(Daily60Client {calls++;Daily60TestImage.image()},cache)
        val session=Daily60Session(repo,{ui.add(it)},worker,{nanos})
        val states=mutableListOf<Daily60State>();session.bind(states::add);session.open();session.open()
        assertEquals(0,reads);assertEquals(0,calls);assertFalse(session.refresh())
        worker.run();while(ui.isNotEmpty())ui.removeFirst().invoke()
        assertEquals(1,reads);assertEquals(1,calls)
        assertTrue(states.any {it.loading && it.result.snapshot==cached})
        assertFalse(states.last().loading);assertFalse(session.refresh())
        nanos=2_000_000_000L;assertTrue(session.refresh());assertFalse(session.refresh())
        worker.run();while(ui.isNotEmpty())ui.removeFirst().invoke()
        assertEquals(2,calls);session.close()
    }
    @Test fun openingTodaysCacheDoesNotWaitForOrRepeatNetwork() {
        val worker=Worker();val ui=ArrayDeque<()->Unit>();var calls=0
        val at=java.time.ZonedDateTime.of(2026,11,12,10,0,0,0,Daily60Rules.zone).toInstant().toEpochMilli()
        val cached=Daily60Snapshot(Daily60TestImage.image(),at)
        val cache=object:Daily60Cache {override fun read()=cached;override fun write(snapshot:Daily60Snapshot) {fail("No unchanged-cache write")}}
        val repo=Daily60Repository(Daily60Client {calls++;throw java.io.IOException()},cache,{at+60000})
        val states=mutableListOf<Daily60State>()
        val session=Daily60Session(repo,{ui.add(it)},worker)
        session.bind(states::add);session.open();worker.run();while(ui.isNotEmpty())ui.removeFirst().invoke()
        assertEquals("Today's cached image must not start a network request",0,calls)
        assertSame(cached,states.last().result.snapshot);assertFalse(states.last().loading)
        assertNull(states.last().result.message);session.close()
    }
    @Test fun detachAndRebindForRotationDoesNotIssueAnotherRequest() {
        val worker=Worker();val ui=ArrayDeque<()->Unit>();var calls=0
        val cache=object:Daily60Cache {override fun read():Daily60Snapshot?=null;override fun write(snapshot:Daily60Snapshot) {}}
        val session=Daily60Session(Daily60Repository(Daily60Client {calls++;throw java.io.IOException()},cache),{ui.add(it)},worker)
        var oldDeliveries=0;var newDeliveries=0
        session.bind {oldDeliveries++};session.open();session.detach();val before=oldDeliveries
        session.bind {newDeliveries++};session.open();worker.run();while(ui.isNotEmpty())ui.removeFirst().invoke()
        assertEquals(before,oldDeliveries);assertTrue(newDeliveries>0);assertEquals(1,calls);session.close()
    }
    @Test fun closingSuppressesPendingCallbacksAndRejectsNewFetches() {
        val worker=Worker();val ui=ArrayDeque<()->Unit>();var calls=0
        val cache=object:Daily60Cache {override fun read():Daily60Snapshot?=null;override fun write(snapshot:Daily60Snapshot) {}}
        val session=Daily60Session(Daily60Repository(Daily60Client {calls++;throw java.io.IOException()},cache),{ui.add(it)},worker)
        var deliveries=0;session.bind {deliveries++};session.open();worker.run();session.close();val before=deliveries
        while(ui.isNotEmpty())ui.removeFirst().invoke()
        assertEquals(before,deliveries);assertFalse(session.refresh());assertEquals(1,calls);assertTrue(worker.stopped)
    }
    @Test fun closingBeforeWorkerStartsDoesNotReadCacheOrOpenNetwork() {
        val worker=Worker();var calls=0;var reads=0
        val cache=object:Daily60Cache {override fun read():Daily60Snapshot? {reads++;return null};override fun write(snapshot:Daily60Snapshot) {}}
        val session=Daily60Session(Daily60Repository(Daily60Client {calls++;throw java.io.IOException()},cache),{it()},worker)
        session.open();session.close();worker.run()
        assertEquals(0,reads);assertEquals(0,calls)
    }
}
