package cn.campuslife.assistant.schedule

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

class SchoolImportDeviceTest {
    @Test fun publishedSuccessCodeOneUnwrapsTimetableDataButUnauthorizedDoesNot() {
        val response = JSONObject("""{"code":1,"msg":"","data":{"semesters":[{"value":"2026-2027-1","label":"秋季","isCurrent":true}]}}""")
        assertEquals("2026-2027-1", SchoolPortalJson.semesters(response).single().id)
        assertTrue(runCatching { SchoolPortalJson.semesters(JSONObject("""{"code":401,"data":{"semesters":[]}}""")) }.isFailure)
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture() = ScheduleData(Semester(LocalDate.of(2026, 9, 7), 20)).add(Course(
        "00000000-0000-0000-0000-000000000111", "离线课程", "教师A", "实训1楼", 1, 1, 2, 1, 18, WeekPattern.EVERY, null))

    @Test fun currentTermUsesSchoolFlagNotListOrderAndMatchesCurrentSmallTerm() {
        fun terms(rows: String) = SchoolPortalJson.semesters(JSONObject("""{"code":200,"semesters":$rows}"""))
        val normal = terms("""[{"value":"future","label":"未来"},{"value":"now","label":"当前","isCurrent":1},{"value":"old","label":"过去"}]""")
        assertEquals("now", SchoolPortalJson.currentTerm(normal)!!.id)
        assertFalse(SchoolPortalJson.currentTerm(normal)!!.smallTerm)
        val small = terms("""[{"value":"now","label":"当前","isCurrent":true,"isXxq":true}]""")
        assertTrue(SchoolPortalJson.currentTerm(small)!!.smallTerm)
        assertNull(SchoolPortalJson.currentTerm(terms("""[{"value":"old","label":"过去"}]""")))
        assertNull(SchoolPortalJson.currentTerm(terms("""[{"value":"a","label":"A","isCurrent":true},{"value":"b","label":"B","isCurrent":true}]""")))
    }

    @Test fun loginProbeAcceptsOnlyBoundedBearerResultFromTheExactSchoolPage() {
        val valid = "\"Bearer synthetic_login_token_12345\""
        assertEquals("Bearer synthetic_login_token_12345", SchoolLoginProbe.authorization("https://m.fjpit.com/#/pages/index/index", valid))
        assertNull(SchoolLoginProbe.authorization("https://auth.fjpit.com/", valid))
        assertNull(SchoolLoginProbe.authorization("https://m.fjpit.com.evil.invalid/", valid))
        listOf("null", "\"\"", "{}", "\"Bearer bad\\nHeader\"", "x".repeat(9000)).forEach {
            assertNull(SchoolLoginProbe.authorization(SchoolPortalPolicy.START_URL, it))
        }
    }

    @Test fun schoolJsonReadsVerifiedFieldsAndRejectsMissingWeeksInsteadOfSilentlySkipping() {
        val term = SchoolPortalJson.semester(JSONObject("""{"code":200,"semestersConfig":{"startDate":"2026-09-09","totalWeeks":20}}"""), false)
        assertEquals(LocalDate.of(2026, 9, 7), term.startMonday)
        val terms = SchoolPortalJson.semesters(JSONObject("""{"code":200,"semesters":[{"value":"2026-2027-1","label":"秋季","isCurrent":true,"isXxq":true}]}"""))
        assertEquals(listOf(false, true), terms.map { it.smallTerm })
        val rows = SchoolPortalJson.lessons(JSONObject("""{"code":200,"list":[{"course":"设计基础","teacherName":null,"spaceName":"实训1楼","DayIndex":2,"startNode":"3","endNode":4}]}"""), 7)
        assertEquals("设计基础", rows.single().name)
        assertEquals(7, rows.single().week)
        assertEquals(2, rows.single().weekday)
        assertEquals("", rows.single().teacher)
        assertTrue(SchoolPortalJson.lessons(JSONObject("""{"code":200,"list":[]}"""), 20).isEmpty())
        assertTrue(runCatching { SchoolPortalJson.lessons(JSONObject("""{"code":200}"""), 1) }.isFailure)
        assertTrue(runCatching { SchoolPortalJson.lessons(JSONObject("""{"code":401,"list":[]}"""), 1) }.isFailure)
        assertTrue(runCatching { SchoolPortalJson.lessons(JSONObject("""{"code":200,"list":[{"course":"Bad","DayIndex":1,"startNode":"x","endNode":2}]}"""), 1) }.isFailure)
    }

    @Test fun backupRoundTripIsOfflineAndDoesNotPersistUnknownCredentialFields() {
        val bytes = ByteArrayOutputStream().also { ScheduleBackup.write(it, fixture()) }.toByteArray()
        assertEquals(fixture(), ScheduleBackup.read(ByteArrayInputStream(bytes)))
        val json = JSONObject(String(bytes, Charsets.UTF_8)).put("token", "must-not-survive")
        val restored = ScheduleBackup.read(ByteArrayInputStream(json.toString().toByteArray()))
        val clean = ByteArrayOutputStream().also { ScheduleBackup.write(it, restored) }.toString("UTF-8")
        assertFalse(clean.contains("must-not-survive"))
        assertFalse(clean.contains("token"))
        assertTrue(runCatching { ScheduleBackup.read(ByteArrayInputStream("{}".toByteArray())) }.isFailure)
        assertTrue(runCatching { ScheduleBackup.read(ByteArrayInputStream(ByteArray(1024 * 1024 + 1))) }.isFailure)
    }

    @Test fun collectorReadsAllWeeksIncludingEmptyMiddleAndKeepsOnlyActualOccurrences() {
        val seen = mutableListOf<Int>()
        val progress = mutableListOf<Int>()
        val client = SchoolPortalClient { path, body ->
            when (path) {
                "/semesterConfig" -> JSONObject("""{"code":200,"semestersConfig":{"startDate":"2026-09-07","totalWeeks":3}}""")
                "/schedule" -> {
                    val week = body!!.getInt("week"); seen.add(week)
                    assertEquals("2026-2027-1", body.getString("semester"))
                    assertEquals(0, body.getInt("showxxq"))
                    if (week == 2) JSONObject("""{"code":200,"list":[]}""")
                    else JSONObject("""{"code":200,"list":[{"course":"真实时段","teacherName":"A","spaceName":"实训1楼","DayIndex":1,"startNode":1,"endNode":2}]}""")
                }
                "/scheduleTime" -> JSONObject("""{"1":{"jcdm":1,"jcmc":"第1节","jcskkssj":"08:00:00","jcskjssj":"08:45:00"},"2":{"jcdm":2,"jcmc":"第2节","jcskkssj":"08:55:00","jcskjssj":"09:40:00"}}""")
                else -> error("Unexpected request")
            }
        }
        val result = client.collect(SchoolTerm("2026-2027-1", "秋季"), AtomicBoolean(false)) { week, total -> assertEquals(3, total); progress.add(week) }
        assertEquals(listOf(1, 2, 3), seen)
        assertEquals(listOf(1, 2, 3), progress)
        assertTrue(ScheduleCalendar.week(result.data, 1).isNotEmpty())
        assertTrue(ScheduleCalendar.week(result.data, 2).isEmpty())
        assertTrue(ScheduleCalendar.week(result.data, 3).isNotEmpty())
        assertTrue(result.warnings.isEmpty())
        assertEquals("08:00", result.data.periods.first().start.toString())
    }

    @Test fun collectorRejectsMissingWeekAndCancellationWithoutReturningPartialTerm() {
        val client = SchoolPortalClient { path, _ -> when (path) {
            "/semesterConfig" -> JSONObject("""{"code":200,"semestersConfig":{"startDate":"2026-09-07","totalWeeks":2}}""")
            "/schedule" -> JSONObject("""{"code":200}""")
            else -> error("Unexpected request")
        } }
        assertTrue(runCatching { client.collect(SchoolTerm("term", "学期"), AtomicBoolean(false)) { _, _ -> } }.isFailure)
        assertTrue(runCatching { client.collect(SchoolTerm("term", "学期"), AtomicBoolean(true)) { _, _ -> } }.exceptionOrNull() is java.io.InterruptedIOException)
    }

    @Test fun varyingBellTimesAreNotMisrepresentedAsAUnifiedOfflineTimetable() {
        val client = SchoolPortalClient { path, body -> when(path) {
            "/semesterConfig" -> JSONObject("""{"code":200,"semestersConfig":{"startDate":"2026-09-07","totalWeeks":2}}""")
            "/schedule" -> JSONObject("""{"code":200,"list":[]}""")
            "/scheduleTime" -> JSONObject("""{"1":{"jcdm":1,"jcmc":"第1节","jcskkssj":"${if (body!!.getInt("dqz") == 1) "08:00:00" else "08:30:00"}","jcskjssj":"09:15:00"}}""")
            else -> error("Unexpected request")
        } }
        val result = client.collect(SchoolTerm("term", "学期"), AtomicBoolean(false)) { _, _ -> }
        assertTrue(result.warnings.isNotEmpty())
        assertTrue(result.data.periods.all { it.start == null && it.end == null })
    }

    @Test fun replacementPreservesUndoAndRejectsAConcurrentEditWithoutChangingEitherFile() {
        val file = File(context.filesDir, ScheduleStore.FILE_NAME)
        val undo = File(context.filesDir, ScheduleStore.IMPORT_UNDO_FILE)
        val saved = file.takeIf { it.exists() }?.readBytes()
        val undoSaved = undo.takeIf { it.exists() }?.readBytes()
        try {
            file.delete(); undo.delete()
            val store = ScheduleStore(context)
            val original = store.commit(store.load(), fixture())
            val imported = fixture().copy(courses = fixture().courses.map { it.copy(name = "学校课程") })
            assertEquals(imported, store.replaceWithBackup(original, imported))
            assertEquals(original, store.previousImport())
            assertEquals(imported, ScheduleStore(context).load())
            store.replaceWithBackup(imported, imported)
            assertEquals("Repeated identical import must retain the original undo", original, store.previousImport())
            val concurrent = store.commit(imported, imported.copy(courses = imported.courses.map { it.copy(classroom = "新教室") }))
            assertTrue(runCatching { store.replaceWithBackup(imported, original) }.exceptionOrNull() is ScheduleConflictException)
            assertEquals(concurrent, store.load())
            assertEquals(original, store.previousImport())
            val invalid = imported.copy(courses = imported.courses.map { it.copy(weekday = 8) })
            assertTrue(runCatching { store.replaceWithBackup(concurrent, invalid) }.isFailure)
            assertEquals(concurrent, store.load())
            assertEquals(original, store.previousImport())
        } finally {
            if (saved == null) file.delete() else file.writeBytes(saved)
            if (undoSaved == null) undo.delete() else undo.writeBytes(undoSaved)
        }
    }
}
