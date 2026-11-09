package cn.campuslife.assistant.schedule

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** Run only in a disposable offline emulator. */
class SchoolFutureStoreDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val today get() = LocalDate.now()
    private fun local(name: String, id: String = "00000000-0000-0000-0000-000000000001"): ScheduleData {
        val monday = today.with(DayOfWeek.MONDAY).minusWeeks(2)
        return ScheduleData(Semester(monday, 6)).add(Course(id, name, "", "", today.dayOfWeek.value, 1, 2, 1, 6, WeekPattern.EVERY, null))
    }
    private fun incoming() = local("New", "00000000-0000-0000-0000-000000000002")

    @Test fun provenancePersistsAndManualEditRevokesItEvenAfterRevert() {
        val store = ScheduleStore(context); val original = local("Original")
        store.replaceWithBackup(store.load(), original, true)
        assertEquals(original.courses.associateBy { it.id }, ScheduleStore(context).loadSnapshot().registry.managed)
        val changed = original.update(original.courses.single().copy(teacher = "manual"))
        store.commit(original, changed); store.commit(changed, original)
        val snapshot = ScheduleStore(context).loadSnapshot()
        assertTrue(snapshot.registry.managed.isEmpty())
        assertTrue(original.courses.single().id in snapshot.registry.protectedIds)
        assertThrows(IllegalArgumentException::class.java) { store.applyFutureWithBackup(snapshot, incoming(), emptySet(), today) }
        assertEquals(snapshot, store.loadSnapshot())
        assertThrows(IllegalArgumentException::class.java) {
            SchoolFutureMerge.plan(original, incoming(), emptyMap(), setOf(original.courses.single().id), today, snapshot.registry.protectedIds)
        }
    }
    @Test fun selectedLegacyFutureCommitHasBackupAndRejectsStaleOrDateChangeWithoutWrites() {
        val store = ScheduleStore(context); val old = local("Legacy")
        store.replaceWithBackup(store.load(), old)
        val baseline = store.loadSnapshot(); assertTrue(baseline.registry.managed.isEmpty())
        val result = store.applyFutureWithBackup(baseline, incoming(), setOf(old.courses.single().id), today)
        assertEquals(old, store.previousImport())
        assertTrue(result.data.courses.any { it.name == "Legacy" })
        assertTrue(result.data.courses.any { it.name == "New" })
        assertEquals(result.managed, ScheduleStore(context).loadSnapshot().registry.managed)
        val after = store.loadSnapshot(); val undo = store.previousImport()
        assertThrows(ScheduleConflictException::class.java) { store.applyFutureWithBackup(baseline, incoming(), emptySet(), today) }
        assertThrows(IllegalArgumentException::class.java) { store.applyFutureWithBackup(after, incoming(), emptySet(), today.minusDays(1)) }
        assertEquals(after, store.loadSnapshot()); assertEquals(undo, store.previousImport())
    }
    @Test fun actualPreviewDefaultsToProtectAndSelectedLegacyCanBeConfirmed() {
        val store = ScheduleStore(context); val old = local("Legacy")
        store.replaceWithBackup(store.load(), old)
        val snapshot = store.loadSnapshot()
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use { scenario ->
            await { enabled("登录学校平台") }
            scenario.onActivity { activity ->
                val session = activity.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(activity)
                session.javaClass.getDeclaredField("baselineSnapshot").apply { isAccessible = true }.set(session, snapshot)
                ScheduleTransferActivity::class.java.getDeclaredMethod("preview", ScheduleData::class.java, String::class.java,
                    List::class.java, ScheduleData::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                    .invoke(activity, incoming(), "Fixture", emptyList<String>(), old, true)
            }
            await { enabled("预览仅今日及未来更新") }
            tap("选择旧课程（已选0/1）")
            inst.runOnMainSync {
                val box = nodes().filterIsInstance<CheckedTextView>().single { it.text.contains("Legacy") }
                assertFalse(box.isChecked)
                val list = nodes().filterIsInstance<ListView>().single { it.getPositionForView(box) >= 0 }
                val position = list.getPositionForView(box)
                assertTrue(list.performItemClick(box, position, list.getItemIdAtPosition(position)))
                assertTrue(box.isChecked)
            }
            tap("确认选择"); await { enabled("选择旧课程（已选1/1）") }
            tap("预览仅今日及未来更新"); await { enabled("确认未来更新") }; tap("确认未来更新")
            await { store.load().courses.any { it.name == "New" } }
            assertEquals(old, store.previousImport())
            assertTrue(store.load().courses.any { it.name == "Legacy" })
        }
    }
    @Test fun overflowDoesNotChangeScheduleOrBackupBytes() {
        val store = ScheduleStore(context)
        val template = local("Template")
        val differentDay = today.dayOfWeek.value % 7 + 1
        val courses = (1..500).map { index -> template.courses.single().copy(
            id = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}", name = "Manual $index", weekday = differentDay) }
        val full = template.copy(courses = courses).checked()
        store.replaceWithBackup(store.load(), full)
        val baseline = store.loadSnapshot()
        val dataFile = java.io.File(context.filesDir, ScheduleStore.FILE_NAME)
        val undoFile = java.io.File(context.filesDir, ScheduleStore.IMPORT_UNDO_FILE)
        val dataBytes = dataFile.readBytes(); val undoBytes = undoFile.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.applyFutureWithBackup(baseline, incoming(), emptySet(), today) }
        assertArrayEquals(dataBytes, dataFile.readBytes()); assertArrayEquals(undoBytes, undoFile.readBytes())
    }
    @Test fun legacyTimetableWithoutImportTimestampShowsUnknownReminder() {
        val store = ScheduleStore(context)
        store.replaceWithBackup(store.load(), local("Legacy reminder"))
        context.getSharedPreferences("schedule-school-import-history", 0).edit().clear().commit()
        ActivityScenario.launch(ScheduleActivity::class.java).use {
            await { textContains("尚未记录更新时间，建议检查学校课表") }
            assertFalse(textContains("距上次学校导入已满7天"))
        }
    }
    private fun textContains(value: String): Boolean {
        var found = false; inst.runOnMainSync { found = nodes().any { it is TextView && it.text.contains(value) } }; return found
    }
    private fun nodes(): List<View> = WindowInspector.getGlobalWindowViews().flatMap { root ->
        fun walk(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList()
        walk(root)
    }
    private fun enabled(label: String): Boolean {
        var found = false; inst.runOnMainSync { found = nodes().any { it is Button && it.text.toString() == label && it.isEnabled && it.isShown } }; return found
    }
    private fun tap(label: String) = inst.runOnMainSync {
        val button = nodes().filterIsInstance<Button>().firstOrNull { it.text.toString() == label && it.isEnabled && it.isShown }
        assertNotNull("Missing: $label", button); assertTrue(button!!.performClick())
    }
    private fun await(check: () -> Boolean) {
        val limit = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < limit) { if (check()) return; SystemClock.sleep(40) }
        assertTrue("Timed out", check())
    }
}
