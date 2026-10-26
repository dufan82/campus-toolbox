package cn.campuslife.assistant

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.home.*
import cn.campuslife.assistant.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class HomeRepositoryDeviceTest {
    @Test fun queuedSaveThenRecreationReadAndSecondEditCannotLoseFirstEdit() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=HomeLayoutStore(context);val previous=store.load()
        val tasks=java.util.ArrayDeque<Runnable>()
        val controlled=Executor { tasks.add(it) }
        val oldScreen=HomeLayoutRepository(context,controlled) { it() }
        val recreated=HomeLayoutRepository(context,controlled) { it() }
        try {
            store.save(HomeLayout.defaults())
            var loaded: HomeLayout?=null
            oldScreen.update({ it.hide("map",true) }) { assertTrue(it.isSuccess) }
            recreated.load { loaded=it.getOrThrow() }
            recreated.update({ it.move("planner",-1) }) { assertTrue(it.isSuccess) }
            while(tasks.isNotEmpty()) tasks.removeFirst().run()
            assertTrue("Recreated page must read after the pending save", "map" in loaded!!.hidden)
            assertEquals(listOf("schedule","planner","watermark"),HomeLayoutStore(context).load().visible())
        } finally { store.save(previous) }
    }
    @Test fun corruptHomeSettingsAreVisibleAndNeverOverwrittenByFallback() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("home_layout_v1",0)
        val old=prefs.getString("layout",null)
        try {
            prefs.edit().putString("layout","broken").commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var shown=false;val until=android.os.SystemClock.uptimeMillis()+5000
                while(!shown && android.os.SystemClock.uptimeMillis()<until) {
                    scenario.onActivity { shown=it.window.decorView.findViewWithTag<View>("home_layout_error")!=null }
                    if(!shown) Thread.sleep(30)
                }
                assertTrue("Fallback must explain the unreadable settings",shown)
                assertEquals("broken",prefs.getString("layout",null))
            }
        } finally { prefs.edit().putString("layout",old).commit() }
    }
}
