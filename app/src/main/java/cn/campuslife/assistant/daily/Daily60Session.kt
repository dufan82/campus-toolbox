package cn.campuslife.assistant.daily

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal data class Daily60State(val result: Daily60Result=Daily60Result(null,false), val loading: Boolean=false)
internal class Daily60Session(private val repository: Daily60Repository, private val dispatch: (()->Unit)->Unit,
    private val worker: ExecutorService=Executors.newSingleThreadExecutor {Thread(it,"daily60-io")},
    private val nanoTime: ()->Long=System::nanoTime) {
    // Owner/dispatch is the UI thread. Worker closures never retain an Activity or View.
    @Volatile private var closed=false
    private var started=false
    private var lastAttempt: Long?=null
    private var task: Future<*>?=null
    private var receiver: ((Daily60State)->Unit)?=null
    private var state=Daily60State()
    fun bind(receiver: (Daily60State)->Unit) { if(!closed) {this.receiver=receiver;receiver(state)} }
    fun detach() {receiver=null}
    fun open() { if(!started && !closed) {started=true;submit(true)} }
    fun refresh(): Boolean {
        if(closed || state.loading || lastAttempt?.let {nanoTime()-it < 2_000_000_000L}==true) return false
        if(!started) {open();return true}
        submit(false);return true
    }
    private fun submit(readCache: Boolean) {
        lastAttempt=nanoTime();state=state.copy(loading=true);receiver?.invoke(state)
        val previous=state.result.snapshot
        task=worker.submit job@ {
            if(closed) return@job
            var cached=previous
            var cacheError=false
            if(readCache) {
                try {cached=repository.readCache()} catch(_:Exception) {cacheError=true}
                val preview=Daily60Result(cached,cached!=null,if(cacheError) "缓存读取失败，原文件已保留；正在尝试联网。" else null)
                post {state=Daily60State(preview,true);receiver?.invoke(state)}
            }
            if(closed) return@job
            val result=repository.refresh(cached,force=!readCache)
            post {
                val message=if(cacheError && result.snapshot==null) "缓存读取失败，原文件已保留。${result.message.orEmpty()}" else result.message
                state=Daily60State(result.copy(message=message),false);receiver?.invoke(state)
            }
        }
    }
    private fun post(action: ()->Unit) { dispatch {if(!closed) action()} }
    fun close() {closed=true;receiver=null;task?.cancel(true);worker.shutdownNow()}
}
