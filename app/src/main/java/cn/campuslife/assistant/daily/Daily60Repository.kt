package cn.campuslife.assistant.daily

import java.io.InterruptedIOException

fun interface Daily60Client { fun fetch():Daily60Image }
interface Daily60Cache {
    fun read(): Daily60Snapshot?
    fun write(snapshot: Daily60Snapshot)
}
data class Daily60Result(val snapshot: Daily60Snapshot?, val cached: Boolean, val message: String? = null)
internal class Daily60Repository(private val client: Daily60Client, private val cache: Daily60Cache,
    private val now: ()->Long = System::currentTimeMillis) {
    @Volatile private var memory:Daily60Snapshot?=null
    fun peek():Daily60Snapshot?=memory
    fun isFresh(snapshot:Daily60Snapshot?):Boolean {
        val at=snapshot?.savedAtMillis ?: return false
        val current=now()
        return at<=current && java.time.Instant.ofEpochMilli(at).atZone(Daily60Rules.zone).toLocalDate()==
            java.time.Instant.ofEpochMilli(current).atZone(Daily60Rules.zone).toLocalDate()
    }
    @Synchronized fun readCache(): Daily60Snapshot? = memory ?: cache.read()?.also {
        Daily60Rules.image(it.image.bytes,it.image.mime)
        require(it.savedAtMillis>0)
        memory=it
    }
    /** All calls (including cache I/O) belong on the session's worker, never the UI thread. */
    @Synchronized fun refresh(previous: Daily60Snapshot?,force:Boolean=true): Daily60Result {
        val available=memory ?: previous
        if(!force && isFresh(available))return Daily60Result(available,true)
        try {
            checkCancellation()
            val image=client.fetch().let {Daily60Rules.image(it.bytes,it.mime)}
            checkCancellation()
            val snapshot=Daily60Snapshot(image,now())
            memory=snapshot
            return try {
                cache.write(snapshot)
                Daily60Result(snapshot,false)
            } catch(_: Exception) {
                Daily60Result(snapshot,false,"图片已获取，缓存未保存")
            }
        } catch(error: Exception) {
            return Daily60Result(available,available!=null,
                Daily60Rules.failure(error)+if(available==null)"；暂无缓存图片" else "；显示已缓存图片")
        }
    }
    private fun checkCancellation() { if(Thread.currentThread().isInterrupted) throw InterruptedIOException() }
}
