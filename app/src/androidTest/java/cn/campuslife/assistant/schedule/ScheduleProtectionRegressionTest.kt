package cn.campuslife.assistant.schedule

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate

/** Synthetic, offline store regressions. Never opens an Activity or the real timetable directory. */
class ScheduleProtectionRegressionTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var today: LocalDate

    @Before fun createIsolatedStore() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(base.cacheDir.toPath(), "schedule-protection-").toFile()
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        today = LocalDate.now()
    }

    @After fun removeIsolatedStore() {
        if (::directory.isInitialized) assertTrue("Remove only this test's temporary directory", directory.deleteRecursively())
    }

    @Test fun emptyReadsAndIdenticalReplacementDoNotCreateScheduleOrPrevious() {
        val untouched = diskState()
        val store = ScheduleStore(context)
        val empty = store.load()
        val before = store.loadSnapshot()
        assertEquals(ScheduleSnapshot(empty, SchoolRegistry()), before)
        assertNull(store.previousImport())
        // Both data and provenance are identical; this does not register a school source.
        assertEquals(empty, store.replaceWithBackup(empty, empty, schoolImport = false))
        assertEquals(before, ScheduleStore(context).loadSnapshot())
        assertDiskUnchanged(untouched)
    }

    @Test fun readsAndIdenticalSchoolReplacementKeepBothContentsAndMtimes() {
        val store = seed(data(course(1, "Imported", 1)), schoolImport = true)
        val before = store.loadSnapshot()
        val previous = store.previousImport()
        assertNotNull(previous)
        assertNotEquals(before.data, previous)
        val untouched = markAndSnapshot()

        repeat(2) {
            val reopened = ScheduleStore(context)
            assertEquals(before, reopened.loadSnapshot())
            assertEquals(before.data, reopened.load())
            assertEquals(previous, reopened.previousImport())
            assertEquals(before.data, reopened.replaceWithBackup(before.data, before.data, schoolImport = true))
        }
        assertEquals(before, store.loadSnapshot())
        assertDiskUnchanged(untouched)
    }

    @Test fun automaticMixedUpdatePreservesHistoryEditsTombstoneAndManualOverlapThenReplayIsNoOp() {
        val safe = course(1, "Safe", 1)
        val originalEdit = course(2, "Edited", 3)
        val deleted = course(3, "Deleted", 5).copy(pattern = WeekPattern.ODD, endWeek = 7)
        val collision = course(4, "Collision", 7)
        val cancelled = course(5, "Cancelled", 11).copy(pattern = WeekPattern.EVEN, startWeek = 2)
        val imported = data(safe, originalEdit, deleted, collision, cancelled)
        val store = seed(imported, schoolImport = true)
        val edited = originalEdit.copy(teacher = "User teacher", classroom = "User room", placeId = "local-place")
        val manual = course(6, "Personal", 9).copy(placeId = "personal-place")
        store.commit(imported, imported.update(edited).delete(deleted.id).add(manual))
        val before = ScheduleStore(context).loadSnapshot()
        assertEquals(setOf(originalEdit.id, deleted.id), before.registry.protectedIds)
        assertEquals(mapOf(originalEdit.id to originalEdit, deleted.id to deleted), before.registry.protectedCourses)
        val originalBytes = File(directory, ScheduleStore.FILE_NAME).readBytes()
        val school = data(
            safe.copy(id = id(101), classroom = "School room"),
            originalEdit.copy(id = id(102), teacher = "School teacher"),
            deleted.copy(id = id(103), name = "Renamed deleted", weekday = today.dayOfWeek.value % 7 + 1),
            collision.copy(id = id(104), startPeriod = 9, endPeriod = 10)
        )

        val plan = store.applyAutomatic(before, school, today)
        assertEquals(SchoolChanges(added = 0, adjusted = 1, cancelled = 1), plan.changes)
        assertEquals(3, plan.conflicts)
        val after = ScheduleStore(context).loadSnapshot()
        assertEquals(plan.data, after.data)
        assertEquals(plan.managed, after.registry.managed)
        assertEquals(before.registry.protectedIds, after.registry.protectedIds)
        assertEquals(before.registry.protectedCourses, after.registry.protectedCourses)
        assertEquals(pastOccurrences(before.data), pastOccurrences(after.data))
        assertEquals(edited, after.data.courses.single { it.id == edited.id })
        assertEquals(manual, after.data.courses.single { it.id == manual.id })
        assertEquals(collision, after.data.courses.single { it.id == collision.id })
        assertFalse(after.data.courses.any { it.id == deleted.id || it.name in setOf(deleted.name, "Renamed deleted") })
        assertEquals((3..8).toList(), futureWeeks(after.data, safe.name))
        assertEquals("School room", after.data.courses.single { it.name == safe.name && it.occurs(3) }.classroom)
        assertTrue(futureWeeks(after.data, cancelled.name).isEmpty())
        assertBackupOf(before, originalBytes)

        // School IDs/order may change; equal dated content plus unresolved conflicts is still a disk no-op.
        val untouched = markAndSnapshot()
        val reordered = school.copy(courses = school.courses.reversed().mapIndexed { index, c -> c.copy(id = id(201 + index)) })
        val replay = ScheduleStore(context).applyAutomatic(after, reordered, today)
        assertEquals(0, replay.changes.total)
        assertEquals(3, replay.conflicts)
        assertEquals(after, ScheduleStore(context).loadSnapshot())
        assertEquals(before.data, store.previousImport())
        assertDiskUnchanged(untouched)
    }

    @Test fun futureMergeCombinesSelectedLegacyManagedAndProtectedWeeksThenReplayKeepsPrevious() {
        val imported = course(1, "Imported", 1)
        val legacy = course(2, "Legacy", 3).copy(pattern = WeekPattern.ODD, endWeek = 7)
        val blocker = course(3, "Personal blocker", 1).copy(startWeek = 4, endWeek = 4, placeId = "local-place")
        val personal = course(4, "Other personal", 7)
        val initial = data(imported)
        val store = seed(initial, schoolImport = true)
        store.commit(initial, initial.add(legacy).add(blocker).add(personal))
        val before = ScheduleStore(context).loadSnapshot()
        assertEquals(mapOf(imported.id to imported), before.registry.managed)
        val originalBytes = File(directory, ScheduleStore.FILE_NAME).readBytes()
        val school = data(
            imported.copy(id = id(101), classroom = "School room"),
            legacy.copy(id = id(102), name = "Legacy replacement", pattern = WeekPattern.EVEN, startWeek = 2, endWeek = 8)
        )

        val plan = store.applyFutureWithBackup(before, school, setOf(legacy.id), today)
        val after = ScheduleStore(context).loadSnapshot()
        assertEquals(1, plan.blockedOccurrences)
        assertEquals(plan.data, after.data)
        assertEquals(SchoolRegistry(plan.managed), after.registry)
        assertEquals(pastOccurrences(before.data), pastOccurrences(after.data))
        assertEquals(blocker, after.data.courses.single { it.id == blocker.id })
        assertEquals(personal, after.data.courses.single { it.id == personal.id })
        assertEquals(listOf(3, 5, 6, 7, 8), futureWeeks(after.data, imported.name))
        assertTrue(futureWeeks(after.data, legacy.name).isEmpty())
        assertEquals(listOf(4, 6, 8), futureWeeks(after.data, "Legacy replacement"))
        assertEquals("School room", after.data.courses.single { it.name == imported.name && it.occurs(3) }.classroom)
        assertEquals(after.data.courses.size, after.data.courses.map { it.id }.distinct().size)
        assertBackupOf(before, originalBytes)

        val untouched = markAndSnapshot()
        val replay = ScheduleStore(context).applyFutureWithBackup(after, school, emptySet(), today)
        assertEquals(1, replay.blockedOccurrences)
        assertEquals(after.data, replay.data)
        assertEquals(after.registry.managed, replay.managed)
        assertEquals(after, ScheduleStore(context).loadSnapshot())
        assertEquals(before.data, store.previousImport())
        assertDiskUnchanged(untouched)
    }

    @Test fun futureMergeCannotBypassCombinedEditAndDeletionProtectionByExplicitSelection() {
        val originalEdit = course(1, "Edited", 1)
        val deleted = course(2, "Deleted", 3)
        val safe = course(3, "Safe", 5)
        val original = data(originalEdit, deleted, safe)
        val store = seed(original, schoolImport = true)
        val edited = originalEdit.copy(teacher = "User teacher", placeId = "local-place")
        store.commit(original, original.update(edited).delete(deleted.id))
        val before = ScheduleStore(context).loadSnapshot()
        assertEquals(setOf(originalEdit.id, deleted.id), before.registry.protectedIds)
        assertEquals(mapOf(originalEdit.id to originalEdit, deleted.id to deleted), before.registry.protectedCourses)
        val previous = store.previousImport()
        val untouched = markAndSnapshot()
        val school = original.update(safe.copy(classroom = "School room"))

        listOf(emptySet(), setOf(originalEdit.id), setOf(deleted.id)).forEach { selected ->
            assertThrows(IllegalArgumentException::class.java) {
                ScheduleStore(context).applyFutureWithBackup(before, school, selected, today)
            }
            assertEquals(before, ScheduleStore(context).loadSnapshot())
            assertEquals(previous, store.previousImport())
            assertDiskUnchanged(untouched)
        }
    }

    @Test fun provenanceOnlyStalenessRejectsBothMergesEvenAfterEditedCourseIsReverted() {
        val first = course(1, "Reverted", 1)
        val second = course(2, "Other imported", 3)
        val original = data(first, second)
        val store = seed(original, schoolImport = true)
        val stale = store.loadSnapshot()
        val edited = original.update(first.copy(teacher = "Temporary edit"))
        store.commit(original, edited)
        store.commit(edited, original)
        val latest = ScheduleStore(context).loadSnapshot()
        assertEquals(stale.data, latest.data)
        assertNotEquals(stale.registry, latest.registry)
        assertEquals(setOf(first.id), latest.registry.protectedIds)
        assertEquals(mapOf(first.id to first), latest.registry.protectedCourses)
        assertEquals(mapOf(second.id to second), latest.registry.managed)
        val previous = store.previousImport()
        val untouched = markAndSnapshot()
        val school = original.update(second.copy(classroom = "School room"))

        assertThrows(ScheduleConflictException::class.java) { store.applyAutomatic(stale, school, today) }
        assertEquals(latest, ScheduleStore(context).loadSnapshot())
        assertEquals(previous, store.previousImport())
        assertDiskUnchanged(untouched)
        assertThrows(ScheduleConflictException::class.java) { store.applyFutureWithBackup(stale, school, emptySet(), today) }
        assertEquals(latest, ScheduleStore(context).loadSnapshot())
        assertEquals(previous, store.previousImport())
        assertDiskUnchanged(untouched)
    }

    private fun id(number: Int) = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"

    private fun course(number: Int, name: String, startPeriod: Int) = Course(
        id(number), name, "Synthetic teacher", "Synthetic room", today.dayOfWeek.value,
        startPeriod, startPeriod + 1, 1, 8, WeekPattern.EVERY, null
    )

    private fun data(vararg courses: Course) = ScheduleData(
        Semester(today.with(DayOfWeek.MONDAY).minusWeeks(2), 8), courses = courses.toList()
    ).checked()

    /** Establish a distinct nonempty previous backup so accidental rotation cannot pass unnoticed. */
    private fun seed(current: ScheduleData, schoolImport: Boolean): ScheduleStore {
        val store = ScheduleStore(context)
        val previous = data(course(900, "Previous backup", 11))
        store.replaceWithBackup(store.load(), previous)
        store.replaceWithBackup(previous, current, schoolImport)
        assertEquals(previous, store.previousImport())
        return store
    }

    /** Compare every historical dated occurrence, including ID, parity, details and local place association. */
    private fun pastOccurrences(data: ScheduleData): Map<Pair<LocalDate, Course>, Int> = data.courses.flatMap { c ->
        (c.startWeek..c.endWeek).filter(c::occurs).mapNotNull { week ->
            val date = date(data, c, week)
            if (date.isBefore(today)) date to c.copy(startWeek = week, endWeek = week) else null
        }
    }.groupingBy { it }.eachCount()

    private fun futureWeeks(data: ScheduleData, name: String): List<Int> = data.courses.filter { it.name == name }.flatMap { c ->
        (c.startWeek..c.endWeek).filter { c.occurs(it) && !date(data, c, it).isBefore(today) }
    }.sorted()

    private fun date(data: ScheduleData, course: Course, week: Int): LocalDate =
        data.semester!!.startMonday.plusWeeks((week - 1).toLong()).plusDays((course.weekday - 1).toLong())

    private fun assertBackupOf(before: ScheduleSnapshot, bytes: ByteArray) {
        val backup = File(directory, ScheduleStore.IMPORT_UNDO_FILE)
        assertArrayEquals("Previous must be the exact pre-merge file, including provenance", bytes, backup.readBytes())
        assertEquals(before, ScheduleStore.decodeSnapshot(JSONObject(backup.readText(Charsets.UTF_8))))
        assertEquals(before.data, ScheduleStore(context).previousImport())
    }

    private data class DiskFile(val bytes: ByteArray, val modified: Long)

    // Include AtomicFile sidecars: an absent backup or a leftover partial write is also observable state.
    private fun diskNames() = listOf(ScheduleStore.IMPORT_UNDO_FILE, ScheduleStore.FILE_NAME).flatMap { name ->
        listOf(name, "$name.bak", "$name.new")
    }

    private fun diskState(): Map<String, DiskFile?> = diskNames().associateWith { name ->
        File(directory, name).takeIf { it.exists() }?.let { DiskFile(it.readBytes(), it.lastModified()) }
    }

    private fun markAndSnapshot(): Map<String, DiskFile?> {
        diskNames().forEachIndexed { index, name ->
            val file = File(directory, name)
            if (file.exists()) {
                // A year-2000 sentinel detects rewrites even on coarse-mtime filesystems, without sleeps.
                val timestamp = 946684800000L + index * 2000L
                assertTrue("Set mtime for $name", file.setLastModified(timestamp))
                assertEquals("Read back mtime for $name", timestamp, file.lastModified())
            }
        }
        return diskState()
    }

    private fun assertDiskUnchanged(expected: Map<String, DiskFile?>) {
        expected.forEach { (name, before) ->
            val file = File(directory, name)
            assertEquals("$name existence", before != null, file.exists())
            if (before != null) {
                assertArrayEquals("$name content", before.bytes, file.readBytes())
                assertEquals("$name mtime: a no-op must not rewrite the file", before.modified, file.lastModified())
            }
        }
    }
}
