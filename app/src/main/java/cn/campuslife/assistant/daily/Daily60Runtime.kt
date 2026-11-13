package cn.campuslife.assistant.daily

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One application-only image and repository, never an Activity, View, account or scheduler. */
internal object Daily60Runtime {
    private var repository:Daily60Repository?=null
    @Volatile private var preview:Pair<Daily60Image,Bitmap>?=null
    private val warming=AtomicBoolean()
    private val worker=Executors.newSingleThreadExecutor {Thread(it,"daily60-warm").apply {isDaemon=true}}
    @Synchronized fun repository(context:Context):Daily60Repository = repository ?: Daily60Repository(
        Daily60ApiClient(),Daily60Store(context.applicationContext)).also {repository=it}
    fun preview(image:Daily60Image):Bitmap?=preview?.takeIf {it.first===image && !it.second.isRecycled}?.second
    fun remember(image:Daily60Image,bitmap:Bitmap) {preview=image to bitmap}
    /** Called after the home first frame. Today's cache performs zero network requests. */
    fun warm(context:Context) {
        val repo=repository(context)
        if(repo.isFresh(repo.peek()) && repo.peek()?.image?.let(::preview)!=null)return
        if(!warming.compareAndSet(false,true))return
        worker.execute {
            try {
                val old=runCatching {repo.readCache()}.getOrNull()
                if(old!=null && preview(old.image)==null)remember(old.image,Daily60Bitmap.decode(old.image))
                val result=repo.refresh(old,force=false)
                result.snapshot?.let {if(preview(it.image)==null)remember(it.image,Daily60Bitmap.decode(it.image))}
            } catch(_:Exception) { /* The reading page owns error feedback; a failed warmup is silent. */ }
            finally {warming.set(false)}
        }
    }
}
