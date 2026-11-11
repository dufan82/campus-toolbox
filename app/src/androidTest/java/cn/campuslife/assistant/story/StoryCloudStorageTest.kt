package cn.campuslife.assistant.story
import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.AtomicJsonFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.io.IOException
class StoryCloudStorageTest {
 private fun isolated():Context {val app=InstrumentationRegistry.getInstrumentation().targetContext;val dir=Files.createTempDirectory(app.cacheDir.toPath(),"cloud-storage-").toFile();return object:ContextWrapper(app){override fun getApplicationContext():Context=this;override fun getFilesDir()=dir}}
 @Test fun outboxKeepsRequestIdentityForExactRetryButNotChangedContent() {
  val app=isolated();val draft=StoryDraft("测试标题",StoryCategory.LIFE,"故事正文".repeat(50),"",true,true)
  val id=StoryOutbox(app).prepare(draft);assertEquals(id,StoryOutbox(app).prepare(draft))
  assertNotEquals(id,StoryOutbox(app).prepare(draft.copy(body=draft.body+"修改")))
 }
 @Test fun futureOutboxNeverOverwritesEvidenceWithNewId() {
  val app=isolated();val name=StoryOutbox(app).fileName
  val file=AtomicJsonFile(app,name);file.write(JSONObject().put("version",999))
  try {StoryOutbox(app).prepare(StoryDraft("测试标题",StoryCategory.LIFE,"正文".repeat(60),"",true,true));fail("Reject unknown schema")}catch(_:IllegalArgumentException){}
  assertEquals(999,file.read()!!.getInt("version"))
 }
 @Test fun onlyReadCloudBodiesAreBoundedAndAuthoritativeRemovalLeavesLocalState() {
  val app=isolated();val cache=StoryCloudCache(app)
  StoryStore(app).write(StoryLocalState(favorites=setOf("story-001")))
  repeat(101){i->cache.save(Story("story-cloud-$i","测试标题","简介",StoryCategory.LIFE,"正文".repeat(60),"匿名","审核发布",provenance=StoryProvenance(sourceType="user_submission",rights=StoryRights.USER_AUTHORIZED)))}
  assertEquals(100,cache.read().size);assertFalse(cache.read().any {it.id=="story-cloud-0"})
  cache.remove("story-cloud-100");assertEquals(99,cache.read().size)
  assertEquals(setOf("story-001"),StoryStore(app).read().favorites)
 }
 private fun story(id:String="story-cloud-withdrawn")=Story(id,"测试标题","简介",StoryCategory.LIFE,"正文".repeat(100),"匿名","审核发布",provenance=StoryProvenance(sourceType="user_submission",rights=StoryRights.USER_AUTHORIZED))
 private fun blockAtomicWrite(app:Context,name:String) {
  val directory=File(app.filesDir,"$name.new");assertTrue(directory.mkdir())
  File(directory,"blocker").writeText("Force AtomicFile.startWrite failure; preserve original")
 }
 private fun assertMissing(cache:StoryCloudCache,id:String) {
  var fetched=false
  val result=runCatching {cache.resolveDetail(id){fetched=true;story(id)}}
  assertTrue(result.exceptionOrNull() is StoryCloudException)
  assertEquals("STORY_NOT_FOUND",(result.exceptionOrNull() as StoryCloudException).code)
  assertFalse(fetched)
 }
 @Test fun authoritative404PersistsIndependentMarkerWhenCacheNewDirectoryBlocksRewrite() {
  val app=isolated();val cache=StoryCloudCache(app);val gone=story();cache.save(gone)
  blockAtomicWrite(app,cache.fileName)
  val result=runCatching {cache.resolveDetail(gone.id){throw StoryCloudException("CUSTOM_404","gone",404)}}
  assertEquals("STORY_NOT_FOUND",(result.exceptionOrNull() as StoryCloudException).code)
  assertNotNull(result.exceptionOrNull()!!.cause)
  assertEquals(1,AtomicJsonFile(app,cache.fileName).read()!!.getJSONArray("stories").length())
  val marker=AtomicJsonFile(app,cache.withdrawnFileName).read()!!
  assertTrue((0 until marker.getJSONArray("ids").length()).any {marker.getJSONArray("ids").getString(it)==gone.id})
  assertTrue(cache.read().isEmpty())
  val reopened=StoryCloudCache(app);assertTrue(reopened.read().isEmpty());assertMissing(reopened,gone.id)
  assertTrue(StoryCloudRules.combine(emptyList(),listOf(gone),emptyList(),reopened.withdrawnIds()).isEmpty())
 }
 @Test fun failedMarkerAndCacheWritesStillBlockAllInstancesInThisProcess() {
  val app=isolated();val cache=StoryCloudCache(app);val gone=story();cache.save(gone)
  blockAtomicWrite(app,cache.fileName);blockAtomicWrite(app,cache.withdrawnFileName)
  val result=runCatching {cache.resolveDetail(gone.id){throw StoryCloudException("STORY_NOT_FOUND","gone",404)}}
  assertEquals("STORY_NOT_FOUND",(result.exceptionOrNull() as StoryCloudException).code)
  assertNotNull(result.exceptionOrNull()!!.cause)
  assertEquals(1,AtomicJsonFile(app,cache.fileName).read()!!.getJSONArray("stories").length())
  assertTrue(cache.read().isEmpty());val reopened=StoryCloudCache(app)
  assertTrue(reopened.read().isEmpty());assertMissing(reopened,gone.id)
  assertTrue(reopened.withdrawnIds().contains(gone.id))
 }
 @Test fun ordinaryWithdrawalPersistsAndNetworkFailureCannotReviveIt() {
  val app=isolated();val cache=StoryCloudCache(app);val gone=story();cache.save(gone)
  cache.remove(gone.id)
  assertTrue(AtomicJsonFile(app,cache.withdrawnFileName).read()!!.getJSONArray("ids").length()>0)
  assertTrue(StoryCloudCache(app).read().isEmpty());assertMissing(StoryCloudCache(app),gone.id)
  val result=runCatching {StoryCloudCache(app).resolveDetail(gone.id){throw IOException("offline")}}
  assertTrue(StoryCloudRules.authoritativeMissing(result.exceptionOrNull()!!))
 }
 @Test fun transientNetworkFailureStillReadsNonWithdrawnCacheFromOtherPages() {
  val app=isolated();val cache=StoryCloudCache(app);val body=story("story-cloud-other-page");cache.save(body)
  assertEquals(body.id,cache.resolveDetail(body.id){throw IOException("offline")}.id)
  val first=story("story-cloud-first-page").copy(body="")
  val catalog=StoryCloudRules.combine(emptyList(),listOf(first),cache.read(),cache.withdrawnIds())
  assertEquals(listOf(first.id,body.id),catalog.map {it.id})
  assertEquals(StoryCloudRules.CACHED_SOURCE,catalog.last().source)
 }
 @Test fun invalidWithdrawalDocumentFailsClosedInsteadOfReadingCachedBodies() {
  val app=isolated();val cache=StoryCloudCache(app);val body=story();cache.save(body)
  AtomicJsonFile(app,cache.withdrawnFileName).write(JSONObject().put("version",999))
  assertTrue(runCatching {cache.read()}.isFailure)
  var fetched=false
  assertTrue(runCatching {cache.resolveDetail(body.id){fetched=true;body}}.isFailure)
  assertFalse(fetched)
 }
 @Test fun sourcesIsolateCacheWithdrawalAndOutboxWithoutMigratingLegacyFiles() {
  val app=isolated();val api="https://example.org/api";val auth="https://test.api.tcloudbasegateway.com"
  val a=StoryCloudCache(app,api,auth);val b=StoryCloudCache(app,api,"https://other.api.tcloudbasegateway.com")
  val changedApi=StoryCloudCache(app,"https://other.org/api",auth)
  val mock=StoryCloudCache(app,"http://127.0.0.1:8787/api","http://127.0.0.1:8787")
  val blank=StoryCloudCache(app,null,null);val body=story()
  val legacyNames=listOf("stories-local-v1.json","stories-cloud-cache-v1.json","stories-mock-cloud-cache-v1.json","story-outbox-v1.json","story-mock-outbox-v1.json")
  val originals=legacyNames.associateWith {name->"legacy unchanged: $name".toByteArray().also {File(app.filesDir,name).writeBytes(it)}}
  a.save(body);assertEquals(body.id,StoryCloudCache(app,"https://EXAMPLE.org:443/api/",auth+"/").read().single().id)
  for(cache in listOf(b,changedApi,mock,blank))assertTrue(cache.read().isEmpty())
  b.save(body);a.remove(body.id);assertEquals(body.id,b.read().single().id)
  val draft=StoryDraft("测试标题",StoryCategory.LIFE,"正文".repeat(100),"",true,true)
  val aBox=StoryOutbox(app,api,auth);val aId=aBox.prepare(draft)
  assertEquals(aId,StoryOutbox(app,"https://EXAMPLE.org:443/api/",auth+"/").prepare(draft))
  val ids=listOf(aId,StoryOutbox(app,api,"https://other.api.tcloudbasegateway.com").prepare(draft),StoryOutbox(app,"https://other.org/api",auth).prepare(draft),StoryOutbox(app,"http://127.0.0.1:8787/api","http://127.0.0.1:8787").prepare(draft),StoryOutbox(app,null,null).prepare(draft))
  assertEquals(5,ids.distinct().size)
  for(name in listOf(a.fileName,a.withdrawnFileName,b.fileName,mock.fileName,blank.fileName,aBox.fileName)) {
   assertTrue(name.length<80);assertTrue(name.matches(Regex("[a-z0-9._-]+")))
  }
  for((name,bytes) in originals)assertArrayEquals(bytes,File(app.filesDir,name).readBytes())
 }
}
