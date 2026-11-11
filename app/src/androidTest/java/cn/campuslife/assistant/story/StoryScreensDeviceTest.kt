package cn.campuslife.assistant.story

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Requires host manifest integration and a dedicated disposable emulator.
 * Only this module's file is snapshotted/restored; no legacy user stores are accessed.
 */
class StoryScreensDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val activities=mutableListOf<Activity>()
    private val original=mutableMapOf<File,ByteArray?>()
    private fun drain() {
        val done=CountDownLatch(1)
        StoryRepository.get(context).loadState { done.countDown() }
        assertTrue(done.await(5,TimeUnit.SECONDS))
    }
    @Before fun preserveOnlyStoryFile() {
        drain()
        listOf("",".bak",".new").forEach { suffix ->
            val file=File(context.filesDir,StoryStore.FILE_NAME+suffix)
            original[file]=if (file.exists()) file.readBytes() else null
        }
        StoryStore(context).write(StoryLocalState())
    }
    @After fun restoreOnlyStoryFile() {
        instrumentation.runOnMainSync { activities.filterNot { it.isDestroyed }.forEach { it.finish() } }
        instrumentation.waitForIdleSync(); drain()
        original.forEach { (file,bytes) -> if (bytes==null) file.delete() else file.writeBytes(bytes) }
    }
    private fun launch(intent: Intent): Activity = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)).also { activities+=it }
    private fun view(activity: Activity,tag: String): View {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime()<deadline) {
            var found: View?=null
            instrumentation.runOnMainSync { found=activity.window.decorView.findViewWithTag(tag) }
            if (found!=null) return found!!
            Thread.sleep(40)
        }
        throw AssertionError("Missing story view: $tag")
    }
    private fun recreate(activity: Activity): Activity {
        val monitor=instrumentation.addMonitor(activity.javaClass.name,null,false)
        try {
            instrumentation.runOnMainSync { activity.recreate() }
            return (instrumentation.waitForMonitorWithTimeout(monitor,8000) ?: error("Recreation timed out")).also { activities+=it }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(450)
        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
        try {
            val dir=File(context.getExternalFilesDir(null),"reading-screens").apply { mkdirs() }
            File(dir,"$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }
    @Test fun listFlowHasNoIntroOrUnreadBoilerplate() {
        val activity=launch(Intent(context,StoryActivity::class.java))
        view(activity,"story_card_story-001")
        instrumentation.runOnMainSync {
            fun inspect(node: View) {
                if (node is TextView) {
                    assertFalse(node.text.contains("尚未阅读"))
                    assertFalse(node.text.contains("给日常留一小段故事"))
                    assertFalse(node.text.contains("点开继续阅读"))
                }
                if (node is android.view.ViewGroup) for (index in 0 until node.childCount) inspect(node.getChildAt(index))
            }
            inspect(activity.window.decorView)
        }
        screenshot("story-list")
    }
    @Test fun submissionKeepsUnfinishedDraftAndConsentsAfterRecreation() {
        var activity=launch(Intent(context,StorySubmissionActivity::class.java))
        val title=view(activity,"story_draft_title") as EditText
        val body=view(activity,"story_draft_body") as EditText
        val category=view(activity,"story_draft_category") as Spinner
        screenshot("story-submission")
        instrumentation.runOnMainSync { (activity.window.decorView.findViewWithTag<View>("story_submission_scroll") as ScrollView).fullScroll(View.FOCUS_DOWN) }
        screenshot("story-submission-rights")
        instrumentation.runOnMainSync {
            title.setText("尚未完成的故事"); body.setText("这是未满一百字的本机草稿")
            category.setSelection(5)
            (activity.window.decorView.findViewWithTag<View>("story_draft_rights") as CheckBox).isChecked=true
        }
        activity=recreate(activity)
        val restored=view(activity,"story_draft_body") as EditText
        screenshot("story-submission-restored")
        instrumentation.runOnMainSync {
            assertEquals("这是未满一百字的本机草稿",restored.text.toString())
            assertEquals("尚未完成的故事",(activity.window.decorView.findViewWithTag<View>("story_draft_title") as EditText).text.toString())
            assertEquals(5,(activity.window.decorView.findViewWithTag<View>("story_draft_category") as Spinner).selectedItemPosition)
            assertTrue((activity.window.decorView.findViewWithTag<View>("story_draft_rights") as CheckBox).isChecked)
            assertNull(activity.window.decorView.findViewWithTag<View>("story_draft_permission"))
            (activity.window.decorView.findViewWithTag<View>("story_draft_validate") as Button).performClick()
            val status=(activity.window.decorView.findViewWithTag<View>("story_draft_status") as TextView).text.toString()
            assertTrue(status.contains("100–5000"))
            if(StoryCloudConfig.configured)assertNotNull(activity.window.decorView.findViewWithTag<View>("story_submit"))else assertNull(activity.window.decorView.findViewWithTag<View>("story_submit"))
        }
    }
    @Test fun readerKeepsFontAndProgressAndUnknownIdReturnsToList() {
        var activity=launch(StoryReaderActivity.intent(context,"story-001"))
        view(activity,"story_reader_body"); screenshot("story-reader")
        instrumentation.runOnMainSync {
            (activity.window.decorView.findViewWithTag<View>("story_favorite") as Button).performClick()
            (activity.window.decorView.findViewWithTag<View>("story_font_size") as Spinner).setSelection(2)
        }
        val until=System.currentTimeMillis()+6000
        while(System.currentTimeMillis()<until) {
            drain(); val state=StoryStore(context).read()
            if("story-001" in state.favorites && state.fontSp==18)break
            Thread.sleep(40)
        }
        assertTrue("story-001" in StoryStore(context).read().favorites);assertEquals(18,StoryStore(context).read().fontSp)
        instrumentation.waitForIdleSync();Thread.sleep(150)
        instrumentation.runOnMainSync {
            val scroll=activity.window.decorView.findViewWithTag<ScrollView>("story_reader_scroll")
            scroll.scrollTo(0,((scroll.getChildAt(0).height-scroll.height)*.6).toInt())
        }
        Thread.sleep(650);drain()
        assertTrue(StoryStore(context).read().progress.getValue("story-001") in 0.5..0.7)
        activity=recreate(activity)
        val body=view(activity,"story_reader_body") as TextView
        instrumentation.runOnMainSync {
            assertEquals(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,18f,context.resources.displayMetrics),body.textSize,.2f)
            assertEquals(2,(activity.window.decorView.findViewWithTag<View>("story_font_size") as Spinner).selectedItemPosition)
            assertEquals("已收藏",(activity.window.decorView.findViewWithTag<View>("story_favorite") as Button).text.toString())
        }
        drain();assertTrue(StoryStore(context).read().progress.getValue("story-001")>0.0)
        val list=launch(Intent(context,StoryActivity::class.java));view(list,"story_card_story-001")
        instrumentation.runOnMainSync {(list.window.decorView.findViewWithTag<View>("story_favorites_only") as CheckBox).isChecked=true}
        assertNotNull(view(list,"story_card_story-001"))
        instrumentation.runOnMainSync {assertNull(list.window.decorView.findViewWithTag<View>("story_card_story-002"))}
        val unknown=launch(StoryReaderActivity.intent(context,"story-future-999"))
        view(unknown,"story_back_list_unknown")
    }
}
