package cn.campuslife.assistant.schedule

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.ui.RetainedWork
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** Synthetic upper-bound timetable; no school login or network requests. */
class SchoolPreviewPerformanceDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private fun fixture(prefix: String, start: Int): ScheduleData {
        val today = LocalDate.now()
        val semester = Semester(today.with(DayOfWeek.MONDAY), 60)
        return ScheduleData(semester, courses = (start until start + 500).map { index ->
            Course("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}", "$prefix $index", "", "",
                today.dayOfWeek.value, 1, 2, 1, 60, WeekPattern.EVERY, null)
        }).checked()
    }
    @Test fun upperBoundSafePreviewRunsOffMainAndLimitsRows() {
        val local=fixture("Local",1);val school=local.copy(courses=local.courses.map {it.copy(classroom="Changed")})
        val store=ScheduleStore(context);store.replaceWithBackup(store.load(),local,true);val snapshot=store.loadSnapshot()
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use {scenario->
            await {enabled("登录学校平台")}
            scenario.onActivity {activity->
                val session=activity.javaClass.getDeclaredField("session").apply {isAccessible=true}.get(activity)
                session.javaClass.getDeclaredField("baselineSnapshot").apply {isAccessible=true}.set(session,snapshot)
                val work=session.javaClass.getDeclaredField("work").apply {isAccessible=true}.get(session) as RetainedWork<*>
                activity.javaClass.getDeclaredMethod("preview",ScheduleData::class.java,String::class.java,List::class.java,ScheduleData::class.java,Boolean::class.javaPrimitiveType).apply {isAccessible=true}.invoke(activity,school,"Offline 500 x 60",emptyList<String>(),local,true)
                assertTrue("Preview must dispatch computation",work.busy)
            }
            await {enabled("更新课表")}
            inst.runOnMainSync {assertTrue(nodes().count {it is CheckBox}<20);assertFalse(nodes().any {it.tag=="schedule.replace.options"})}
            scenario.recreate();await {enabled("更新课表")};assertEquals(snapshot,store.loadSnapshot())
        }
    }
    @Test fun unknownOwnershipNeverOffersLegacyOverrideOrWholeReplacement() {
        val local=fixture("Local",1);val school=fixture("School",1001)
        val store=ScheduleStore(context);store.replaceWithBackup(store.load(),local);val snapshot=store.loadSnapshot()
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use {scenario->
            await {enabled("登录学校平台")}
            scenario.onActivity {activity->
                val session=activity.javaClass.getDeclaredField("session").apply {isAccessible=true}.get(activity)
                session.javaClass.getDeclaredField("baselineSnapshot").apply {isAccessible=true}.set(session,snapshot)
                activity.javaClass.getDeclaredMethod("preview",ScheduleData::class.java,String::class.java,List::class.java,ScheduleData::class.java,Boolean::class.javaPrimitiveType).apply {isAccessible=true}.invoke(activity,school,"Offline",emptyList<String>(),local,true)
            }
            await {var found=false;inst.runOnMainSync {found=nodes().filterIsInstance<android.widget.TextView>().any {it.text.toString()=="没有可安全更新的课程"}};found}
            assertFalse(enabled("更新课表"));assertFalse(enabled("确认保存到本机"));assertEquals(snapshot,store.loadSnapshot())
        }
    }
    private fun tap(label: String) = inst.runOnMainSync {
        val button = nodes().filterIsInstance<Button>().firstOrNull { it.text.toString() == label && it.isEnabled && it.isShown }
        assertNotNull("Missing: $label", button); assertTrue(button!!.performClick())
    }
    private fun nodes(): List<View> = WindowInspector.getGlobalWindowViews().flatMap { root ->
        fun walk(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList()
        walk(root)
    }
    private fun enabled(label: String): Boolean {
        var found = false; inst.runOnMainSync { found = nodes().any { it is Button && it.text.toString() == label && it.isEnabled && it.isShown } }; return found
    }
    private fun await(check: () -> Boolean) {
        val limit = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < limit) { if (check()) return; SystemClock.sleep(40) }
        assertTrue("Timed out", check())
    }
}
