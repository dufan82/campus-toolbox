package cn.campuslife.assistant

import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.home.*
import cn.campuslife.assistant.map.*
import cn.campuslife.assistant.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test

class HomeAndFavoritesFlowTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun awaitHome(scenario: ActivityScenario<MainActivity>) {
        var ready=false;val until=android.os.SystemClock.uptimeMillis()+5000
        while(!ready && android.os.SystemClock.uptimeMillis()<until) {
            scenario.onActivity { ready=it.window.decorView.findViewWithTag<View>("home_all_tools")!=null }
            if(!ready) Thread.sleep(30)
        }
        assertTrue("Home preferences must finish loading",ready)
    }
    @Test fun allOrdinaryToolRoutesOpenTheirRegisteredActivities() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val routes=listOf("map" to "map.CampusMapActivity","schedule" to "schedule.ScheduleActivity",
            "watermark" to "ui.WatermarkActivity","planner" to "planner.PlannerActivity")
        ActivityScenario.launch(ToolManagementActivity::class.java).use { scenario ->
            for((id,suffix) in routes) {
                var ready=false
                val until=android.os.SystemClock.uptimeMillis()+5000
                while(!ready && android.os.SystemClock.uptimeMillis()<until) {
                    scenario.onActivity { ready=it.window.decorView.findViewWithTag<View>("all_tool_$id")!=null }
                    if(!ready) Thread.sleep(30)
                }
                assertTrue(ready)
                val monitor=instrumentation.addMonitor("cn.campuslife.assistant.$suffix",null,false)
                try {
                    scenario.onActivity { it.window.decorView.findViewWithTag<View>("all_tool_$id").performClick() }
                    val opened=instrumentation.waitForMonitorWithTimeout(monitor,5000)
                    assertNotNull("Tool $id must open its actual registered page",opened)
                    instrumentation.waitForIdleSync();instrumentation.runOnMainSync { opened!!.finish() }
                    instrumentation.waitForIdleSync()
                } finally { instrumentation.removeMonitor(monitor) }
            }
            scenario.onActivity { activity ->
                assertNull(activity.window.decorView.findViewWithTag<View>("all_tool_favorites"))
                assertNull(activity.window.decorView.findViewWithTag<View>("all_tool_batch"))
            }
        }
    }
    @Test fun hidingAllOrdinaryToolsCannotRemoveOrMoveExpressFromFirstScreen() {
        val store=HomeLayoutStore(context);val before=store.load()
        try {
            store.save(before.copy(hidden=before.order.toSet()))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitHome(scenario)
                scenario.onActivity { activity ->
                    val root=activity.window.decorView
                    listOf("pdd_packages","pdd_code","douyin_orders").forEach { id ->
                        val button=root.findViewWithTag<View>("shortcut_$id")
                        val visible=Rect();assertNotNull(button);assertTrue(button.getGlobalVisibleRect(visible))
                        assertEquals(button.width,visible.width());assertEquals(button.height,visible.height());assertTrue(button.isClickable)
                    }
                    assertNull(root.findViewWithTag<View>("open_campus_map"))
                    assertNotNull(root.findViewWithTag<View>("home_all_tools"))
                }
                scenario.recreate()
                awaitHome(scenario)
                scenario.onActivity { assertNotNull(it.window.decorView.findViewWithTag<View>("shortcut_pdd_code")) }
            }
        } finally { store.save(before) }
    }
    @Test fun ordinaryLayoutSurvivesNewStoreInstancesWithoutTouchingLegacyHistory() {
        val home=HomeLayoutStore(context);val previousHome=home.load()
        val history=PlaceHistoryStore(context);val previousHistory=history.load()
        try {
            home.save(HomeLayout.defaults().move("planner",-1).hide("map",true))
            assertEquals(listOf("schedule","planner","watermark"),HomeLayoutStore(context).load().visible())
            ActivityScenario.launch(ToolManagementActivity::class.java).use { }
            assertEquals(previousHistory,PlaceHistoryStore(context).load())
        } finally { home.save(previousHome) }
    }
}
