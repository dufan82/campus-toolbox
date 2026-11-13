package cn.campuslife.assistant.daily
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
class Daily60RepositoryTest {
 private class Cache(var value:Daily60Snapshot?=null,val fail:Boolean=false):Daily60Cache {
  var writes=0
  override fun read()=value
  override fun write(snapshot:Daily60Snapshot){if(fail)throw IOException();value=snapshot;writes++}
 }
 @Test fun successfulRefreshSavesOriginalBytesAndFetchTime() {
  val c=Cache();val r=Daily60Repository(Daily60Client{Daily60TestImage.image()},c,{1234}).refresh(null)
  assertEquals(1234L,r.snapshot!!.savedAtMillis);assertArrayEquals(Daily60TestImage.bytes,r.snapshot.image.bytes)
  assertEquals(1,c.writes);assertFalse(r.cached)
 }
 @Test fun failedRefreshRetainsGoodImageAndDoesNotLeakInternalDetails() {
  val old=Daily60Snapshot(Daily60TestImage.image(),1000);val c=Cache(old)
  val r=Daily60Repository(Daily60Client{throw IOException("private-detail")},c).refresh(old)
  assertSame(old,r.snapshot);assertSame(old,c.value);assertEquals(0,c.writes);assertTrue(r.cached)
  assertFalse(r.message!!.contains("private-detail"));assertTrue(r.message.contains("已缓存图片"))
 }
 @Test fun emptyFailureDoesNotInventAnImageOrSuccessfulUpdate() {
  val r=Daily60Repository(Daily60Client{throw IOException()},Cache()).refresh(null)
  assertNull(r.snapshot);assertTrue(r.message!!.contains("暂无缓存图片"))
 }
 @Test fun invalidBodyNeverReplacesGoodCache() {
  val old=Daily60Snapshot(Daily60TestImage.image(),1000);val c=Cache(old)
  val r=Daily60Repository(Daily60Client{Daily60Image("<html>error</html>".toByteArray(),"image/png")},c).refresh(old)
  assertSame(old,r.snapshot);assertEquals(0,c.writes)
 }
 @Test fun diskFailureStillShowsFreshImageWithoutClaimingItWasSaved() {
  val old=Daily60Snapshot(Daily60TestImage.image(),1000);val c=Cache(old,true)
  val r=Daily60Repository(Daily60Client{Daily60TestImage.image()},c,{2000}).refresh(old)
  assertEquals(2000L,r.snapshot!!.savedAtMillis);assertFalse(r.cached);assertSame(old,c.value)
  assertTrue(r.message!!.contains("缓存未保存"))
 }
 @Test fun cancellationAfterFetchNeverWrites() {
  val c=Cache();val repo=Daily60Repository(Daily60Client{Thread.currentThread().interrupt();Daily60TestImage.image()},c)
  try{repo.refresh(null);assertEquals(0,c.writes)}finally{Thread.interrupted()}
 }
 @Test fun repeatedPageOpenReusesMemoryInsteadOfReadingAndDecodingDiskAgain() {
  var reads=0;val at=System.currentTimeMillis();val snapshot=Daily60Snapshot(Daily60TestImage.image(),at)
  val cache=object:Daily60Cache{override fun read():Daily60Snapshot{reads++;return snapshot};override fun write(snapshot:Daily60Snapshot){}}
  val repo=Daily60Repository(Daily60Client{error("No request")},cache,{at})
  assertSame(snapshot,repo.readCache());assertSame(snapshot,repo.readCache());assertSame(snapshot,repo.peek());assertEquals(1,reads)
 }
 @Test fun backgroundWarmupAndPageReadCoalesceInsteadOfDownloadingTwice() {
  val at=System.currentTimeMillis();val calls=java.util.concurrent.atomic.AtomicInteger()
  val repo=Daily60Repository(Daily60Client{calls.incrementAndGet();Thread.sleep(80);Daily60TestImage.image()},Cache(),{at})
  val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
  try{val a=pool.submit<Daily60Result>{repo.refresh(null,force=false)};val b=pool.submit<Daily60Result>{repo.refresh(null,force=false)}
   assertSame(a.get().snapshot,b.get().snapshot);assertEquals(1,calls.get())
  }finally{pool.shutdownNow()}
 }
 @Test fun oldOrFutureDatedCacheCannotSuppressFetchingAndManualRefreshStillFetches() {
  val at=java.time.Instant.parse("2026-11-12T10:00:00Z").toEpochMilli();var calls=0
  val repo=Daily60Repository(Daily60Client{calls++;Daily60TestImage.image()},Cache(),{at})
  assertFalse(repo.isFresh(Daily60Snapshot(Daily60TestImage.image(),at+1)))
  val old=Daily60Snapshot(Daily60TestImage.image(),at-86_400_000)
  assertFalse(repo.isFresh(old));repo.refresh(old,force=false);assertEquals(1,calls)
  repo.refresh(repo.peek());assertEquals(2,calls)
 }

}
