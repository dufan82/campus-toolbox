package cn.campuslife.assistant.daily

import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Daily60HttpException(val status:Int):IOException("Public image HTTP $status")
internal class Daily60Http(private val open:(URL)->HttpURLConnection={it.openConnection() as HttpURLConnection},
    private val nanoTime:()->Long=System::nanoTime,private val today:()->LocalDate=Daily60Rules::today,
    private val attemptLimitMillis:Long=8000) {
    fun read():Daily60Image {
        val started=nanoTime()
        fun remaining():Long {
            if(Thread.currentThread().isInterrupted)throw InterruptedIOException()
            return (20_000_000_000L-(nanoTime()-started)).also {if(it<=0)throw java.net.SocketTimeoutException()}
        }
        var last:Exception?=null
        val mirror="https://cdn.jsdmirror.com/gh/vikiboss/60s-static-host@main/static/images/${today()}.png"
        val workers=Executors.newFixedThreadPool(2){Thread(it,"daily60-image-fetch").apply {isDaemon=true}}
        val completed=java.util.concurrent.ExecutorCompletionService<Daily60Image>(workers)
        val futures=mutableListOf<java.util.concurrent.Future<Daily60Image>>()
        val active=java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
        val limit=minOf(20_000_000_000L,TimeUnit.MILLISECONDS.toNanos(attemptLimitMillis+150))
        fun budget():Long=minOf(remaining(),limit-(nanoTime()-started)).also {if(it<=0)throw java.net.SocketTimeoutException()}
        fun submit(url:String) {
            budget()
            futures+=completed.submit(java.util.concurrent.Callable {
                val connection=open(URI(url).toURL());active.add(connection)
                val timer=Executors.newSingleThreadScheduledExecutor {Thread(it,"daily60-image-deadline").apply {isDaemon=true}}
                val deadline=timer.schedule({connection.disconnect()},minOf(budget(),TimeUnit.MILLISECONDS.toNanos(attemptLimitMillis)),TimeUnit.NANOSECONDS)
                try {
                    connection.requestMethod="GET";connection.instanceFollowRedirects=false;connection.useCaches=false
                    connection.connectTimeout=minOf(6000,TimeUnit.NANOSECONDS.toMillis(budget()).coerceAtLeast(1).toInt())
                    connection.readTimeout=minOf(8000,TimeUnit.NANOSECONDS.toMillis(budget()).coerceAtLeast(1).toInt())
                    connection.setRequestProperty("Accept","image/png,image/jpeg,image/webp")
                    connection.setRequestProperty("Accept-Encoding","identity")
                    connection.setRequestProperty("User-Agent","CampusToolbox-Android")
                    if(connection.responseCode!=200)throw Daily60HttpException(connection.responseCode)
                    if(!connection.contentEncoding.isNullOrBlank() && !connection.contentEncoding.equals("identity",true))throw IOException("Unsupported encoding")
                    if(connection.contentLengthLong>Daily60Rules.MAX_BYTES)throw IOException("Image too large")
                    val bytes=ByteArrayOutputStream()
                    connection.inputStream.use {input->
                        val buffer=ByteArray(8192)
                        while(true) {
                            budget();val count=input.read(buffer);budget()
                            if(count<0)break
                            if(bytes.size()+count>Daily60Rules.MAX_BYTES)throw IOException("Image too large")
                            bytes.write(buffer,0,count)
                        }
                    }
                    Daily60Rules.image(bytes.toByteArray(),connection.contentType.orEmpty())
                } finally {deadline.cancel(false);timer.shutdownNow();connection.disconnect();active.remove(connection)}
            })
        }
        fun result(future:java.util.concurrent.Future<Daily60Image>):Daily60Image?=try {future.get()}
            catch(error:java.util.concurrent.ExecutionException){last=error.cause as? Exception ?: IOException("Image unavailable");null}
        try {
            submit(ENDPOINT)
            // A fast primary costs one GET. A slow one gets a fixed mirror after 150ms.
            val first=completed.poll(minOf(budget(),TimeUnit.MILLISECONDS.toNanos(150)),TimeUnit.NANOSECONDS)
            if(first!=null)result(first)?.let{return it}
            submit(mirror)
            var waiting=if(first==null)2 else 1
            while(waiting-->0) {
                val done=completed.poll(budget(),TimeUnit.NANOSECONDS) ?: throw java.net.SocketTimeoutException()
                result(done)?.let{return it}
            }
            throw last ?: IOException("Image unavailable")
        }catch(_:InterruptedException){Thread.currentThread().interrupt();throw InterruptedIOException()}
        finally {futures.forEach {it.cancel(true)};active.forEach {it.disconnect()};workers.shutdownNow()}
    }
    companion object {const val ENDPOINT="https://60s.viki.moe/v2/60s?encoding=image-proxy"}
}
