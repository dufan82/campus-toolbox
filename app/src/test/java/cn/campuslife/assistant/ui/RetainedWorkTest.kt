package cn.campuslife.assistant.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class RetainedWorkTest {
    private class ManualExecutor : AbstractExecutorService() {
        val pending=ArrayDeque<Runnable>()
        private var stopped=false
        override fun execute(command: Runnable) { pending.add(command) }
        override fun shutdown() { stopped=true }
        override fun shutdownNow(): MutableList<Runnable> { stopped=true; return mutableListOf() }
        override fun isShutdown()=stopped
        override fun isTerminated()=stopped && pending.isEmpty()
        override fun awaitTermination(timeout: Long,unit: TimeUnit)=isTerminated
    }

    @Test fun completionIsDeliveredToTheRecreatedReceiverExactlyOnce() {
        val executor=ManualExecutor(); val posted=ArrayDeque<()->Unit>()
        val queue=RetainedWork<MutableList<String>>({ posted.add(it) },executor)
        val original=mutableListOf<String>(); val recreated=mutableListOf<String>()
        queue.bind(original) { _,_-> }
        assertTrue(queue.submit("export",{ "saved" },{ add(it.getOrThrow()) }))
        queue.detach(original); executor.pending.removeFirst().run(); posted.removeFirst().invoke()
        assertTrue(original.isEmpty()); assertTrue(queue.busy)
        queue.bind(recreated) { _,_-> }
        assertEquals(listOf("saved"),recreated); assertFalse(queue.busy)
        queue.detach(recreated); queue.bind(recreated) { _,_-> }
        assertEquals(1,recreated.size)
        queue.close()
    }

    @Test fun externalPickerResultQueuesBehindDraftRestoreWithoutBeingDropped() {
        val executor=ManualExecutor(); val posted=ArrayDeque<()->Unit>()
        val queue=RetainedWork<MutableList<String>>({ posted.add(it) },executor)
        val receiver=mutableListOf<String>(); queue.bind(receiver) { _,_-> }
        queue.submit("restore",{ "old draft" },{ add(it.getOrThrow()) })
        assertTrue(queue.submit("picker",{ "new photo" },{ add(it.getOrThrow()) },enqueue=true))
        executor.pending.removeFirst().run(); posted.removeFirst().invoke()
        executor.pending.removeFirst().run(); posted.removeFirst().invoke()
        assertEquals(listOf("old draft","new photo"),receiver)
        assertFalse(queue.busy); queue.close()
    }

    @Test fun repeatedExportIsRejectedWhileTheFirstIsInFlight() {
        val executor=ManualExecutor(); val posted=ArrayDeque<()->Unit>()
        val queue=RetainedWork<MutableList<Int>>({ posted.add(it) },executor)
        val receiver=mutableListOf<Int>(); queue.bind(receiver) { _,_-> }
        assertTrue(queue.submit("export",{ 1 },{ add(it.getOrThrow()) }))
        assertFalse(queue.submit("export",{ 2 },{ add(it.getOrThrow()) }))
        executor.pending.removeFirst().run(); posted.removeFirst().invoke()
        assertEquals(listOf(1),receiver); queue.close()
    }
}
