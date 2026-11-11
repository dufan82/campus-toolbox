package cn.campuslife.assistant.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cn.campuslife.assistant.schedule.*
import cn.campuslife.assistant.home.HomeLayoutStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.Collections

/** Offline only. Measures real hierarchy replacement and app frames, not animation duration.
 * Synthetic timetable is restored byte-for-byte. No school/update/third-party entry is clicked. */
class NavigationRenderPerformanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private fun onMain(block: () -> Unit) = inst.runOnMainSync(block)
    private fun resumed(): Activity? {
        var value: Activity? = null
        onMain { value = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() }
        return value
    }
    private fun awaitActivity(type: Class<out Activity>): Activity {
        val until = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < until) {
            resumed()?.takeIf { type.isInstance(it) }?.let { return it }
            SystemClock.sleep(15)
        }
        throw AssertionError("No resumed ${type.simpleName}")
    }
    private fun settle() { SystemClock.sleep(450); inst.waitForIdleSync() }
    private fun tap(activity: Activity, id: String) = onMain {
        assertTrue(activity.window.decorView.findViewWithTag<View>("bottom_nav_$id").performClick())
    }

    private class Builds(root: ViewGroup) : ViewGroup.OnHierarchyChangeListener {
        var count = 0
        init { root.setOnHierarchyChangeListener(this) }
        override fun onChildViewAdded(parent: View?, child: View?) {
            if ((parent as ViewGroup).indexOfChild(child) == 0) count++
        }
        override fun onChildViewRemoved(parent: View?, child: View?) = Unit
    }

    @Test fun changedLocalSourcesStillRefreshAfterReturningAndRecreating() = isolated {
        val prefs = context.getSharedPreferences("home_layout_v1", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getString("layout", null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val home = awaitActivity(MainActivity::class.java); settle()
                tap(home, "settings"); val settings = awaitActivity(SettingsActivity::class.java); settle()
                val store = HomeLayoutStore(context); val layout = store.load()
                store.save(layout.copy(hidden=layout.hidden+"map"))
                tap(settings, "home"); assertSame(home, awaitActivity(MainActivity::class.java)); settle()
                onMain { assertNull(home.window.decorView.findViewWithTag<View>("open_campus_map")) }
                scenario.recreate(); val recreated = awaitActivity(MainActivity::class.java); settle()
                assertNotSame(home, recreated)
                onMain { assertNull(recreated.window.decorView.findViewWithTag<View>("open_campus_map")) }
            }
        } finally { assertTrue(prefs.edit().putString("layout", previous).commit()) }
        ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
            val schedule = awaitActivity(ScheduleActivity::class.java); settle()
            tap(schedule, "settings"); val settings = awaitActivity(SettingsActivity::class.java); settle()
            val store = ScheduleStore(context); val before = store.load()
            store.commit(before, before.update(before.courses.first().copy(name="离线更新后的课程")))
            tap(settings, "schedule"); assertSame(schedule, awaitActivity(ScheduleActivity::class.java)); settle()
            fun assertChanged(activity: Activity) = onMain {
                fun hasName(view: View): Boolean = view.contentDescription?.contains("离线更新后的课程") == true ||
                    (view is ViewGroup && (0 until view.childCount).any { hasName(view.getChildAt(it)) })
                assertTrue("Refreshed timetable did not display changed local data", hasName(activity.window.decorView))
            }
            assertChanged(schedule)
            scenario.recreate(); val recreated = awaitActivity(ScheduleActivity::class.java); settle()
            assertNotSame(schedule, recreated); assertChanged(recreated)
        }
    }

    @Test fun reusedTabsDoNotRebuildMoreThanOncePerFrameForOneRefresh() = isolated {
        val probe = InstrumentationRegistry.getArguments().getString("navProbe") == "true"
        val frames = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val thread = HandlerThread("nav-frame-metrics").apply { start() }
        val windows = mutableListOf<Window>()
        var created = 0; var destroyed = 0
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION) to metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
        }
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, state: Bundle?) {
                if (a is MainActivity || a is ScheduleActivity || a is SettingsActivity) {
                    created++; windows.add(a.window)
                    a.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
                }
            }
            override fun onActivityDestroyed(a: Activity) { if (a is MainActivity || a is ScheduleActivity || a is SettingsActivity) destroyed++ }
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
        }
        val app = context.applicationContext as Application
        onMain { app.registerActivityLifecycleCallbacks(callbacks) }
        val scheduleCounts = mutableListOf<Int>(); val homeCounts = mutableListOf<Int>()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val home = awaitActivity(MainActivity::class.java); settle()
                lateinit var homeBuilds: Builds
                onMain { homeBuilds = Builds(home.window.decorView.findViewWithTag("home_tools")) }
                tap(home, "schedule")
                val schedule = awaitActivity(ScheduleActivity::class.java); settle()
                lateinit var scheduleBuilds: Builds
                onMain { scheduleBuilds = Builds((schedule.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ViewGroup) }
                repeat(10) {
                    tap(schedule, "settings"); val settings = awaitActivity(SettingsActivity::class.java); settle()
                    val before = scheduleBuilds.count
                    tap(settings, "schedule"); assertSame(schedule, awaitActivity(ScheduleActivity::class.java)); settle()
                    scheduleCounts.add(scheduleBuilds.count - before)
                }
                tap(schedule, "home"); assertSame(home, awaitActivity(MainActivity::class.java)); settle()
                repeat(10) {
                    tap(home, "settings"); val settings = awaitActivity(SettingsActivity::class.java); settle()
                    val before = homeBuilds.count
                    tap(settings, "home"); assertSame(home, awaitActivity(MainActivity::class.java)); settle()
                    homeCounts.add(homeBuilds.count - before)
                }
                scenario.onActivity { assertSame(home, it) }
            }
        } finally {
            onMain { windows.forEach { it.removeOnFrameMetricsAvailableListener(listener) }; app.unregisterActivityLifecycleCallbacks(callbacks) }
            thread.quitSafely(); thread.join(2000)
            val samples = synchronized(frames) { frames.toList() }
            val result = JSONObject().put("scheduleRebuilds", JSONArray(scheduleCounts)).put("homeRebuilds", JSONArray(homeCounts))
                .put("activityCreated", created).put("activityDestroyed", destroyed)
                .put("totalFrameNs", JSONArray(samples.map { it.first })).put("layoutMeasureNs", JSONArray(samples.map { it.second }))
                .put("note", "API36 x86_64 software GPU; app frames only, not SurfaceFlinger animation smoothness or ARM phone acceptance")
            File(context.getExternalFilesDir("verification"), "nav-render-${if(probe) "probe" else "regression"}.json")
                .writeText(result.toString(2))
        }
        if (!probe) {
            assertTrue("Unchanged home results replaced the tool views: $homeCounts", homeCounts.all { it == 0 })
            assertTrue("One refresh rebuilt the whole timetable repeatedly: $scheduleCounts", scheduleCounts.all { it <= 2 })
        }
    }

    private fun isolated(block: () -> Unit) {
        val names = listOf(ScheduleStore.FILE_NAME, ScheduleStore.IMPORT_UNDO_FILE)
        val saved = names.associateWith { name -> File(context.filesDir, name).takeIf { it.exists() }?.readBytes() }
        try {
            val start = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val courses = (0 until 35).map { i -> Course("00000000-0000-0000-0000-${(i+1).toString(16).padStart(12,'0')}",
                "合成课程${i+1}", "合成教师", "测试教室", i%7+1, (i/7)*2+1, (i/7)*2+2,
                1, 20, WeekPattern.EVERY, null) }
            val store = ScheduleStore(context)
            store.replaceWithBackup(store.load(), ScheduleData(Semester(start,20), courses=courses).checked())
            block()
        } finally {
            saved.forEach { (name, bytes) -> val f=File(context.filesDir,name); if(bytes==null) f.delete() else f.writeBytes(bytes) }
        }
    }
}
