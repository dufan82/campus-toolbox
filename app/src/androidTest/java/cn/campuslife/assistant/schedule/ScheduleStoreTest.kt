package cn.campuslife.assistant.schedule

import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.AtomicJsonFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScheduleStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun twoStoresMergeStaleIndependentAddsAndRejectAConflictingEditWithoutWriting() = isolated {
        val a = ScheduleStore(context); val b = ScheduleStore(context)
        val initial = ScheduleData(Semester(LocalDate.of(2026, 9, 7), 20))
        a.commit(a.load(), initial)
        val beforeA = a.load(); val beforeB = b.load()
        val first = Course("00000000-0000-0000-0000-000000000001", "First", "", "", 1, 1, 1, 1, 20, WeekPattern.EVERY, null)
        val second = first.copy(id = "00000000-0000-0000-0000-000000000002", name = "Second", weekday = 2)
        b.commit(beforeB, beforeB.add(second))
        val merged = a.commit(beforeA, beforeA.add(first))
        assertEquals(setOf(first.id, second.id), merged.courses.map { it.id }.toSet())
        val stale = b.load()
        a.commit(merged, merged.update(first.copy(name = "Updated by A")))
        val originalBytes = File(context.filesDir, ScheduleStore.FILE_NAME).readBytes()
        assertThrows(ScheduleConflictException::class.java) {
            b.commit(stale, stale.update(first.copy(name = "Updated by B")))
        }
        assertArrayEquals(originalBytes, File(context.filesDir, ScheduleStore.FILE_NAME).readBytes())
        val latest = a.load()
        assertEquals("Updated by A", latest.courses.single { it.id == first.id }.name)
        a.commit(latest, latest.delete(first.id))
        assertThrows(ScheduleConflictException::class.java) { b.commit(stale, stale.update(first.copy(teacher = "Stale"))) }
        assertFalse(a.load().courses.any { it.id == first.id })
    }

    @Test fun separateStoreInstancesSerializeReadMergeWriteUnderTheSameLock() = isolated {
        val store = ScheduleStore(context)
        store.commit(store.load(), ScheduleData(Semester(LocalDate.of(2026, 9, 7), 20)))
        val before = store.load()
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { index ->
                executor.submit<ScheduleData> {
                    assertTrue(gate.await(5, TimeUnit.SECONDS))
                    val course = Course("00000000-0000-0000-0000-00000000000$index", "Course $index", "", "", index, 1, 1, 1, 20, WeekPattern.EVERY, null)
                    ScheduleStore(context).commit(before, before.add(course))
                }
            }
            gate.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(2, ScheduleStore(context).load().courses.size)
        } finally { executor.shutdownNow() }
    }

    @Test fun roundTripKeepsUuidCustomTimesAndUnresolvedPlaceAcrossNewStoreInstances() = isolated {
        val course = Course("00000000-0000-0000-0000-000000000001", "测试课程", "测试教师", "测试教室", 2, 1, 1, 2, 20, WeekPattern.EVEN, "missing-example")
        val data = ScheduleData(Semester(LocalDate.of(2026, 9, 7), 20), listOf(SchedulePeriod("实验", LocalTime.of(8, 0), LocalTime.of(8, 50))), listOf(course))
        ScheduleStore(context).let { it.commit(it.load(), data) }
        assertEquals(data, ScheduleStore(context).load())
        assertEquals(1, AtomicJsonFile(context, ScheduleStore.FILE_NAME).read()!!.getInt("version"))
    }

    @Test fun strictDecodeRejectsCoercionUnknownVersionDuplicateAndMalformedFields() {
        val good = ScheduleStore.encode(ScheduleData(Semester(LocalDate.of(2026, 9, 7), 20)))
        listOf(
            JSONObject(good.toString()).put("version", 2),
            JSONObject(good.toString()).put("version", "1"),
            JSONObject(good.toString()).put("semester", JSONObject().put("startMonday", "2026-09-07").put("totalWeeks", 1.5)),
            JSONObject(good.toString()).put("courses", "not-an-array"),
            JSONObject(good.toString()).put("periods", org.json.JSONArray().put(JSONObject().put("label", 42)))
        ).forEach { bad -> assertThrows(Exception::class.java) { ScheduleStore.decode(bad) } }
        val course = Course("00000000-0000-0000-0000-000000000001", "测试", "", "", 1, 1, 1, 1, 20, WeekPattern.EVERY, null)
        val encoded = ScheduleStore.encode(ScheduleData(Semester(LocalDate.of(2026, 9, 7), 20)).add(course))
        encoded.getJSONArray("courses").put(encoded.getJSONArray("courses").getJSONObject(0))
        assertThrows(Exception::class.java) { ScheduleStore.decode(encoded) }
    }

    @Test fun corruptStoreIsReportedAndOriginalBytesAreKept() = isolated {
        val file = File(context.filesDir, ScheduleStore.FILE_NAME)
        val malformed = "{broken-schedule-fixture".toByteArray()
        file.writeBytes(malformed)
        assertThrows(Exception::class.java) { ScheduleStore(context).load() }
        assertArrayEquals(malformed, file.readBytes())
        AtomicJsonFile(context, ScheduleStore.FILE_NAME).write(JSONObject().put("version", 999))
        val unsupported = file.readBytes()
        assertThrows(Exception::class.java) { ScheduleStore(context).load() }
        assertArrayEquals(unsupported, file.readBytes())
    }

    private fun isolated(block: () -> Unit) {
        val files = listOf("", ".bak", ".new").map { File(context.filesDir, ScheduleStore.FILE_NAME + it) }
        val backup = files.map { if (it.exists()) it.readBytes() else null }
        try { files.forEach { it.delete() }; block() }
        finally { files.forEachIndexed { i, file -> backup[i]?.let { file.writeBytes(it) } ?: file.delete() } }
    }
}
