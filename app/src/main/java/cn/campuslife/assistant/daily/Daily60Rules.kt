package cn.campuslife.assistant.daily

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Daily60Image(val bytes:ByteArray,val mime:String)
data class Daily60Snapshot(val image:Daily60Image,val savedAtMillis:Long)

object Daily60Rules {
    val zone:ZoneId=ZoneId.of("Asia/Shanghai")
    fun today():LocalDate=LocalDate.now(zone)
    const val MAX_BYTES=5*1024*1024
    fun image(bytes:ByteArray,declaredType:String):Daily60Image {
        require(bytes.size in 24..MAX_BYTES){"图片大小不正确"}
        val mime=when {
            bytes.take(8)==listOf(137,80,78,71,13,10,26,10).map(Int::toByte)->{
                require(bytes.size>=45 && String(bytes,12,4,Charsets.US_ASCII)=="IHDR")
                val width=ByteBuffer.wrap(bytes,16,4).int;val height=ByteBuffer.wrap(bytes,20,4).int
                require(width in 1..8192 && height in 1..24000 && width.toLong()*height<=16_000_000){"图片尺寸过大"}
                require(String(bytes,bytes.size-8,4,Charsets.US_ASCII)=="IEND"){"图片未完整读取"}
                "image/png"
            }
            bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte() && bytes[2]==0xff.toByte()->"image/jpeg"
            String(bytes,0,4,Charsets.US_ASCII)=="RIFF" && String(bytes,8,4,Charsets.US_ASCII)=="WEBP"->"image/webp"
            else->throw IOException("图片源没有返回图片")
        }
        require(declaredType.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)==mime){"图片格式与响应不符"}
        return Daily60Image(bytes,mime)
    }
    fun readingLabel(snapshot:Daily60Snapshot,cached:Boolean):String {
        val time=Instant.ofEpochMilli(snapshot.savedAtMillis).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        return if(cached)"缓存图片 · 获取于 $time" else "获取于 $time"
    }
    fun failure(error:Exception)=when(error){
        is UnknownHostException->"网络连接失败，请检查网络后重试"
        is SocketTimeoutException->"图片加载超时，请重试"
        is javax.net.ssl.SSLException->"安全连接失败，请稍后重试"
        is Daily60HttpException->"图片源暂不可用（${error.status}），请稍后重试"
        is IllegalArgumentException->"图片不完整或格式错误，请重试"
        else->"图片获取失败，请稍后重试"
    }
}
