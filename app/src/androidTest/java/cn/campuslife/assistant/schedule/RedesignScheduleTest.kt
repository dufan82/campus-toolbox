package cn.campuslife.assistant.schedule

import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.ui.Ui
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/** Synthetic local fixtures only: no WebView, account, school requests or real-device data. */
@SdkSuppress(minSdkVersion = 29)
class RedesignScheduleTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private fun fixture(): ScheduleData {
        val today = LocalDate.now()
        fun course(id: String, name: String, first: Int, last: Int) = Course(
            "00000000-0000-0000-0000-${id.padStart(12, '0')}", name, "离线教师", "离线教室203",
            today.dayOfWeek.value, first, last, 1, 20, WeekPattern.EVERY, null)
        return ScheduleData(Semester(today.with(DayOfWeek.MONDAY), 20), courses = listOf(
            course("901", "离线早课", 1, 2), course("902", "离线午间课", 6, 6),
            course("903", "离线冲突课", 5, 6), course("904", "离线晚课", 11, 12)))
    }

    @Test fun weekTodayNavigationAndRecreationAreReadOnly() = isolated { before ->
        ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
            await { hasTag("schedule.week.grid") && enabled("管理") }
            inst.runOnMainSync {
                assertTrue(nodes().single { it.tag == "bottom_nav_schedule" }.isSelected)
                assertFalse(nodes().single { it.tag == "bottom_nav_home" }.isSelected)
                val week = nodes().filterIsInstance<Button>().single { it.text == "周课表" }
                val today = nodes().filterIsInstance<Button>().single { it.text == "今日" }
                assertSame(week.parent, today.parent)
                assertTrue(week.isSelected)
                assertTouch(week); assertTouch(today)
                assertFalse(nodes().filterIsInstance<Button>().any { it.text == "新增课程" })
            }
            tap("今日")
            await { hasTag("schedule.today.${before.courses.first().id}") }
            inst.runOnMainSync {
                assertEquals(before.courses.size, nodes().count { it.tag?.toString()?.startsWith("schedule.today.") == true })
                assertFalse(nodes().filterIsInstance<Button>().any { it.text.contains("离线教师") })
            }
            scenario.recreate()
            await { hasTag("schedule.today.${before.courses.last().id}") }
            tap("周课表")
            await { hasTag("schedule.week.grid") }
            assertEquals(before, ScheduleStore(context).load())
        }
    }

    @Test fun lunchEveningAndOverlapsRemainReachableThroughReadOnlyDetails() = isolated { before ->
        val lunch = before.courses[1]
        val evening = before.courses.last()
        ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
            await { hasTag("schedule.overlap") && enabled("管理") }
            inst.runOnMainSync {
                assertEquals(before.periods.size, nodes().count { it.tag?.toString()?.startsWith("schedule.period.") == true })
                assertTouch(nodes().single { it.tag == "schedule.overlap" })
                assertTouch(nodes().single { it.tag == "schedule.course.${evening.id}" })
                nodes().single { it.tag == "schedule.overlap" }.performClick()
            }
            await { text("选择重叠的课程") }
            tap("${lunch.name} · ${SchedulePresentation.periodRange(lunch)}")
            await { hasTag("schedule.details") }
            assertDetails(lunch)
            closeSheet()
            await { !hasTag("schedule.details") }
            inst.runOnMainSync { nodes().single { it.tag == "schedule.course.${evening.id}" }.performClick() }
            await { hasTag("schedule.details") }
            scenario.recreate()
            await { hasTag("schedule.details") }
            assertDetails(evening)
            assertEquals(before, ScheduleStore(context).load())
        }
    }

    @Test fun managementKeepsAllExistingActionsAndCancelPreservesData() = isolated { before ->
        ActivityScenario.launch(ScheduleActivity::class.java).use {
            await { enabled("管理") }; tap("管理")
            await { text("课程表管理") }
            inst.runOnMainSync {
                val list = nodes().filterIsInstance<ListView>().single()
                val labels = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
                assertTrue(labels.containsAll(listOf("新增课程", "学校导入 / 备份与恢复", "学期设置", "节次设置", "全部课程")))
                (0 until list.childCount).forEach { assertTouch(list.getChildAt(it)) }
            }
            tap("节次设置")
            await { text("节次列表") }
            tap("取消")
            assertEquals(before, ScheduleStore(context).load())
        }
    }

    @Test fun schoolDifferenceSeparatesFutureAndWholeReplacementWithoutSaving() = isolated { before ->
        val store = ScheduleStore(context)
        val snapshot = store.loadSnapshot()
        val candidate = before.copy(courses = before.courses.mapIndexed { index, course ->
            if (index == 0) course.copy(name = "学校调整后的离线课", classroom = "离线教室501") else course
        })
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use { scenario ->
            await { enabled("登录学校平台") }
            scenario.onActivity { activity ->
                val session = activity.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(activity)
                session.javaClass.getDeclaredField("baselineSnapshot").apply { isAccessible = true }.set(session, snapshot)
                activity.javaClass.getDeclaredMethod("preview", ScheduleData::class.java, String::class.java,
                    List::class.java, ScheduleData::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                    .invoke(activity, candidate, "离线差异夹具", emptyList<String>(), before, true)
            }
            await { enabled("预览仅今日及未来更新") }
            assertTrue(hasTag("schedule.diff.local")); assertTrue(hasTag("schedule.diff.school"))
            assertTrue(hasTag("schedule.future.options")); assertTrue(hasTag("schedule.replace.options"))
            assertTrue(textContains("未登记来源的旧课程默认全部保护"))
            assertTrue(enabled("选择旧课程（已选0/${before.courses.size}）"))
            assertTrue(textContains("不是未来合并"))
            tap("预览仅今日及未来更新")
            await { enabled("确认未来更新") }
            assertTrue(textContains("过去课程和受保护课程保留"))
            tap("取消")
            await { enabled("确认保存到本机") }
            tap("确认保存到本机")
            await { enabled("确认替换") }
            assertTrue(textContains("包括过去及手动课程"))
            tap("返回核对")
            tap("取消，不修改原课表")
            await { enabled("登录学校平台") }
            assertEquals(snapshot, store.loadSnapshot())
        }
    }

    @Test fun largeTextGridKeepsAll24PeriodsAndMinimumTouchSize() {
        inst.runOnMainSync {
            val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
            val largeContext = context.createConfigurationContext(configuration)
            val data = fixture().copy(periods = (1..24).map { SchedulePeriod("离线节次$it") })
            val grid = redesignedScheduleWeekView(largeContext, data, 1, true) { }
            grid.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(largeContext, 320), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
            val views = descendants(grid)
            assertEquals(24, views.count { it.tag?.toString()?.startsWith("schedule.period.") == true })
            assertTrue(grid.getChildAt(0).width > grid.width)
            views.filterIsInstance<Button>().forEach { assertTouch(it) }
        }
    }

    private fun assertDetails(course: Course) = inst.runOnMainSync {
        val detail = nodes().single { it.tag == "schedule.details" }
        assertFalse(descendants(detail).any { it is EditText })
        listOf(course.name, course.classroom, course.teacher, SchedulePresentation.periodRange(course)).forEach { value ->
            assertTrue("Missing detail $value", descendants(detail).filterIsInstance<TextView>().any { it.text.toString() == value })
        }
        assertTrue(descendants(detail).filterIsInstance<Button>().any { it.text == "编辑课程" })
    }
    private fun assertTouch(view: View) {
        val minimum = Ui.dp(view.context, 48)
        assertTrue("Touch height ${view.tag}", view.height >= minimum)
        assertTrue("Touch width ${view.tag}", view.width >= minimum)
    }
    private fun closeSheet() = inst.runOnMainSync {
        nodes().first { it.contentDescription == "关闭" && it.isShown && it.isClickable }.performClick()
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun nodes() = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
    private fun checkMain(check: () -> Boolean): Boolean {
        var value = false; inst.runOnMainSync { value = check() }; return value
    }
    private fun hasTag(tag: String) = checkMain { nodes().any { it.tag == tag && it.isShown } }
    private fun enabled(label: String) = checkMain { nodes().filterIsInstance<Button>().any { it.text.toString() == label && it.isEnabled && it.isShown } }
    private fun text(label: String) = checkMain { nodes().filterIsInstance<TextView>().any { it.text.toString() == label && it.isShown } }
    private fun textContains(label: String) = checkMain { nodes().filterIsInstance<TextView>().any { it.text.contains(label) && it.isShown } }
    private fun tap(label: String) = inst.runOnMainSync {
        val view = nodes().filterIsInstance<TextView>().first { it.text.toString() == label && it.isShown && it.isEnabled }
        var parent = view.parent
        while (parent is View) {
            if (parent is ListView) {
                val position = parent.getPositionForView(view)
                assertTrue(parent.performItemClick(view, position, parent.getItemIdAtPosition(position)))
                return@runOnMainSync
            }
            parent = parent.parent
        }
        assertTrue(view.performClick())
    }
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < deadline) { if (check()) return; SystemClock.sleep(40) }
        assertTrue("Schedule redesign UI did not settle", check())
    }
    private fun isolated(block: (ScheduleData) -> Unit) {
        val files = listOf(ScheduleStore.FILE_NAME, ScheduleStore.IMPORT_UNDO_FILE).flatMap { name ->
            listOf(name, "$name.bak", "$name.new").map { File(context.filesDir, it) }
        }
        val original = files.associateWith { it.takeIf(File::exists)?.readBytes() }
        try {
            files.forEach { it.delete() }
            val data = fixture()
            File(context.filesDir, ScheduleStore.FILE_NAME).writeText(ScheduleStore.encodeSnapshot(ScheduleSnapshot(data, SchoolRegistry())).toString())
            block(data)
        } finally {
            original.forEach { (file, bytes) -> if (bytes == null) file.delete() else file.writeBytes(bytes) }
        }
    }
}
