package cn.campuslife.assistant.story

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SuijiContentDeviceTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated():Context {
        val dir=Files.createTempDirectory(app.cacheDir.toPath(),"suiji-data-").toFile()
        return object:ContextWrapper(app){override fun getApplicationContext():Context=this;override fun getFilesDir()=dir}
    }
    @Test fun tenSourceRecordsArePinnedAndOldExamplesRemainOnlyInTheArchive() {
        val all=StoryAsset.load(app.assets)
        val visible=StoryRules.filter(all,null,false,emptySet())
        assertEquals(10,visible.size)
        assertEquals(listOf("story-suiji-001","story-suiji-002","story-suiji-003"),visible.take(3).map {it.id})
        assertEquals(12,all.count {it.provenance.archived})
        assertTrue(visible.all {it.provenance.link!=null})
        assertTrue(visible.filterNot {it.provenance.allowsFullText || it.privateReview}.all {it.body.isEmpty()})
        val old=all.single {it.id=="story-001"}
        assertEquals(listOf(old),StoryRules.filter(all,null,true,setOf(old.id)))
        val first=visible.first()
        assertFalse(first.provenance.allowsFullText)
        assertEquals(cn.campuslife.assistant.BuildConfig.SUIJI_PRIVATE_REVIEW,first.privateReview)
        if(first.privateReview) {
            val raw=app.assets.open("suiji_private_review.json").bufferedReader().use {JSONObject(it.readText())}
            assertTrue("User manuscript must not be rewritten",raw.getJSONArray("reviews").getJSONObject(0).getString("body")==first.readableBody)
        }
    }
    @Test fun oldFavoritesReadingPositionAndUnfinishedSubmissionKeepTheirExistingFileBytes() {
        val context=isolated();val store=StoryStore(context)
        val before=StoryLocalState(setOf("story-001","story-future-999"),mapOf("story-001" to .42),18,StoryDraft("旧草稿",null,"尚未完成","旧笔名",true,false))
        store.write(before);val file=File(context.filesDir,StoryStore.FILE_NAME);val bytes=file.readBytes()
        StoryAsset.load(app.assets)
        assertEquals(before,store.read());assertArrayEquals(bytes,file.readBytes())
    }
    @Test fun freshRestrictedRightsRemoveCachedFullTextBeforeAnyOfflineFallback() {
        val context=isolated();val cache=StoryCloudCache(context)
        val full=Story("story-cloud-rights-change","许可测试","独立介绍",StoryCategory.LIFE,"授权的合成正文。".repeat(50),"测试作者",
            provenance=StoryProvenance(sourceType="other_platform",sourceUrl="https://example.org/original",rights=StoryRights.AUTHORIZED))
        cache.save(full);assertEquals(full.body,cache.read().single().body)
        val restricted=full.copy(provenance=full.provenance.copy(rights=StoryRights.UNVERIFIED))
        cache.applyPublicMetadata(listOf(restricted))
        assertEquals("",cache.read().single().body)
        assertEquals("",cache.resolveDetail(full.id){throw IOException("Offline test")}.body)
        cache.save(restricted.copy(privateReview=true))
        assertFalse(cache.read().single().privateReview);assertEquals("",cache.read().single().body)
    }
    @Test fun publicMetadataNeverImportsAdminRightsEvidenceOrReviewerNotes() {
        val raw=JSONObject().put("sourceType","douyin_comment").put("sourceUrl","https://example.org/post#comment-1")
            .put("rightsStatus","unverified").put("publisherName","原作品发布者").put("commentLocator","评论作者及原评论定位")
            .put("rightsNote","private proof").put("reviewerNote","private review")
        val p=StoryProvenanceJson.decode(raw)
        assertEquals("原作品发布者",p.publisherName)
        val encoded=StoryProvenanceJson.encode(p).toString()
        assertFalse(encoded.contains("private proof"));assertFalse(encoded.contains("private review"))
        assertFalse(StoryProvenanceJson.decode(raw.put("rightsStatus","unknown-future-status")).allowsFullText)
        assertEquals(StoryRights.USER_AUTHORIZED,StoryProvenanceJson.decode(JSONObject().put("sourceType","submission"),true).rights)
    }
    @Test fun originalLinkIntentIsExplicitUserTriggeredHttpsAndNotAnInAppWebRequest() {
        val intent=StorySourceLauncher.intent("https://www.douyin.com/note/7422916853426621732")!!
        assertEquals(Intent.ACTION_VIEW,intent.action);assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals("https://www.douyin.com/note/7422916853426621732",intent.dataString)
        assertNull(StorySourceLauncher.intent("javascript:alert(1)"))
    }
}
