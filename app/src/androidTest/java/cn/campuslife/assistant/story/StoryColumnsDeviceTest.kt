package cn.campuslife.assistant.story

import cn.campuslife.assistant.testing.DeviceTestUi
import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.MotionEvent
import android.widget.ScrollView
import android.widget.Button
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.TimeUnit
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class StoryColumnsDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private fun launch()=inst.startActivitySync(Intent(inst.targetContext,StoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
    private fun view(activity:Activity,tag:String):View=DeviceTestUi.find(activity,tag)

    private fun capture(name:String) {
        inst.waitForIdleSync();Thread.sleep(220)
        val bitmap=checkNotNull(inst.uiAutomation.takeScreenshot())
        try {val dir=File(inst.targetContext.getExternalFilesDir(null),"story-columns").apply {mkdirs()}
            File(dir,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        }finally {bitmap.recycle()}
    }
    @Test fun allCategoryIsReplacedByThreeNamedSourceTabs() {
        val activity=launch()
        try {
            inst.runOnMainSync {
                for(name in listOf("network","submissions","literature"))
                    assertNotNull("Missing source tab: $name",activity.window.decorView.findViewWithTag<View>("story_tab_$name"))
                assertNull(activity.window.decorView.findViewWithTag<View>("story_filter"))
            }
        } finally {inst.runOnMainSync {activity.finish()}}
    }
    @Test fun networkLiteratureAndReviewedSubmissionsStaySeparateAndTabsSurviveRecreation() {
        var activity=launch()
        try {
            view(activity,"story_card_story-suiji-002")
            inst.runOnMainSync {
                val network=view(activity,"story_scroll_network")
                assertNotNull(network.findViewWithTag<View>("story_card_story-suiji-002"))
                assertNotNull(network.findViewWithTag<View>("story_card_story-suiji-003"))
                assertNull(network.findViewWithTag<View>("story_card_story-suiji-007"))
                val submissions=view(activity,"story_scroll_submissions")
                assertNull(submissions.findViewWithTag<View>("story_card_story-suiji-002"))
                assertNull(submissions.findViewWithTag<View>("story_card_story-suiji-007"))
            }
            capture("01-network")
            inst.runOnMainSync {view(activity,"story_tab_submissions").performClick()}
            capture("02-submissions")
            inst.runOnMainSync {view(activity,"story_tab_literature").performClick()}
            capture("03-literature")
            activity=DeviceTestUi.recreate(activity)
            view(activity,"story_card_story-suiji-007")
            inst.runOnMainSync {assertEquals(2,(view(activity,"story_pager") as StoryPager).selected)}
        }finally {inst.runOnMainSync {activity.finish()}}
    }
    @Test fun horizontalSwipesSwitchColumnsButVerticalReadingDoesNot() {
        val a=launch()
        try {
            view(a,"story_card_story-suiji-002")
            val pager=view(a,"story_pager") as StoryPager
            fun gesture(horizontal:Boolean,back:Boolean=false) {
                val start=android.os.SystemClock.uptimeMillis()
                inst.runOnMainSync {
                    val w=pager.width.toFloat();val h=pager.height.toFloat()
                    for(i in 0..10) {
                        val fraction=i/10f
                        val x=if(horizontal)w*(if(back).25f+.5f*fraction else .75f-.5f*fraction) else w*.5f
                        val y=if(horizontal)h*.45f else h*(.75f-.5f*fraction)
                        val e=MotionEvent.obtain(start,start+i*22L,when(i){0->MotionEvent.ACTION_DOWN;10->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE},x,y,0)
                        pager.dispatchTouchEvent(e);e.recycle()
                    }
                }
                Thread.sleep(260);inst.waitForIdleSync()
            }
            gesture(false);inst.runOnMainSync {assertEquals(0,pager.selected)}
            gesture(true);inst.runOnMainSync {assertEquals(1,pager.selected)}
            gesture(true);inst.runOnMainSync {assertEquals(2,pager.selected)}
            gesture(true,true);inst.runOnMainSync {assertEquals(1,pager.selected)}
        }finally {inst.runOnMainSync {a.finish()}}
    }
    @Test fun lastDouyinArticleDoesNotJumpIntoClassics() {
        val a=inst.startActivitySync(StoryReaderActivity.intent(inst.targetContext,"story-suiji-003").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        try {
            view(a,"story_reader_body")
            inst.runOnMainSync {assertFalse((view(a,"story_next") as Button).isEnabled)}
        }finally {inst.runOnMainSync {a.finish()}}
    }
}
