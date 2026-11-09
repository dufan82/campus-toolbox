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
    @Test fun upperBoundPreviewsUseWorkerAndDoNotBuild500CheckboxViews() {
        val local = fixture("Local", 1); val school = fixture("School", 1001)
        val snapshot = ScheduleSnapshot(local, SchoolRegistry())
        val store = ScheduleStore(context); store.replaceWithBackup(store.load(), local)
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use { scenario ->
            await { enabled("登录学校平台") }
            scenario.onActivity { activity ->
                val session = activity.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(activity)
                session.javaClass.getDeclaredField("baselineSnapshot").apply { isAccessible = true }.set(session, snapshot)
                val work = session.javaClass.getDeclaredField("work").apply { isAccessible = true }.get(session) as RetainedWork<*>
                val start = SystemClock.uptimeMillis()
                ScheduleTransferActivity::class.java.getDeclaredMethod("preview", ScheduleData::class.java, String::class.java,
                    List::class.java, ScheduleData::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                    .invoke(activity, school, "Offline 500 x 60", emptyList<String>(), local, true)
                val elapsed = SystemClock.uptimeMillis() - start
                android.util.Log.i("SchedulePerfTest", "preview_return_ms=$elapsed")
                assertTrue("Preview computation must be in flight after returning to UI; call took ${elapsed}ms", work.busy)
            }
            await { enabled("预览仅今日及未来更新") }
            inst.runOnMainSync {
                assertTrue("Preview must not create hundreds of checkboxes", nodes().count { it is CheckBox } < 20)
            }
            scenario.onActivity { activity ->
                val session = activity.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(activity)
                val work = session.javaClass.getDeclaredField("work").apply { isAccessible = true }.get(session) as RetainedWork<*>
                val start = SystemClock.uptimeMillis()
                activity.javaClass.getDeclaredMethod("previewFuture").apply { isAccessible = true }.invoke(activity)
                android.util.Log.i("SchedulePerfTest", "future_return_ms=${SystemClock.uptimeMillis() - start}")
                assertTrue("Future plan and diff must run on worker", work.busy)
            }
            await { enabled("确认未来更新") }
            assertEquals(local, store.load())
        }
    }
    @Test fun legacyChoiceCancelAndRecreationKeepExplicitSelection() {
        val local = fixture("Local", 1); val school = fixture("School", 1001)
        val store = ScheduleStore(context); store.replaceWithBackup(store.load(), local)
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use { scenario ->
            await { enabled("登录学校平台") }
            scenario.onActivity { activity ->
                val session = activity.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(activity)
                session.javaClass.getDeclaredField("baselineSnapshot").apply { isAccessible = true }.set(session, ScheduleSnapshot(local, SchoolRegistry()))
                activity.javaClass.getDeclaredMethod("preview", ScheduleData::class.java, String::class.java,
                    List::class.java, ScheduleData::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                    .invoke(activity, school, "Offline", emptyList<String>(), local, true)
            }
            await { enabled("选择旧课程（已选0/500）") }
            fun chooseFirst() = inst.runOnMainSync {
                val row = nodes().filterIsInstance<CheckedTextView>().first { it.text.contains("Local 1") }
                assertFalse(row.isChecked)
                val list = nodes().filterIsInstance<ListView>().single { it.getPositionForView(row) >= 0 }
                val index = list.getPositionForView(row)
                assertTrue(list.performItemClick(row, index, list.getItemIdAtPosition(index)))
                assertTrue(row.isChecked)
            }
            tap("选择旧课程（已选0/500）"); chooseFirst(); tap("取消")
            await { enabled("选择旧课程（已选0/500）") }
            tap("选择旧课程（已选0/500）"); chooseFirst(); tap("确认选择")
            await { enabled("选择旧课程（已选1/500）") }
            scenario.recreate()
            await { enabled("选择旧课程（已选1/500）") }
            assertEquals(local, store.load())
        }
    }
    @Test fun comparisonFailureDoesNotDisableExplicitWholeReplacement() {
        val local = fixture("Local", 1); val school = fixture("School", 1001)
        val invalid = local.copy(courses = local.courses.mapIndexed { index, course -> if (index == 0) course.copy(weekday = 8) else course })
        val store = ScheduleStore(context); store.replaceWithBackup(store.load(), local)
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use { scenario ->
            await { enabled("登录学校平台") }
            scenario.onActivity { activity ->
                activity.javaClass.getDeclaredMethod("preview", ScheduleData::class.java, String::class.java,
                    List::class.java, ScheduleData::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                    .invoke(activity, school, "Offline", emptyList<String>(), invalid, true)
            }
            await { enabled("确认保存到本机") }
            assertFalse(enabled("预览仅今日及未来更新"))
            assertEquals(local, store.load())
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
