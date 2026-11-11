package cn.campuslife.assistant.story

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.AtomicJsonFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StoryStorageDeviceTest {
    private fun isolated(): Context {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(base.cacheDir,"story-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object: ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = dir
        }
    }
    @Test fun bundledStoriesAreIndependentOriginalAndSubstantial() {
        val entries=StoryAsset.load(InstrumentationRegistry.getInstrumentation().targetContext.assets)
        assertEquals(12,entries.size)
        assertEquals(12,entries.map { it.id }.toSet().size)
        assertEquals(StoryCategory.entries.filter { it!=StoryCategory.OTHER }.toSet(),entries.map { it.category }.toSet())
        entries.forEach {
            assertTrue(StoryRules.validId(it.id))
            assertTrue("${it.id}: ${StoryRules.characterCount(it.body)}",StoryRules.characterCount(it.body) in 400..900)
            assertEquals("原创短篇",it.authorLabel)
            assertEquals("本项目创作",it.source)
        }
    }
    @Test fun unknownIdsProgressFontAndUnfinishedDraftSurviveRoundTrip() {
        val store=StoryStore(isolated())
        val state=StoryLocalState(setOf("story-future-999"),mapOf("story-future-999" to .42),18,
            StoryDraft("未写完",null,"草稿", "",true,false))
        store.write(state)
        assertEquals(state,store.read())
    }
    @Test fun runtimeAcceptsLongReviewedContentAndOtherSourcesButNotBadSchemaOrIds() {
        fun document()=JSONObject().put("version",1).put("stories",org.json.JSONArray().put(JSONObject()
            .put("id","story-long-reviewed").put("title","审核稿件").put("summary","说明")
            .put("category","OTHER").put("body","字".repeat(5000)).put("authorLabel","测试署名").put("source","合成测试来源")))
        assertEquals(5000,StoryAsset.decode(document()).single().body.length)
        listOf(2,"1").forEach { version -> assertThrows(Exception::class.java) { StoryAsset.decode(document().put("version",version)) } }
        val invalid=document().apply { getJSONArray("stories").getJSONObject(0).put("id","poem-001") }
        assertThrows(Exception::class.java) { StoryAsset.decode(invalid) }
    }
    @Test fun corruptOrFutureStateIsPreservedAndCannotBeOverwrittenByRepository() {
        val context=isolated()
        val file=AtomicJsonFile(context,StoryStore.FILE_NAME)
        file.write(JSONObject().put("version",999))
        val failed=CountDownLatch(1)
        StoryRepository.get(context).change({ StoryLocalState() }) { result ->
            assertTrue(result.isFailure); failed.countDown()
        }
        assertTrue(failed.await(5,TimeUnit.SECONDS))
        assertEquals(999,file.read()!!.getInt("version"))
    }
    @Test fun asyncChangesMergeInsteadOfOverwritingOtherReadingState() {
        val context=isolated()
        val repo=StoryRepository.get(context)
        val done=CountDownLatch(2)
        repo.change({ it.copy(favorites=it.favorites+"story-001") }) { assertTrue(it.isSuccess); done.countDown() }
        repo.change({ it.copy(fontSp=18, progress=it.progress+("story-002" to .65)) }) { assertTrue(it.isSuccess); done.countDown() }
        assertTrue(done.await(5,TimeUnit.SECONDS))
        val state=StoryStore(context).read()
        assertEquals(setOf("story-001"),state.favorites)
        assertEquals(.65,state.progress.getValue("story-002"),0.0)
        assertEquals(18,state.fontSp)
    }
    @Test fun draftAndReadingStorageNeverTouchLegacyFiles() {
        val context=isolated()
        val sentinel=File(context.filesDir,"schedule-sentinel.json").apply { writeText("untouched") }
        StoryStore(context).write(StoryLocalState(draft=StoryDraft(body="只在本机")))
        assertEquals("untouched",sentinel.readText())
        assertEquals(setOf(StoryStore.FILE_NAME,"schedule-sentinel.json"),context.filesDir.list()!!.toSet())
    }
}
