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
        val sources=listOf(ENDPOINT,"https://cdn.jsdmirror.com/gh/vikiboss/60s-static-host@main/static/images/${today()}.png")
        val workers=Executors.newFixedThreadPool(2){Thread(it,"daily60-image-fetch").apply {isDaemon=true}}
        try {
        for(url in sources) {
            val budget=minOf(remaining(),TimeUnit.MILLISECONDS.toNanos(attemptLimitMillis))
            val request=workers.submit<Daily60Image> {
                val connection=open(URI(url).toURL())
                val timer=Executors.newSingleThreadScheduledExecutor {Thread(it,"daily60-image-deadline").apply {isDaemon=true}}
                val deadline=timer.schedule({connection.disconnect()},remaining(),TimeUnit.NANOSECONDS)
                try {
                    connection.requestMethod="GET";connection.instanceFollowRedirects=false;connection.useCaches=false
                    connection.connectTimeout=minOf(6000,TimeUnit.NANOSECONDS.toMillis(remaining()).coerceAtLeast(1).toInt())
                    connection.readTimeout=minOf(8000,TimeUnit.NANOSECONDS.toMillis(remaining()).coerceAtLeast(1).toInt())
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
                            remaining();val count=input.read(buffer);remaining()
                            if(count<0)break
                            if(bytes.size()+count>Daily60Rules.MAX_BYTES)throw IOException("Image too large")
                            bytes.write(buffer,0,count)
                        }
                    }
                    Daily60Rules.image(bytes.toByteArray(),connection.contentType.orEmpty())
                } finally {deadline.cancel(false);timer.shutdownNow();connection.disconnect()}
            }
            try {return request.get(budget,TimeUnit.NANOSECONDS)}
            catch(_:java.util.concurrent.TimeoutException){last=java.net.SocketTimeoutException()}
            catch(error:java.util.concurrent.ExecutionException){last=error.cause as? Exception ?: IOException("Image unavailable")}
            catch(_:InterruptedException){Thread.currentThread().interrupt();throw InterruptedIOException()}
            finally {if(!request.isDone)request.cancel(true)}
        }
        throw last ?: IOException("Image unavailable")
        }finally{workers.shutdownNow()}
    }
    companion object {const val ENDPOINT="https://60s.viki.moe/v2/60s?encoding=image-proxy"}
}
