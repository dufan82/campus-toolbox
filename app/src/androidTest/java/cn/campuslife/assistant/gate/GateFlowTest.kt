package cn.campuslife.assistant.gate

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import cn.campuslife.assistant.home.HomeLayoutStore
import cn.campuslife.assistant.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class GateFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    // Literal expectations deliberately do not come from the production route catalog.
    private val expectedRoutes = listOf(
        Triple("gate_east_in", "东门入校", "https://wx.fjpit.com/wechat/api.security/accessGate?xmdm=10066&crlx=1"),
        Triple("gate_east_out", "东门出校", "https://wx.fjpit.com/wechat/api.security/accessGate?xmdm=10067&crlx=2"),
        Triple("gate_north_in", "北门入校", "https://wx.fjpit.com/wechat/api.security/accessGate?xmdm=10062&crlx=1"),
        Triple("gate_north_out", "北门出校", "https://wx.fjpit.com/wechat/api.security/accessGate?xmdm=10063&crlx=2")
    )

    /** Blocks external launches before another app or a school URL can be opened. */
    private class BlockAllViews : Instrumentation.ActivityMonitor() {
        val intents = CopyOnWriteArrayList<Intent>()
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            val wechatHome = intent.action == Intent.ACTION_MAIN &&
                (intent.`package` == "com.tencent.mm" || intent.component?.packageName == "com.tencent.mm")
            if (intent.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND) && !wechatHome) return null
            intents.add(Intent(intent))
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    @Test fun intentsShareOriginalUrlsToWechatWithoutOpeningBrowser() {
        for (route in GateRoute.entries) {
            var captured: Intent? = null
            assertTrue(GateLauncher.launch(route) { captured = it })
            assertEquals(Intent.ACTION_SEND, captured!!.action)
            assertEquals("text/plain", captured!!.type)
            assertEquals(route.url, captured!!.getStringExtra(Intent.EXTRA_TEXT))
            assertNull(captured!!.data)
            assertEquals("com.tencent.mm", captured!!.`package`)
            assertNull(captured!!.component)
        }
    }

    @Test fun missingAppOrRestrictedLaunchReturnsFailureWithoutCrashing() {
        GateRoute.entries.forEach { route ->
            assertFalse(GateLauncher.launch(route) { throw ActivityNotFoundException() })
            assertFalse(GateLauncher.launch(route) { throw SecurityException() })
        }
        assertFalse(GateLauncher.openWechat({ null }) { fail("Missing WeChat must not launch anything") })
        assertFalse(GateLauncher.openWechat({ throw SecurityException() }) { fail("Restricted lookup must not launch anything") })
        assertFalse(GateLauncher.openWechat({ Intent(Intent.ACTION_MAIN) }) { throw ActivityNotFoundException() })
        assertFalse(GateLauncher.openWechat({ Intent(Intent.ACTION_MAIN) }) { throw SecurityException() })
    }

    @Test fun openingWechatDoesNotCarryASchoolUrlOrSendAnything() {
        var requestedPackage: String? = null
        var captured: Intent? = null
        assertTrue(GateLauncher.openWechat({ requestedPackage = it; Intent(Intent.ACTION_MAIN) }) { captured = it })
        assertEquals("com.tencent.mm", requestedPackage)
        assertEquals(Intent.ACTION_MAIN, captured!!.action)
        assertEquals("com.tencent.mm", captured!!.`package`)
        assertNull(captured!!.data)
        assertFalse(captured!!.hasExtra(Intent.EXTRA_TEXT))
    }

    @Test fun missingWechatKeepsThePageUsableAndNeverOpensBrowser() {
        val monitor = BlockAllViews()
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(GateActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    // The dedicated offline emulator intentionally has no WeChat installed.
                    org.junit.Assume.assumeTrue(activity.packageManager.getLaunchIntentForPackage("com.tencent.mm") == null)
                    val button = activity.window.decorView.findViewWithTag<View>("gate_open_wechat")
                    assertNotNull(button)
                    button.performClick()
                    button.performClick()
                }
                assertTrue(monitor.intents.isEmpty())
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun noLaunchOnCreationOrRecreationAndEachClickUsesItsOwnUrl() {
        val monitor = BlockAllViews()
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(GateActivity::class.java).use { scenario ->
                assertEquals(0, monitor.intents.size)
                scenario.recreate()
                assertEquals(0, monitor.intents.size)
                expectedRoutes.forEachIndexed { index, (tag, title, url) ->
                    scenario.onActivity { activity ->
                        val button = activity.window.decorView.findViewWithTag<TextView>(tag)
                        assertNotNull("Missing $title", button)
                        assertEquals(title, button.text.toString())
                        assertTrue(button.height >= (48 * activity.resources.displayMetrics.density).toInt())
                        button.performClick()
                        button.performClick()
                    }
                    assertEquals(index + 1, monitor.intents.size)
                    val intent = monitor.intents[index]
                    assertEquals(Intent.ACTION_SEND, intent.action)
                    assertEquals("com.tencent.mm", intent.`package`)
                    assertEquals("text/plain", intent.type)
                    assertEquals(url, intent.getStringExtra(Intent.EXTRA_TEXT))
                    assertEquals(title, intent.getStringExtra(Intent.EXTRA_TITLE))
                    scenario.moveToState(Lifecycle.State.CREATED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    scenario.recreate()
                    assertEquals("Return/recreation must not repeat $title", index + 1, monitor.intents.size)
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun returningFromAnotherActivityNeverLaunchesAgainUntilAnotherClick() {
        val monitor = BlockAllViews()
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(GateActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("gate_east_in").performClick() }
                assertEquals(1, monitor.intents.size)
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertEquals("Returning must never repeat the launch", 1, monitor.intents.size)
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("gate_east_out").performClick() }
                assertEquals(2, monitor.intents.size)
                assertEquals(GateRoute.EAST_OUT.url, monitor.intents[1].getStringExtra(Intent.EXTRA_TEXT))
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun landscapeKeepsAllFourGateButtonsReachableWithoutStartingAnIntent() {
        val monitor = BlockAllViews()
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(GateActivity::class.java).use { scenario ->
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                val until = android.os.SystemClock.uptimeMillis() + 5000
                var landscape = false
                while (!landscape && android.os.SystemClock.uptimeMillis() < until) {
                    scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    if (!landscape) Thread.sleep(30)
                }
                assertTrue("Landscape must be applied", landscape)
                instrumentation.waitForIdleSync()
                for ((tag, _, _) in expectedRoutes) {
                    scenario.onActivity { activity ->
                        val button = activity.window.decorView.findViewWithTag<View>(tag)
                        assertNotNull(button)
                        button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val button = activity.window.decorView.findViewWithTag<View>(tag)
                        val bounds = Rect()
                        assertTrue("$tag must be reachable by scrolling", button.getGlobalVisibleRect(bounds))
                        assertTrue("$tag needs a usable touch area", bounds.height() >= (48 * activity.resources.displayMetrics.density).toInt())
                    }
                }
                assertTrue("Rotation must never launch a gate URL", monitor.intents.isEmpty())
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun oldHomePreferencesKeepChoicesWithoutRevivingRetiredGate() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("home_layout_v1", 0)
        val original = prefs.getString("layout", null)
        val previous = HomeLayoutStore(context).load()
        val monitor = instrumentation.addMonitor(cn.campuslife.assistant.story.StoryActivity::class.java.name, null, false)
        try {
            assertTrue(prefs.edit().putString("layout", """{"version":1,"order":["planner","watermark","map","schedule"],"hidden":["map","watermark"]}""").commit())
            val restored = HomeLayoutStore(context).load()
            assertEquals(listOf("planner", "schedule", "story", "daily60"), restored.visible())
            assertEquals(setOf("map", "watermark"), restored.hidden)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val until = android.os.SystemClock.uptimeMillis() + 5000
                var ready = false
                while (!ready && android.os.SystemClock.uptimeMillis() < until) {
                    scenario.onActivity { ready = it.window.decorView.findViewWithTag<View>("home_tool_story") != null }
                    if (!ready) Thread.sleep(30)
                }
                assertTrue("New tool must be visible in the upgraded home layout", ready)
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    for (id in listOf("pdd_packages", "pdd_code", "douyin_orders")) {
                        val shortcut = root.findViewWithTag<View>("shortcut_$id")
                        assertNotNull(shortcut)
                        assertTrue(shortcut.isClickable)
                        assertTrue(shortcut.getGlobalVisibleRect(Rect()))
                    }
                    assertNull("Retired gate must not reappear",root.findViewWithTag<View>("home_tool_gate"))
                    root.findViewWithTag<View>("home_tool_story").performClick()
                }
                val page = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                assertNotNull("Home must open the current registered story page", page)
                instrumentation.runOnMainSync { page!!.finish() }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            if (original == null) prefs.edit().remove("layout").commit()
            else prefs.edit().putString("layout", original).commit()
            assertEquals(previous, HomeLayoutStore(context).load())
        }
    }
}
