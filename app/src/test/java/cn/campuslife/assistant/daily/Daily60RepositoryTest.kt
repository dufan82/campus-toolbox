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
}
