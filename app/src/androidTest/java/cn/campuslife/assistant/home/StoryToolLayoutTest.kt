package cn.campuslife.assistant.home

import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class StoryToolLayoutTest {
    @Test fun oldLayoutKeepsOrderWhileNewStoryCanMoveHideRestoreAndOpenFromAllTools() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val prefs=context.getSharedPreferences("home_layout_v1",0);val before=prefs.getString("layout",null)
        val old="""{"version":1,"order":["planner","map","schedule","watermark"],"hidden":["map"]}"""
        try {
            assertTrue(prefs.edit().putString("layout",old).commit())
            val store=HomeLayoutStore(context)
            assertEquals(listOf("planner","map","schedule","watermark","story"),store.load().order)
            assertEquals(setOf("map"),store.load().hidden);assertEquals(old,prefs.getString("layout",null))
            val intent=Intent(context,ToolManagementActivity::class.java).putExtra("manage",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<ToolManagementActivity>(intent).use {s->
                fun waitTag(tag:String) {val end=SystemClock.uptimeMillis()+8000;var ready=false;while(!ready && SystemClock.uptimeMillis()<end){s.onActivity {ready=it.window.decorView.findViewWithTag<View>(tag)?.isEnabled==true};if(!ready)SystemClock.sleep(40)};assertTrue(tag,ready)}
                fun tap(tag:String){waitTag(tag);s.onActivity {assertTrue(it.window.decorView.findViewWithTag<View>(tag).performClick())}}
                tap("tool_story_0");waitTag("tool_story_2");assertEquals("story",store.load().order[3])
                tap("tool_story_2");waitTag("tool_story_2");assertTrue("story" in store.load().hidden)
                s.recreate();waitTag("tool_story_2");assertTrue("story" in store.load().hidden)
                tap("tool_story_2");waitTag("tool_story_2");assertFalse("story" in store.load().hidden)
                assertTrue("map" in store.load().hidden)
            }
            ActivityScenario.launch(ToolManagementActivity::class.java).use {s->
                val end=SystemClock.uptimeMillis()+8000;var ready=false
                while(!ready && SystemClock.uptimeMillis()<end){s.onActivity {ready=it.window.decorView.findViewWithTag<View>("all_tool_story")!=null};if(!ready)SystemClock.sleep(40)}
                assertTrue(ready)
                val monitor=inst.addMonitor("cn.campuslife.assistant.story.StoryActivity",null,false)
                try {s.onActivity {assertTrue(it.window.decorView.findViewWithTag<View>("all_tool_story").performClick())};val opened=inst.waitForMonitorWithTimeout(monitor,5000);assertNotNull(opened);inst.runOnMainSync {opened!!.finish()}}
                finally {inst.removeMonitor(monitor)}
            }
        } finally {assertTrue(prefs.edit().apply {if(before==null)remove("layout")else putString("layout",before)}.commit())}
    }
}
