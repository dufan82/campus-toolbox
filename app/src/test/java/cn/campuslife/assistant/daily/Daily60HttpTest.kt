package cn.campuslife.assistant.daily
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.io.*
class Daily60HttpTest {
 private class Connection(url:URL,val code:Int=200,val body:ByteArray=Daily60TestImage.bytes,val type:String="image/png",val declared:Long=body.size.toLong(),val stream:InputStream=ByteArrayInputStream(body)):HttpURLConnection(url) {
  var closed=false
  override fun connect(){}
  override fun disconnect(){closed=true}
  override fun usingProxy()=false
  override fun getResponseCode()=code
  override fun getContentType()=type
  override fun getContentLengthLong()=declared
  override fun getInputStream()=stream
 }
 @Test fun mailerProxyReturnsExactPngAndClosesConnection() {
  lateinit var c:Connection
  val r=Daily60Http({Connection(it).also{v->c=v}}).read()
  assertEquals(Daily60Http.ENDPOINT,c.url.toString());assertTrue(c.url.query.contains("encoding=image-proxy"))
  assertArrayEquals(Daily60TestImage.bytes,r.bytes);assertTrue(c.closed);assertFalse(c.instanceFollowRedirects)
 }
 @Test fun unavailableProxyUsesOnlyFixedOfficialArtworkMirror() {
  val urls=mutableListOf<String>()
  val r=Daily60Http({urls.add(it.toString());if(urls.size==1)throw UnknownHostException() else Connection(it)},today={java.time.LocalDate.of(2026,11,12)}).read()
  assertEquals(2,urls.size);assertEquals("https://cdn.jsdmirror.com/gh/vikiboss/60s-static-host@main/static/images/2026-11-12.png",urls[1]);assertEquals("image/png",r.mime)
 }
 @Test fun redirectsHttpErrorAndHtmlNeverBecomeSuccessAndRetriesAreBounded() {
  for(code in listOf(302,403,429,500)){
   var count=0
   assertThrows(IOException::class.java){Daily60Http({count++;Connection(it,code)}).read()};assertEquals(2,count)
  }
  assertThrows(Exception::class.java){Daily60Http({Connection(it,body="<html>bad</html>".toByteArray(),type="text/html")}).read()}
 }
 @Test fun oversizedDeclaredOrStreamedBodyCannotAllocateUnboundedMemory() {
  assertThrows(Exception::class.java){Daily60Http({Connection(it,declared=Daily60Rules.MAX_BYTES+1L)}).read()}
  assertThrows(Exception::class.java){Daily60Http({Connection(it,body=ByteArray(Daily60Rules.MAX_BYTES+1),declared=-1)}).read()}
 }
 @Test fun cancellationCannotStartNetworkOrFallback() {
  var count=0;Thread.currentThread().interrupt()
  try{assertThrows(InterruptedIOException::class.java){Daily60Http({count++;Connection(it)}).read()};assertEquals(0,count)}finally{Thread.interrupted()}
 }
 @Test fun wholeRequestDeadlineAlsoBoundsFallback() {
  var clock=0L;var count=0
  val stream=object:ByteArrayInputStream(Daily60TestImage.bytes){override fun read(b:ByteArray,o:Int,l:Int):Int{clock=21_000_000_000;return super.read(b,o,l)}}
  assertThrows(IOException::class.java){Daily60Http({count++;Connection(it,stream=stream)},{clock}).read()};assertEquals(1,count)
 }
 @Test fun blockedPrimaryCannotConsumeTheFallbackBudget() {
  val urls=java.util.concurrent.CopyOnWriteArrayList<String>()
  val r=Daily60Http({url->urls.add(url.toString());if(url.host=="60s.viki.moe")Thread.sleep(2000);Connection(url)},attemptLimitMillis=250).read()
  assertEquals(2,urls.size);assertEquals("image/png",r.mime)
 }
}
