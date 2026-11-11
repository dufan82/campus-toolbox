package cn.campuslife.assistant.story
import org.junit.Assert.*
import org.junit.Test
class StoryCloudRulesTest {
 @Test fun productionRequiresFixedHttpsAndOfficialAuthOrigin() {
  assertNull(StoryCloudRules.endpoint(""));assertNull(StoryCloudRules.endpoint("http://example.org/api"))
  assertNull(StoryCloudRules.endpoint("https://a:b@example.org/api"));assertNull(StoryCloudRules.endpoint("https://example.org/api?token=x"))
  assertEquals("https://example.org/api",StoryCloudRules.endpoint("https://example.org/api"))
  assertNull(StoryCloudRules.endpoint("https://example.org",auth=true));assertNotNull(StoryCloudRules.endpoint("https://test.api.tcloudbasegateway.com",auth=true))
 }
 @Test fun localMockCannotEscapeLoopbackOrBecomeReleaseDefault() {
  assertNotNull(StoryCloudRules.endpoint("http://10.0.2.2:8787/api",mock=true))
  assertNull(StoryCloudRules.endpoint("http://10.0.2.2:8787/api"))
  assertNull(StoryCloudRules.endpoint("http://example.org:8787/api",mock=true))
 }
 @Test fun cloudIdsCannotReplaceBundledStoryIds() {
  val local=Story("story-001","本地","简介",StoryCategory.LIFE,"内置")
  val remote=local.copy(id="story-cloud-abc",title="云端")
  assertEquals(listOf(local,remote),StoryCloudRules.combine(listOf(local),listOf(local.copy(title="伪造覆盖"),remote,remote)))
 }
 @Test fun sourceKeyNormalizesOriginsAndIsAFixedShortDigest() {
  val key=StoryCloudRules.sourceKey("https://EXAMPLE.org:443/api/","https://TEST.api.tcloudbasegateway.com:443/")
  assertEquals(key,StoryCloudRules.sourceKey("https://example.org/api","https://test.api.tcloudbasegateway.com"))
  assertTrue(key.matches(Regex("[a-f0-9]{24}")))
  assertEquals(StoryCloudRules.sourceKey(null,null),StoryCloudRules.sourceKey("",""))
  assertEquals("b2c41d0aa87fd47e9d1b2df0",StoryCloudRules.sourceKey(null,null))
  assertEquals(StoryCloudRules.sourceKey(null,null),StoryCloudRules.sourceKey("  ",null))
  assertNotEquals(key,StoryCloudRules.sourceKey(null,null))
 }
 @Test fun sourceKeySeparatesApiAuthPathsAndMockSources() {
  val api="https://example.org/api";val auth="https://test.api.tcloudbasegateway.com"
  val key=StoryCloudRules.sourceKey(api,auth)
  assertNotEquals(key,StoryCloudRules.sourceKey("https://other.org/api",auth))
  assertNotEquals(key,StoryCloudRules.sourceKey(api,"https://other.api.tcloudbasegateway.com"))
  assertNotEquals(key,StoryCloudRules.sourceKey("https://example.org/API",auth))
  assertNotEquals(key,StoryCloudRules.sourceKey("https://example.org/api%2Fother",auth))
  assertNotEquals(StoryCloudRules.sourceKey("https://example.org/api/other",auth),StoryCloudRules.sourceKey("https://example.org/api%2Fother",auth))
  val mock=StoryCloudRules.sourceKey("http://127.0.0.1:8787/api","http://127.0.0.1:8787")
  assertNotEquals(key,mock)
  assertNotEquals(mock,StoryCloudRules.sourceKey("http://10.0.2.2:8787/api","http://10.0.2.2:8787"))
  assertNotEquals(StoryCloudRules.sourceKey(api,null),StoryCloudRules.sourceKey(null,api))
 }
 @Test fun firstPageMergeKeepsOtherCachedPagesWithLabelsAndLocalPriority() {
  val local=Story("story-001","本地","简介",StoryCategory.LIFE,"内置")
  val first=local.copy(id="story-cloud-first",title="第一页",body="")
  val other=local.copy(id="story-cloud-other",title="其他页已读",body="缓存正文")
  val result=StoryCloudRules.combine(listOf(local,local),listOf(first,first,local.copy(title="不可替换本地")),listOf(other,first.copy(body="已缓存"),other))
  assertEquals(listOf(local.id,first.id,other.id),result.map {it.id})
  assertEquals(local,result.first());assertEquals(first,result[1])
  assertEquals(StoryCloudRules.CACHED_SOURCE,result.last().source)
  assertEquals(other.body,result.last().body)
 }
 @Test fun knownWithdrawalsNeverReturnThroughOnlineOrOfflineMerges() {
  val story=Story("story-cloud-gone","已撤下","简介",StoryCategory.LIFE,"正文")
  assertTrue(StoryCloudRules.combine(emptyList(),listOf(story),listOf(story),setOf(story.id)).isEmpty())
  assertTrue(StoryCloudRules.combine(emptyList(),emptyList(),listOf(story),setOf(story.id)).isEmpty())
  assertTrue(StoryCloudRules.authoritativeMissing(StoryCloudException("NEW_ERROR_CODE","gone",404)))
  assertTrue(StoryCloudRules.authoritativeMissing(StoryCloudException("STORY_NOT_FOUND","gone")))
  assertFalse(StoryCloudRules.authoritativeMissing(StoryCloudException("NETWORK_ERROR","offline")))
 }
}
