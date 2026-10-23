package cn.campuslife.assistant.ui

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Calls and delivery run on the UI dispatcher; only the action runs on the worker. */
class RetainedWork<R>(private val dispatch: (()->Unit)->Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()) {
    private data class Job(val label: String,val run: ()->Unit)
    private val jobs=ArrayDeque<Job>()
    private val deliveries=ArrayDeque<R.()->Unit>()
    private var running=false
    private var receiver: R?=null
    private var state: (R.(Boolean,String)->Unit)?=null
    var label: String=""
        private set
    val busy get()=running || jobs.isNotEmpty() || deliveries.isNotEmpty()

    fun bind(receiver: R,state: R.(Boolean,String)->Unit) {
        this.receiver=receiver; this.state=state
        notifyState(); deliver(); notifyState()
    }
    fun detach(receiver: R) { if(this.receiver===receiver) { this.receiver=null; state=null } }
    fun <T> submit(label: String,action: ()->T,complete: R.(Result<T>)->Unit,enqueue: Boolean=false): Boolean {
        if(busy && !enqueue) return false
        jobs.add(Job(label) {
            val result=runCatching(action)
            dispatch {
                running=false
                deliveries.add { complete(result) }
                deliver(); startNext(); notifyState()
            }
        })
        startNext(); notifyState(); return true
    }
    private fun startNext() {
        if(running || jobs.isEmpty()) return
        val job=jobs.removeFirst(); running=true; label=job.label
        notifyState(); executor.execute(job.run)
    }
    private fun deliver() {
        val current=receiver ?: return
        while(deliveries.isNotEmpty() && receiver===current) deliveries.removeFirst().invoke(current)
    }
    private fun notifyState() { receiver?.let { state?.invoke(it,busy,label) } }
    fun close() { receiver=null; state=null; executor.shutdown() }
}
