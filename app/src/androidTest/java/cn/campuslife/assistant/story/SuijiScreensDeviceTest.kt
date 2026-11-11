package cn.campuslife.assistant.story

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native Android screenshots; no school login, external browser or real submission. */
class SuijiScreensDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app=inst.targetContext
    private val activities=mutableListOf<Activity>()
    private var original:ByteArray?=null
    private val file get()=File(app.filesDir,StoryStore.FILE_NAME)
    private fun drain() {val done=CountDownLatch(1);StoryRepository.get(app).loadState {done.countDown()};assertTrue(done.await(8,TimeUnit.SECONDS))}
    @Before fun preserveState(){drain();original=if(file.exists())file.readBytes()else null;StoryStore(app).write(StoryLocalState())}
    @After fun restoreState(){inst.runOnMainSync {activities.filterNot {it.isDestroyed}.forEach {it.finish()}};inst.waitForIdleSync();drain();original?.let(file::writeBytes) ?: file.delete()}
    private fun launch(intent:Intent)=inst.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also {activities+=it}
    private fun view(activity:Activity,tag:String):View {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())return activity.window.decorView.findViewWithTag(tag) ?: error("Missing native view: $tag")
        val end=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<end){var found:View?=null;inst.runOnMainSync {found=activity.window.decorView.findViewWithTag(tag)};if(found!=null)return found!!;Thread.sleep(50)}
        error("Missing native view: $tag")
    }
    private fun shot(name:String){inst.waitForIdleSync();Thread.sleep(300);val bitmap=checkNotNull(inst.uiAutomation.takeScreenshot());try{
        val dir=File(app.getExternalFilesDir(null),"suiji-screens").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
    }finally{bitmap.recycle()}}
    @Test fun listAndFirstManuscriptUseTheNewNameAndKeepTheFirstSourceAndFavorite() {
        val list=launch(Intent(app,StoryActivity::class.java));view(list,"story_card_story-suiji-001");shot("01-suiji-list")
        inst.runOnMainSync {assertNull(list.window.decorView.findViewWithTag<View>("story_card_story-001"))}
        val reader=launch(StoryReaderActivity.intent(app,"story-suiji-001"))
        val body=view(reader,"story_reader_body") as TextView
        inst.runOnMainSync {
            assertEquals("查看原文 ↗",(view(reader,"story_source_link") as Button).text.toString())
            assertEquals("作者待核实",(view(reader,"story_reader_author") as TextView).text.toString())
            assertFalse((view(reader,"story_previous") as Button).isEnabled)
            assertTrue((view(reader,"story_next") as Button).isEnabled)
            assertEquals(StoryAsset.load(app.assets).first {it.id=="story-suiji-001"}.readableBody,body.text.toString())
            (view(reader,"story_favorite") as Button).performClick()
        }
        drain();assertTrue("story-suiji-001" in StoryStore(app).read().favorites)
        shot("02-first-private-layout")
    }
    @Test fun aPublicDomainWorkHasFullNativeTextAndSubmissionDraftStillPersists() {
        val reader=launch(StoryReaderActivity.intent(app,"story-suiji-007"));view(reader,"story_reader_body")
        inst.runOnMainSync {
            assertNull(reader.window.decorView.findViewWithTag<View>("story_rights_notice"))
            assertTrue((view(reader,"story_reader_body") as TextView).text.length>1000)
            (view(reader,"story_font_size") as Spinner).setSelection(2)
        }
        shot("03-public-domain-reader")
        inst.runOnMainSync {(view(reader,"story_reader_scroll") as ScrollView).fullScroll(View.FOCUS_DOWN)}
        shot("04-reader-footer")
        val submission=launch(Intent(app,StorySubmissionActivity::class.java));view(submission,"story_draft_title");shot("05-suiji-submission")
        inst.runOnMainSync {
            (view(submission,"story_draft_title") as android.widget.EditText).setText("原有草稿不会丢")
            (view(submission,"story_draft_body") as android.widget.EditText).setText("只验证本机草稿，不提交到真实云端。")
        }
        Thread.sleep(800);drain();assertEquals("原有草稿不会丢",StoryStore(app).read().draft.title)
    }
    @Test fun aLinkOnlyPreviewDoesNotOverwriteAnExistingFullTextReadingPosition() {
        val id="story-suiji-002"
        StoryStore(app).write(StoryLocalState(progress=mapOf(id to .42)))
        val reader=launch(StoryReaderActivity.intent(app,id))
        view(reader,"story_reader_body")
        inst.runOnMainSync { (view(reader,"story_reader_scroll") as ScrollView).fullScroll(View.FOCUS_DOWN) }
        Thread.sleep(600)
        inst.runOnMainSync { reader.finish() }
        inst.waitForIdleSync();drain()
        assertEquals(.42,StoryStore(app).read().progress.getValue(id),.00001)
    }
}
