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
    @Test fun aTruncatedBundledArticleCannotPassTheRecordedFullTextCheck() {
        val raw=app.assets.open("stories.json").bufferedReader().use {JSONObject(it.readText())}
        val rows=raw.getJSONArray("stories")
        val row=(0 until rows.length()).map {rows.getJSONObject(it)}.first {it.optString("id")=="story-suiji-005"}
        assertTrue(row.has("bodyIntegrity"))
        StoryAsset.decode(raw)
        row.put("body",row.getString("body").take(200))
        try {StoryAsset.decode(raw);fail("Truncated full text was accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun sourceCandidatesStayStoredButTheReadingListOnlyContainsFullText() {
        val all=StoryAsset.load(app.assets)
        val visible=StoryRules.filter(all,null,false,emptySet())
        val privateReview=cn.campuslife.assistant.BuildConfig.SUIJI_PRIVATE_REVIEW
        val publicOrder=listOf("story-suiji-007","story-suiji-022","story-suiji-011","story-suiji-021",
            "story-suiji-015","story-suiji-013","story-suiji-012","story-suiji-005")
        assertEquals(if(privateReview)listOf("story-suiji-001")+publicOrder else publicOrder,visible.map {it.id})
        assertEquals(16,all.count {!it.provenance.archived})
        assertTrue(visible.all {it.hasFullText})
        assertEquals(12,all.count {it.provenance.archived})
        assertTrue(visible.all {it.provenance.link!=null})
        assertTrue(visible.filterNot {it.provenance.allowsFullText || it.privateReview}.all {it.body.isEmpty()})
        val old=all.single {it.id=="story-001"}
        assertEquals(listOf(old),StoryRules.filter(all,null,true,setOf(old.id)))
        val first=all.single {it.id=="story-suiji-001"}
        assertFalse(first.provenance.allowsFullText)
        assertEquals(cn.campuslife.assistant.BuildConfig.SUIJI_PRIVATE_REVIEW,first.privateReview)
        if(first.privateReview) {
            val raw=app.assets.open("suiji_private_review.json").bufferedReader().use {JSONObject(it.readText())}
            assertTrue("User manuscript must not be rewritten",raw.getJSONArray("reviews").getJSONObject(0).getString("body")==first.readableBody)
        }
        val birthday=all.single {it.id=="story-suiji-005"}
        assertTrue(birthday.body.length>4700)
        assertEquals(35,StoryTextIntegrity.paragraphCount(birthday.body))
        assertTrue(birthday.body.endsWith("那就享受暂时还没有失去的自由吧。"))
        assertEquals("CC BY-SA 4.0",birthday.provenance.licenseName)
        val back=all.single {it.id=="story-suiji-007"}.body
        assertTrue(back.contains("父亲"));assertFalse(back.contains("父親"));assertFalse(back.contains("眼淚"))
        assertFalse(back.contains("囘"));assertFalse(back.contains("靑"))
    }
    @Test fun everyCuratedBodyHasACompleteMatchingDigestAndClearSource() {
        val rows=app.assets.open("stories.json").bufferedReader().use {JSONObject(it.readText()).getJSONArray("stories")}
        var complete=0
        (0 until rows.length()).forEach {index->
            val row=rows.getJSONObject(index);val body=row.getString("body")
            if(body.isNotBlank()) {
                complete++
                val recorded=row.getJSONObject("bodyIntegrity")
                assertTrue(row.getString("id"),StoryTextIntegrity.matches(body,recorded.getString("sha256"),
                    recorded.getInt("characters"),recorded.getInt("paragraphs")))
                assertTrue(row.getString("sourceUrl").startsWith("https://"))
                assertTrue(row.getString("authorLabel").isNotBlank())
                assertFalse(body.contains("父親"));assertFalse(body.contains("囘"));assertFalse(body.contains("靑"))
                if(row.getString("id") in listOf("story-suiji-011","story-suiji-012","story-suiji-013"))assertFalse(body.contains("著"))
            }
        }
        assertEquals(8,complete)
        val works=StoryAsset.load(app.assets)
        assertTrue(works.single {it.id=="story-suiji-022"}.body.endsWith("母亲下葬了，我没有泪水。"))
        assertTrue(works.single {it.id=="story-suiji-021"}.body.endsWith("后来母亲经常用收音机听歌，收音机用的也很顺手。"))
        assertEquals("CC BY-NC-SA 4.0",works.single {it.id=="story-suiji-021"}.provenance.licenseName)
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
