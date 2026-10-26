package cn.campuslife.assistant.schedule

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.ListView
import android.widget.ScrollView
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@SdkSuppress(minSdkVersion = 29)
class ScheduleReadabilityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val course = Course("00000000-0000-0000-0000-000000000abc", "字体与版式设计", "合成教师", "合成教室203", 1, 1, 2, 1, 20, WeekPattern.EVERY, null)

    @Test fun consecutivePeriodsShowOneBlockAndOpenReadOnlyDetailsThatSurviveRecreation() = isolated {
        ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
            orient(scenario)
            await { mainCheck { nodes().filterIsInstance<Button>().any { it.contentDescription?.contains(course.name) == true && it.isEnabled } } }
            instrumentation.runOnMainSync {
                val blocks = nodes().filterIsInstance<Button>().filter { it.contentDescription?.contains(course.name) == true }
                assertEquals("A consecutive course must only appear once", 1, blocks.size)
                assertTrue(blocks.single().width >= 48 * context.resources.displayMetrics.density)
                assertTrue(blocks.single().height >= 48 * context.resources.displayMetrics.density)
                assertTrue("Course text needs an inner gutter", blocks.single().paddingLeft >= (5 * context.resources.displayMetrics.density).toInt())
                assertTrue(nodes().filterIsInstance<TextView>().any { it.text.toString() == "第1节" })
                assertFalse(nodes().filterIsInstance<TextView>().any { it.text.toString().startsWith("1. ") })
                assertTrue(blocks.single().isEnabled)
                assertTrue(blocks.single().performClick())
            }
            await { text("课程详情") }
            assertDetails()
            capture("schedule-v164-details.png")
            scenario.recreate()
            await { text("课程详情") }; assertDetails()
            tap("关闭")
            capture("schedule-v164-week.png")
            instrumentation.runOnMainSync {
                val grid = nodes().filterIsInstance<ScheduleWeekView>().single()
                val scroll = nodes().filterIsInstance<ScrollView>().single()
                val bounds = Rect(0, 0, grid.width, grid.height)
                scroll.offsetDescendantRectToMyCoords(grid, bounds)
                scroll.scrollTo(0, bounds.top)
            }
            capture("schedule-v164-week-grid.png")
        }
    }

    @Test fun managementCollectsInfrequentActionsAndKeepsExistingRecordsUntouched() = isolated {
        val before = ScheduleStore(context).load()
        ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
            orient(scenario)
            await { mainCheck { nodes().filterIsInstance<Button>().any { it.text == "管理" && it.isEnabled } } }
            instrumentation.runOnMainSync {
                val labels = nodes().filterIsInstance<Button>().map { it.text.toString() }
                assertFalse(labels.contains("新增课程"))
                assertFalse(labels.contains("学校导入 / 备份与恢复"))
            }
            tap("管理")
            await { mainCheck {
                nodes().filterIsInstance<ListView>().any { list ->
                    val labels = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
                    labels.containsAll(listOf("新增课程", "学校导入 / 备份与恢复", "全部课程"))
                }
            } }
            tap("取消")
            assertEquals(before, ScheduleStore(context).load())
        }
    }

    @Test fun overlappingCoursesStaySeparatelyReadableAndDetailsOfferEditing() = isolated {
        val store = ScheduleStore(context)
        val before = store.load()
        val overlap = course.copy(id = "00000000-0000-0000-0000-000000000ac1", name = "重叠课程的完整名称", classroom = "", teacher = "", startPeriod = 2, endPeriod = 3)
        store.commit(before, before.copy(courses = before.courses + overlap))
        ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
            orient(scenario)
            await { mainCheck { nodes().any { it.tag == "schedule.overlap" && it.isEnabled } } }
            instrumentation.runOnMainSync {
                val block = nodes().first { it.tag == "schedule.overlap" }
                assertTrue(block.isEnabled); assertTrue(block.performClick())
            }
            await { text("选择重叠的课程") }
            tap("${overlap.name} · 第2–3节")
            try { await { text("课程详情") } } catch (error: AssertionError) {
                capture("schedule-v164-overlap-diagnostic.png"); throw error
            }
            instrumentation.runOnMainSync {
                assertTrue(nodes().filterIsInstance<TextView>().any { it.text.toString() == overlap.name })
                assertEquals(2, nodes().filterIsInstance<TextView>().count { it.text.toString() == "未填写" })
            }
            tap("编辑课程")
            await { mainCheck { nodes().filterIsInstance<EditText>().any { it.text.toString() == overlap.name } } }
            tap("取消")
            assertEquals(before.courses + overlap, store.load().courses)
        }
    }

    @Test fun resumedDetailsFollowCurrentRecordAndExplainDeletion() = isolated {
        val store = ScheduleStore(context)
        ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
            await { mainCheck { nodes().any { it.tag == "schedule.course.${course.id}" && it.isEnabled } } }
            instrumentation.runOnMainSync { nodes().first { it.tag == "schedule.course.${course.id}" }.performClick() }
            await { text("课程详情") }
            scenario.moveToState(Lifecycle.State.CREATED)
            val original = store.load()
            val renamed = course.copy(name = "修改后的课程名称", classroom = "新教室301", teacher = "新教师")
            store.commit(original, original.copy(courses = original.courses.map { if (it.id == course.id) renamed else it }))
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { text(renamed.name) && text(renamed.classroom) && text(renamed.teacher) }
            scenario.moveToState(Lifecycle.State.CREATED)
            val current = store.load()
            store.commit(current, current.copy(courses = current.courses.filterNot { it.id == course.id }))
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { text("该课程已被删除，详情已关闭。") && !text("课程详情") }
            assertFalse(store.load().courses.any { it.id == course.id })
        }
    }

    private fun assertDetails() = instrumentation.runOnMainSync {
        val views = nodes()
        assertFalse("Viewing a course must not open an editor", views.any { it is EditText })
        listOf(course.name, course.classroom, course.teacher, "第1–2节").forEach { value ->
            assertTrue("Missing detail: $value", views.filterIsInstance<TextView>().any { it.text.toString() == value })
        }
    }
    private fun nodes(): List<View> {
        fun walk(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { walk(view.getChildAt(it)) } else emptyList()
        return WindowInspector.getGlobalWindowViews().flatMap(::walk)
    }
    private fun text(value: String): Boolean {
        var found = false
        instrumentation.runOnMainSync { found = nodes().filterIsInstance<TextView>().any { it.text.toString() == value } }
        return found
    }
    private fun mainCheck(check: () -> Boolean): Boolean {
        var result = false
        instrumentation.runOnMainSync { result = check() }
        return result
    }
    private fun tap(value: String) = instrumentation.runOnMainSync {
        val view = nodes().filterIsInstance<TextView>().first { it.text.toString() == value && it.isEnabled && it.isShown }
        var parent = view.parent
        while (parent is View) {
            if (parent is ListView) {
                val position = parent.getPositionForView(view)
                assertTrue("List row missing: $value", position >= 0)
                assertTrue(parent.performItemClick(view, position, parent.getItemIdAtPosition(position)))
                return@runOnMainSync
            }
            parent = parent.parent
        }
        var target: View? = view
        while (target != null && !target.isClickable) target = target.parent as? View
        assertNotNull(target); assertTrue(target!!.performClick())
    }
    private fun orient(scenario: ActivityScenario<ScheduleActivity>) {
        val value = InstrumentationRegistry.getArguments().getString("scheduleOrientation") ?: return
        scenario.onActivity { it.requestedOrientation = if (value == "landscape") android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        await {
            var valid = false
            scenario.onActivity { valid = if (value == "landscape") it.window.decorView.width > it.window.decorView.height else it.window.decorView.height > it.window.decorView.width }
            valid
        }
    }
    private fun await(check: () -> Boolean) {
        val limit = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < limit) { if (check()) return; SystemClock.sleep(40) }
        assertTrue("Schedule UI did not settle", check())
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        await {
            var focused = false
            instrumentation.runOnMainSync { focused = WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus() && it.isShown } }
            focused
        }
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val root = WindowInspector.getGlobalWindowViews().first { it.hasWindowFocus() && it.isShown }
            root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }; root.invalidate()
        }
        assertTrue(committed.await(4, TimeUnit.SECONDS))
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        try { File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun isolated(block: () -> Unit) {
        val file = File(context.filesDir, ScheduleStore.FILE_NAME)
        val bytes = file.takeIf { it.exists() }?.readBytes()
        try {
            file.delete()
            val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val extras = listOf(
                course.copy(id = "00000000-0000-0000-0000-000000000abd", name = "高级数字绘画", weekday = 2, startPeriod = 3, endPeriod = 4, classroom = "示例教室A"),
                course.copy(id = "00000000-0000-0000-0000-000000000abe", name = "视觉设计项目制作", weekday = 3, startPeriod = 1, endPeriod = 2, classroom = "示例教室B"),
                course.copy(id = "00000000-0000-0000-0000-000000000abf", name = "体育", weekday = 4, startPeriod = 5, endPeriod = 6, classroom = "示例场地"),
                course.copy(id = "00000000-0000-0000-0000-000000000ac0", name = "数字影像基础", weekday = 5, startPeriod = 3, endPeriod = 4, classroom = "示例教室C")
            )
            ScheduleStore(context).commit(ScheduleStore(context).load(), ScheduleData(Semester(monday, 20), courses = listOf(course) + extras))
            block()
        } finally { if (bytes == null) file.delete() else file.writeBytes(bytes) }
    }
}
