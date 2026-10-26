package cn.campuslife.assistant.schedule

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.graphics.Rect
import android.widget.Button
import android.widget.LinearLayout
import android.view.inspector.WindowInspector
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.map.CampusCoordinateStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class ScheduleFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val monday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    private fun fixture() = ScheduleData(Semester(monday, 20))

    @Test fun recreatedCourseDraftRetainsItsOriginalBaselineAfterRemoteDeletion() {
        val course = Course("00000000-0000-0000-0000-000000000001", "Original", "", "", 1, 1, 1, 1, 20, WeekPattern.EVERY, null)
        isolated(fixture().add(course)) {
            ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
                tap("管理"); tap("全部课程"); tap("编辑：Original"); set("教师（可选）", "Unsaved teacher")
                scenario.moveToState(Lifecycle.State.CREATED)
                val other = ScheduleStore(context); val before = other.load()
                other.commit(before, before.delete(course.id))
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.recreate()
                assertFieldValue("教师（可选）", "Unsaved teacher")
                tap("保存课程")
                find("这条课程已被其他页面修改或删除。草稿已保留，请取消后重新打开课程核对")
                assertTrue(other.load().courses.isEmpty())
                assertFieldValue("教师（可选）", "Unsaved teacher")
            }
        }
    }

    @Test fun resumedSemesterDraftCannotOverwriteAnotherScreensSettings() = isolated(fixture()) {
        ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
            tap("管理"); tap("学期设置"); set("总周数", "25")
            scenario.moveToState(Lifecycle.State.CREATED)
            val other = ScheduleStore(context); val before = other.load()
            other.commit(before, before.copy(semester = Semester(monday, 30)))
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertFieldValue("总周数", "25")
            tap("保存学期"); find("其他页面已修改学期，请保留草稿并重新打开设置核对")
            assertEquals(30, other.load().semester!!.totalWeeks)
            assertFieldValue("总周数", "25")
        }
    }

    @Test fun savingResumedNewDraftKeepsAnotherScreensAdditionAndDeletion() {
        val removed = Course("00000000-0000-0000-0000-000000000001", "Removed", "", "", 1, 1, 1, 1, 20, WeekPattern.EVERY, null)
        isolated(fixture().add(removed)) {
            ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
                tap("管理"); tap("新增课程"); set("课程名称", "Local addition"); set("星期（1为周一，7为周日）", "1")
                scenario.moveToState(Lifecycle.State.CREATED)
                val other = ScheduleStore(context); val before = other.load()
                val added = removed.copy(id = "00000000-0000-0000-0000-000000000002", name = "Remote addition", weekday = 2)
                other.commit(before, before.delete(removed.id).add(added))
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertFieldValue("课程名称", "Local addition")
                tap("保存课程")
                await { other.load().courses.any { it.name == "Local addition" } }
                val courses = other.load().courses
                assertEquals(setOf("Local addition", "Remote addition"), courses.map { it.name }.toSet())
                assertFalse(courses.any { it.id == removed.id })
            }
        }
    }

    @Test fun resumeRefreshPreservesDraftAndRejectsAnotherScreensEdit() {
        val course = Course("00000000-0000-0000-0000-000000000001", "Original", "", "", 1, 1, 1, 1, 20, WeekPattern.EVERY, null)
        isolated(fixture().add(course)) {
            ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
                tap("管理"); tap("全部课程"); tap("编辑：Original"); set("课程名称", "Local draft")
                scenario.moveToState(Lifecycle.State.CREATED)
                val other = ScheduleStore(context); val before = other.load()
                val extra = course.copy(id = "00000000-0000-0000-0000-000000000002", name = "Independent", weekday = 2)
                other.commit(before, before.update(course.copy(name = "Remote edit")).add(extra))
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertFieldValue("课程名称", "Local draft")
                tap("保存课程")
                find("这条课程已被其他页面修改或删除。草稿已保留，请取消后重新打开课程核对")
                assertFieldValue("课程名称", "Local draft")
                val latest = other.load()
                assertEquals(2, latest.courses.size)
                assertEquals("Remote edit", latest.courses.single { it.id == course.id }.name)
                tap("取消"); tap("管理"); tap("全部课程"); find("编辑：Independent")
            }
        }
    }

    @Test fun weekGridUsesCompactColumnsAndSummariesStillOpenTheFullCourse() {
        val course = Course("00000000-0000-0000-0000-000000000001", "用于验证紧凑周表的完整课程名称", "测试教师", "完整教室名称", 1, 1, 1, 1, 20, WeekPattern.EVERY, null)
        isolated(fixture().add(course)) {
            ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
                find("第1周")
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    val table = descendants(root).filterIsInstance<ScheduleWeekView>().single()
                    val density = activity.resources.displayMetrics.density
                    val scale = activity.resources.configuration.fontScale.coerceAtLeast(1f)
                    val button = descendants(table).filterIsInstance<Button>().single()
                    val available = activity.resources.displayMetrics.widthPixels - (24 * density).toInt() - (34 * scale * density).toInt()
                    val expectedWidth = maxOf((48 * scale * density).toInt(), available / 7)
                    assertTrue(kotlin.math.abs(button.width - expectedWidth) <= 1)
                    assertTrue(button.height >= (48 * density).toInt())
                    assertTrue(button.contentDescription.toString().contains(course.name))
                    assertTrue(button.contentDescription.toString().contains(course.classroom))
                    assertTrue(button.contentDescription.toString().contains(course.teacher))
                    assertTrue(button.performClick())
                }
                find("课程详情"); tap("编辑课程")
                assertFieldValue("课程名称", course.name)
            }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun invalidCourseBringsItsErrorIntoViewWithoutSaving() = isolated(fixture()) {
        ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
            tap("管理"); tap("新增课程"); tap("保存课程")
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val error = WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<TextView>()
                    .single { it.text.toString() == "文字为空、过长或包含控制字符" }
                assertTrue("The validation error must scroll into view", error.getGlobalVisibleRect(Rect()))
            }
            assertTrue(ScheduleStore(context).load().courses.isEmpty())
        }
    }

    @Test fun beforeAndAfterSemesterAreVisibleWhileOtherWeeksRemainBrowsable() {
        isolated(ScheduleData(Semester(monday.plusWeeks(1), 20))) {
            ActivityScenario.launch(ScheduleActivity::class.java).use {
                find("学期尚未开始"); find("第1周"); tap("下一周"); find("第2周")
                tap("今日"); find("今日暂无课程")
            }
        }
        isolated(ScheduleData(Semester(monday.minusWeeks(20), 20))) {
            ActivityScenario.launch(ScheduleActivity::class.java).use {
                find("本学期已结束"); find("第20周"); tap("上一周"); find("第19周")
                tap("今日"); find("今日暂无课程")
            }
        }
    }

    @Test fun directActionsShareOneRowAndSettingsKeepInfrequentActionsReachable() = isolated(fixture()) {
        ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
            find("本周暂无课程")
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val buttons = descendants(root).filterIsInstance<Button>()
                val add = buttons.single { it.text.toString() == "周课表" }
                val today = buttons.single { it.text.toString() == "今日" }
                assertSame(add.parent, today.parent)
                assertEquals(LinearLayout.HORIZONTAL, (add.parent as LinearLayout).orientation)
                val minimum = (48 * activity.resources.displayMetrics.density).toInt()
                assertTrue(add.height >= minimum); assertTrue(today.height >= minimum)
                assertFalse(buttons.any { it.text.toString() in listOf("新增课程", "学期设置", "节次设置", "全部课程", "学校导入 / 备份与恢复") })
            }
            tap("管理"); tap("节次设置")
            set("节次列表", "测试节次|08:00|08:50"); tap("保存节次")
            await { ScheduleStore(context).load().periods.size == 1 }
            assertEquals("测试节次", ScheduleStore(context).load().periods.single().label)
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun backAndRepeatedSaveCannotLeaveAnInFlightWriteOrDuplicateTheCourse() = isolated(fixture()) {
        ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
            tap("管理"); tap("新增课程"); set("课程名称", "保存返回测试")
            scenario.onActivity { activity ->
                val save = WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<Button>()
                    .single { it.text.toString() == "保存课程" }
                assertTrue(save.performClick())
                // The delivery cannot run until this main-thread callback returns, even if I/O is fast.
                activity.onBackPressed()
                assertFalse("Back must wait for the write outcome", activity.isFinishing)
                save.performClick()
            }
            await { ScheduleStore(context).load().courses.size == 1 }
            tap("管理"); tap("全部课程"); find("编辑：保存返回测试")
            assertEquals(1, ScheduleStore(context).load().courses.size)
        }
    }

    @Test fun crudThroughControlsSurvivesActivityRestart() = isolated(fixture()) {
        ActivityScenario.launch(ScheduleActivity::class.java).use {
            tap("管理"); tap("新增课程"); set("课程名称", "流程测试课程"); tap("保存课程")
            await { ScheduleStore(context).load().courses.size == 1 }
        }
        val id = ScheduleStore(context).load().courses.single().id
        ActivityScenario.launch(ScheduleActivity::class.java).use {
            tap("管理"); tap("全部课程"); tap("编辑：流程测试课程")
            set("课程名称", "修改测试课程"); tap("保存课程")
            await { ScheduleStore(context).load().courses.single().name == "修改测试课程" }
            assertEquals(id, ScheduleStore(context).load().courses.single().id)
            tap("管理"); tap("全部课程"); tap("编辑：修改测试课程"); tap("删除课程"); tap("确认删除")
            await { ScheduleStore(context).load().courses.isEmpty() }
        }
        ActivityScenario.launch(ScheduleActivity::class.java).use { find("本周暂无课程") }
    }

    @Test fun semesterSetupAndDraftSurviveRecreationWithExplicitValidation() = isolated(ScheduleData()) {
        ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
            find("请先设置学期"); tap("管理"); tap("学期设置")
            set("起始周一", monday.plusDays(1).toString()); set("总周数", "20"); tap("保存学期")
            find("学期起始日必须是周一")
            set("起始周一", monday.toString()); tap("保存学期")
            await { ScheduleStore(context).load().semester != null }
            tap("管理"); tap("新增课程"); set("课程名称", "旋转草稿"); set("教师（可选）", "测试教师")
            scenario.recreate()
            assertFieldValue("课程名称", "旋转草稿")
            assertFieldValue("教师（可选）", "测试教师")
            assertTrue(ScheduleStore(context).load().courses.isEmpty())
            tap("保存课程"); await { ScheduleStore(context).load().courses.size == 1 }
        }
    }

    @Test fun conflictingCoursesCanBothBeSavedAndEdited() = isolated(fixture()) {
        ActivityScenario.launch(ScheduleActivity::class.java).use {
            tap("管理"); tap("新增课程"); set("课程名称", "冲突课程一"); tap("保存课程")
            await { ScheduleStore(context).load().courses.size == 1 }
            tap("管理"); tap("新增课程"); set("课程名称", "冲突课程二"); tap("保存课程")
            find("课程时间有重叠"); tap("仍然保存")
            await { ScheduleStore(context).load().courses.size == 2 }
            tap("管理"); tap("全部课程"); find("编辑：冲突课程一"); tap("编辑：冲突课程二")
            assertFieldValue("课程名称", "冲突课程二")
        }
    }

    @Test fun hiddenMissingAndUnpositionedPlacesDisableMapWithoutDroppingCourses() {
        val map = CampusCoordinateStore(context).load()
        val ids = listOf("missing-example", map.hiddenIds.first(), map.visiblePlaces.first { it.id !in map.points }.id)
        ids.forEach { id ->
            val course = Course("00000000-0000-0000-0000-000000000001", "地点测试课程", "", "", 1, 1, 2, 1, 20, WeekPattern.EVERY, id)
            isolated(fixture().add(course)) {
                ActivityScenario.launch(ScheduleActivity::class.java).use {
                    tap("管理"); tap("全部课程"); tap("编辑：地点测试课程")
                    assertFalse(find("查看上课地点").isEnabled)
                    assertEquals(id, ScheduleStore(context).load().courses.single().placeId)
                }
            }
        }
    }

    @Test fun corruptStoreDisablesMutationAndKeepsSource() = isolated(null) {
        val file = File(context.filesDir, ScheduleStore.FILE_NAME)
        val bytes = "{broken-flow-fixture".toByteArray(); file.writeBytes(bytes)
        ActivityScenario.launch(ScheduleActivity::class.java).use {
            find("课程表读取失败，原文件已保留。请勿卸载应用；可退出后重试。")
            assertFalse(find("管理").isEnabled)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun assertFieldValue(label: String, expected: String) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            assertEquals(expected, find(label, true).text.toString()); return
        }
        // Error feedback intentionally scrolls the form to its bottom. Read retained values from
        // the real dialog view, without racing accessibility's repeated show/scroll operations.
        instrumentation.runOnMainSync {
            val fields = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
                .filterIsInstance<android.widget.EditText>().filter { it.contentDescription?.toString() == label }
            assertEquals("Expected one live editor field: $label", 1, fields.size)
            assertEquals(expected, fields.single().text.toString())
        }
    }

    private fun find(value: String, description: Boolean = false): AccessibilityNodeInfo {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if ((if (description) node.contentDescription else node.text)?.toString() == value) return node
            for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
            return null
        }
        val deadline = SystemClock.uptimeMillis() + 8000
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        fun scrollHost(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable && node.className?.toString() == "android.widget.ScrollView" && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) scrollHost(node.getChild(i))?.let { return it }
            return null
        }
        do {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val node = search(root)
            if (node != null) {
                if (node.isVisibleToUser) return node
                if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) {
                    // Do not immediately scroll forward again: that undoes SHOW_ON_SCREEN and
                    // oscillates past offscreen headings/fields until the timeout expires.
                    SystemClock.sleep(80)
                    continue
                }
            }
            val host = scrollHost(root)
            if (host != null) {
                val moved = host.performAction(direction)
                if (!moved) {
                    direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
            }
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        if (InstrumentationRegistry.getArguments().getString("diagnostic") == "1" &&
            android.os.Build.VERSION.SDK_INT >= 29 && android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) {
            instrumentation.runOnMainSync {
                val views = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
                File(context.filesDir, "schedule-ui-diagnostic.txt").writeText(views.filterIsInstance<TextView>().joinToString("\n") {
                    "${it.javaClass.simpleName} text=${it.text} shown=${it.isShown} rect=${Rect().also { rect -> it.getGlobalVisibleRect(rect) }}"
                })
            }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            if (screenshot != null) try { File(context.filesDir, "schedule-ui-diagnostic.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            } } finally { screenshot.recycle() }
        }
        throw AssertionError("Missing schedule test control: $value")
    }
    private fun tap(value: String) {
        val node = find(value); node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        assertTrue("Control disabled: $value", node.isEnabled)
        var clicked = false
        if (android.os.Build.VERSION.SDK_INT >= 29) instrumentation.runOnMainSync {
            // onResume can replace page views while accessibility still caches the old node ID.
            // Click the current visible native button, not a stale ID from the previous frame.
            val button = WindowInspector.getGlobalWindowViews().asReversed().flatMap(::descendants)
                .filterIsInstance<Button>().firstOrNull {
                    it.text.toString() == value && it.isShown && it.getGlobalVisibleRect(Rect())
                }
            if (button != null) { assertTrue("Control disabled: $value", button.isEnabled); clicked = button.performClick() }
        }
        if (!clicked) clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        assertTrue(clicked); instrumentation.waitForIdleSync()
    }
    private fun set(label: String, value: String) {
        val node = find(label, true); node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })); instrumentation.waitForIdleSync()
    }
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) { if (check()) return; SystemClock.sleep(40) }
        fail("Schedule operation did not persist")
    }
    private fun isolated(data: ScheduleData?, block: () -> Unit) {
        val files = listOf("", ".bak", ".new").map { File(context.filesDir, ScheduleStore.FILE_NAME + it) }
        val backup = files.map { if (it.exists()) it.readBytes() else null }
        try {
            files.forEach { it.delete() }; data?.let { data -> ScheduleStore(context).let { it.commit(it.load(), data) } }; block()
        } finally { files.forEachIndexed { i, file -> backup[i]?.let { file.writeBytes(it) } ?: file.delete() } }
    }
}
