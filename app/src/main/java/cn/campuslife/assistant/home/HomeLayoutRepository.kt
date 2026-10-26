package cn.campuslife.assistant.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** All screens share a FIFO. Every edit transforms the last committed layout, not a stale UI copy. */
class HomeLayoutRepository(context: Context,private val executor: Executor=io,
    private val deliver: (()->Unit)->Unit={ Handler(Looper.getMainLooper()).post(it) }) {
    private val store=HomeLayoutStore(context.applicationContext)
    fun load(complete: (Result<HomeLayout>)->Unit) { executor.execute { val result=runCatching { store.load() };deliver { complete(result) } } }
    fun update(change: (HomeLayout)->HomeLayout,complete: (Result<HomeLayout>)->Unit) {
        executor.execute {
            val result=runCatching { change(store.load()).normalized().also { store.save(it) } }
            deliver { complete(result) }
        }
    }
    companion object { private val io=Executors.newSingleThreadExecutor { Thread(it,"home-layout-io") } }
}
