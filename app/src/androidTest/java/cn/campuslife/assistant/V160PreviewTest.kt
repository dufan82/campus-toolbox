package cn.campuslife.assistant

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.planner.*
import cn.campuslife.assistant.schedule.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Opt-in synthetic visual fixture. No real phone or personal data. */
class V160PreviewTest {
    @Test fun captureNativeScreensWithSyntheticRecords() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("visual") == "1" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val stores = listOf(ScheduleStore.FILE_NAME, "planner-v1.json")
            .flatMap { name -> listOf("", ".bak", ".new").map { File(context.filesDir, name + it) } }
        val backup = stores.associateWith { if (it.isFile) it.readBytes() else null }
        val folder = File(context.filesDir, "v160-previews").apply { mkdirs() }
        try {
            stores.forEach { it.delete() }
            val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val schedule = ScheduleData(Semester(monday, 20), courses = listOf(
                Course("90000000-0000-4000-8000-000000000001", "数字影像基础", "测试教师", "实训楼 301", 1, 1, 2, 1, 20, WeekPattern.EVERY, null),
                Course("90000000-0000-4000-8000-000000000002", "交互设计", "测试教师", "机房 202", 3, 3, 4, 1, 20, WeekPattern.EVERY, null)))
            ScheduleStore(context).commit(ScheduleStore(context).load(), schedule)
            PlannerStore(context).write(PlannerState(
                countdowns = listOf(CountdownItem("90000000-0000-4000-8000-000000000004", "寒假计划", LocalDate.now().plusDays(60)))))
            fun capture(name: String, type: Class<out Activity>, ready: (Activity) -> Boolean) {
                ActivityScenario.launch<Activity>(Intent(context, type)).use { scenario ->
                    var loaded = false
                    val deadline = SystemClock.elapsedRealtime() + 10000
                    while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity { loaded = ready(it) }
                        if (!loaded) SystemClock.sleep(50)
                    }
                    assertTrue("Screen did not load: $name", loaded)
                    instrumentation.waitForIdleSync(); SystemClock.sleep(150)
                    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try { File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                    finally { bitmap.recycle() }
                }
            }
            capture("home", cn.campuslife.assistant.ui.MainActivity::class.java) { it.window.decorView.findViewWithTag<View>("home_tool_schedule") != null }
            capture("schedule", ScheduleActivity::class.java) { hasReadyButton(it.window.decorView, "管理") }
            capture("planner", PlannerActivity::class.java) { hasReadyButton(it.window.decorView, "新增倒计时") }
        } finally {
            backup.forEach { (file, bytes) -> if (bytes == null) file.delete() else file.writeBytes(bytes) }
        }
    }

    private fun hasReadyButton(view: View, label: String): Boolean =
        (view is Button && view.text.toString() == label && view.isEnabled) ||
            (view is ViewGroup && (0 until view.childCount).any { hasReadyButton(view.getChildAt(it), label) })
}
