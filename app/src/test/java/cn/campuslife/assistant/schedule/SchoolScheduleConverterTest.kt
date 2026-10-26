package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class SchoolScheduleConverterTest {
    private val semester = Semester(LocalDate.of(2026, 9, 7), 20)
    private val periods = (1..12).map { SchedulePeriod("Period $it") }

    private fun lesson(week: Int = 1) = SchoolLesson("数字媒体", "示例老师", "A101", 1, 1, 2, week)
    private fun convert(
        lessons: List<SchoolLesson>,
        inputSemester: Semester = semester,
        inputPeriods: List<SchedulePeriod> = periods
    ) = SchoolScheduleConverter.convert(inputSemester, inputPeriods, lessons)

    private fun weeks(course: Course) = (1..60).filter { course.occurs(it) }
    private fun ranges(data: ScheduleData) = data.courses.map { Triple(it.startWeek, it.endWeek, it.pattern) }
    private fun occurrences(data: ScheduleData): List<SchoolLesson> = data.courses.flatMap { course ->
        weeks(course).map { week ->
            SchoolLesson(course.name, course.teacher, course.classroom, course.weekday,
                course.startPeriod, course.endPeriod, week)
        }
    }

    @Test fun emptySemesterPreservesPeriodDefinitions() {
        val custom = listOf(SchedulePeriod("Morning", LocalTime.of(8, 0), LocalTime.of(8, 45)))
        val result = convert(emptyList(), inputPeriods = custom)
        assertEquals(semester, result.semester)
        assertEquals(custom, result.periods)
        assertTrue(result.courses.isEmpty())
        result.checked()
    }

    @Test fun contiguousWeeksBecomeEveryRange() {
        val result = convert(listOf(2, 3, 4, 5).map { lesson(it) })
        assertEquals(listOf(Triple(2, 5, WeekPattern.EVERY)), ranges(result))
        assertEquals("数字媒体", result.courses.single().name)
        assertEquals("示例老师", result.courses.single().teacher)
        assertEquals("A101", result.courses.single().classroom)
        assertNull(result.courses.single().placeId)
    }

    @Test fun oddWeeksBecomeOddRange() {
        val result = convert(listOf(1, 3, 5, 7).map { lesson(it) })
        assertEquals(listOf(Triple(1, 7, WeekPattern.ODD)), ranges(result))
        assertEquals(listOf(1, 3, 5, 7), weeks(result.courses.single()))
    }

    @Test fun evenWeeksBecomeEvenRange() {
        val result = convert(listOf(2, 4, 6, 8).map { lesson(it) })
        assertEquals(listOf(Triple(2, 8, WeekPattern.EVEN)), ranges(result))
        assertEquals(listOf(2, 4, 6, 8), weeks(result.courses.single()))
    }

    @Test fun singletonUsesExactWeek() {
        assertEquals(listOf(Triple(6, 6, WeekPattern.EVERY)), ranges(convert(listOf(lesson(6)))))
    }

    @Test fun separatedOrdinaryWeeksStayAbsent() {
        val input = listOf(1, 2, 5, 6, 10).map { lesson(it) }
        val result = convert(input)
        assertEquals(input.toSet(), occurrences(result).toSet())
        assertEquals(listOf(
            Triple(1, 2, WeekPattern.EVERY), Triple(5, 6, WeekPattern.EVERY),
            Triple(10, 10, WeekPattern.EVERY)
        ), ranges(result))
    }

    @Test fun separatedOddRunsStaySeparate() {
        val result = convert(listOf(1, 3, 7, 9).map { lesson(it) })
        assertEquals(listOf(Triple(1, 3, WeekPattern.ODD), Triple(7, 9, WeekPattern.ODD)), ranges(result))
        assertFalse(result.courses.any { it.occurs(5) })
    }

    @Test fun separatedEvenRunsStaySeparate() {
        val result = convert(listOf(2, 4, 8, 10).map { lesson(it) })
        assertEquals(listOf(Triple(2, 4, WeekPattern.EVEN), Triple(8, 10, WeekPattern.EVEN)), ranges(result))
        assertFalse(result.courses.any { it.occurs(6) })
    }

    @Test fun mixedParityWeeksAreCoveredExactlyOnce() {
        val input = listOf(1, 2, 3, 5, 7, 8, 12, 14, 15).map { lesson(it) }
        val actual = occurrences(convert(input))
        assertEquals(input.toSet(), actual.toSet())
        assertEquals(input.size, actual.size)
    }

    @Test fun duplicateLessonRowsProduceOneCourse() {
        val result = convert(listOf(lesson(1), lesson(3), lesson(1), lesson(3)))
        assertEquals(listOf(Triple(1, 3, WeekPattern.ODD)), ranges(result))
        assertEquals(2, occurrences(result).size)
    }

    @Test fun reschedulingKeepsOriginalSlotAbsentInMovedWeek() {
        val moved = lesson(3).copy(weekday = 4, classroom = "B202", startPeriod = 5, endPeriod = 6)
        val input = listOf(lesson(1), lesson(2), moved, lesson(4), lesson(5))
        val result = convert(input)
        assertEquals(input.toSet(), occurrences(result).toSet())
        val thirdWeek = ScheduleCalendar.week(result, 3)
        assertEquals(1, thirdWeek.size)
        assertEquals("B202", thirdWeek.single().classroom)
        assertEquals(4, thirdWeek.single().weekday)
        assertEquals(5, thirdWeek.single().startPeriod)
    }

    @Test fun eachIdentityFieldKeepsSeparateGroup() {
        val first = lesson()
        val input = listOf(first, first.copy(name = "另一课程"), first.copy(teacher = "另一老师"),
            first.copy(classroom = "B202"), first.copy(weekday = 2),
            first.copy(startPeriod = 2), first.copy(endPeriod = 3))
        val result = convert(input)
        assertEquals(input.toSet(), occurrences(result).toSet())
        assertEquals(7, result.courses.size)
        assertEquals(7, result.courses.map { it.id }.toSet().size)
    }

    @Test fun inputOrderDoesNotChangeFullSchedule() {
        val input = listOf(lesson(3), lesson(1), lesson(8), lesson(10),
            lesson(4).copy(weekday = 5), lesson(2).copy(name = "Another"))
        val result = convert(input)
        assertEquals(result, convert(input.reversed()))
        assertEquals(result, convert(input.drop(2) + input.take(2)))
        result.courses.forEach { assertEquals(it.id, UUID.fromString(it.id).toString()) }
    }

    @Test fun differentSemesterMondayChangesIds() {
        val first = convert(listOf(lesson())).courses.single().id
        val next = convert(listOf(lesson()), semester.copy(startMonday = semester.startMonday.plusWeeks(24)))
        assertNotEquals(first, next.courses.single().id)
    }

    @Test fun semesterLengthChangesIds() {
        val first = convert(listOf(lesson())).courses.single().id
        val next = convert(listOf(lesson()), semester.copy(totalWeeks = 21))
        assertNotEquals(first, next.courses.single().id)
    }

    @Test fun duplicateRowsDoNotChangeIds() {
        val input = listOf(lesson(1), lesson(3), lesson(7))
        assertEquals(convert(input), convert(input + input.reversed()))
    }

    @Test fun unrelatedCourseDoesNotChangeExistingIds() {
        val input = listOf(lesson(1), lesson(3), lesson(7))
        val before = convert(input).courses
        val after = convert(input + lesson(2).copy(name = "Unrelated")).courses
        assertEquals(before, after.filter { it.name == "数字媒体" })
    }

    @Test fun identityFieldsAndWeekIntervalsChangeIds() {
        val first = lesson()
        val variants = listOf(first, first.copy(name = "Other"), first.copy(teacher = "Other"),
            first.copy(classroom = "Other"), first.copy(weekday = 2), first.copy(startPeriod = 2),
            first.copy(endPeriod = 3), first.copy(week = 2))
        assertEquals(8, variants.map { convert(listOf(it)).courses.single().id }.toSet().size)
        assertNotEquals(convert(listOf(first)).courses.single().id,
            convert(listOf(first, lesson(3))).courses.single().id)
    }

    @Test fun separatorsInsideTextDoNotCollide() {
        val first = lesson().copy(name = "A|B", teacher = "C:1")
        val second = lesson().copy(name = "A", teacher = "B|C:1")
        val result = convert(listOf(first, second))
        assertEquals(2, result.courses.map { it.id }.toSet().size)
        assertEquals(setOf(first, second), occurrences(result).toSet())
    }

    @Test fun acceptsMaxTextLengthsAndOptionalEmptyFields() {
        val longest = lesson().copy(name = "课".repeat(80), teacher = "师".repeat(80), classroom = "室".repeat(120))
        val optional = lesson().copy(name = "Optional", teacher = "", classroom = "")
        assertEquals(setOf(longest, optional), occurrences(convert(listOf(longest, optional))).toSet())
    }

    @Test fun rejectsBlankName() {
        listOf("", " ", "\u3000").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { convert(listOf(lesson().copy(name = name))) }
        }
    }

    @Test fun rejectsOversizedTextFields() {
        listOf(lesson().copy(name = "x".repeat(81)), lesson().copy(teacher = "x".repeat(81)),
            lesson().copy(classroom = "x".repeat(121))).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { convert(listOf(invalid)) }
        }
    }

    @Test fun rejectsControlCharactersInEveryTextField() {
        listOf('\u0000', '\t', '\n', '\r', '\u001f', '\u007f', '\u0085', '\u009f').forEach { control ->
            listOf(lesson().copy(name = "A${control}B"), lesson().copy(teacher = "A${control}B"),
                lesson().copy(classroom = "A${control}B")).forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) { convert(listOf(invalid)) }
            }
        }
    }

    @Test fun acceptsFirstAndLastWeekWeekdayAndPeriod() {
        val input = listOf(lesson().copy(endPeriod = 1),
            lesson(60).copy(weekday = 7, startPeriod = 24, endPeriod = 24))
        val result = convert(input, semester.copy(totalWeeks = 60), (1..24).map { SchedulePeriod("P$it") })
        assertEquals(input.toSet(), occurrences(result).toSet())
        result.checked()
    }

    @Test fun rejectsInvalidWeeks() {
        listOf(Int.MIN_VALUE, -1, 0, 21, Int.MAX_VALUE).forEach { week ->
            assertThrows(IllegalArgumentException::class.java) { convert(listOf(lesson(week))) }
        }
    }

    @Test fun rejectsInvalidWeekdays() {
        listOf(Int.MIN_VALUE, 0, 8, Int.MAX_VALUE).forEach { weekday ->
            assertThrows(IllegalArgumentException::class.java) { convert(listOf(lesson().copy(weekday = weekday))) }
        }
    }

    @Test fun rejectsInvalidPeriodRanges() {
        listOf(lesson().copy(startPeriod = 0), lesson().copy(startPeriod = 13, endPeriod = 13),
            lesson().copy(endPeriod = 0), lesson().copy(startPeriod = 3, endPeriod = 2),
            lesson().copy(endPeriod = 13), lesson().copy(startPeriod = Int.MIN_VALUE),
            lesson().copy(endPeriod = Int.MAX_VALUE)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { convert(listOf(invalid)) }
        }
    }

    @Test fun rejectsInvalidSemestersForEmptyLessonList() {
        listOf(semester.copy(startMonday = semester.startMonday.plusDays(1)),
            semester.copy(totalWeeks = 0), semester.copy(totalWeeks = 61),
            Semester(LocalDate.of(1899, 1, 2), 20), Semester(LocalDate.of(2201, 1, 5), 20)
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { convert(emptyList(), invalid) }
        }
    }

    @Test fun rejectsInvalidPeriodsForEmptyLessonList() {
        val timed = SchedulePeriod("P", LocalTime.of(8, 0), LocalTime.of(8, 45))
        listOf(emptyList(), (1..25).map { SchedulePeriod("P$it") }, listOf(SchedulePeriod(" ")),
            listOf(SchedulePeriod("x".repeat(25))), listOf(SchedulePeriod("P\n")),
            listOf(timed.copy(end = null)), listOf(timed.copy(end = LocalTime.of(7, 45))),
            listOf(timed.copy(start = LocalTime.of(8, 0, 1))), listOf(timed, timed)
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { convert(emptyList(), inputPeriods = invalid) }
        }
    }

    @Test fun invalidMiddleRowFailsWholeConversion() {
        assertThrows(IllegalArgumentException::class.java) {
            convert(listOf(lesson(1), lesson(0), lesson(3)))
        }
    }

    @Test fun maxFiveHundredCoursesAreAccepted() {
        val result = convert((1..500).map { lesson().copy(name = "Course $it") })
        assertEquals(500, result.courses.size)
        assertEquals(500, result.courses.map { it.id }.toSet().size)
        result.checked()
    }

    @Test fun fiveHundredAndOneCoursesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            convert((1..501).map { lesson().copy(name = "Course $it") })
        }
    }

    @Test fun splitRangesCountTowardFiveHundredLimit() {
        val input = (1..251).flatMap { index ->
            listOf(lesson(1).copy(name = "Course $index"), lesson(5).copy(name = "Course $index"))
        }
        assertThrows(IllegalArgumentException::class.java) { convert(input) }
    }

    @Test fun largeRepeatedInputMergesBeforeCourseLimit() {
        val input = (1..500).flatMap { index -> (1..60).map { lesson(it).copy(name = "Course $index") } }
        val result = convert(input, semester.copy(totalWeeks = 60))
        assertEquals(500, result.courses.size)
        assertTrue(result.courses.all { it.startWeek == 1 && it.endWeek == 60 && it.pattern == WeekPattern.EVERY })
        assertEquals(30_000, occurrences(result).size)
        val duplicated = convert(List(30_000) { lesson() })
        assertEquals(1, duplicated.courses.size)
    }

    @Test fun tooManyRawRowsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { convert(List(30_001) { lesson() }) }
    }

    @Test fun allWeekSubsetsPreserveExactLessonSetWithoutOverlaps() {
        for (mask in 0 until (1 shl 10)) {
            val input = (1..10).filter { mask and (1 shl (it - 1)) != 0 }.map { lesson(it) }
            val result = convert(input)
            val actual = occurrences(result)
            assertEquals("Unexpected teaching weeks for subset $mask", input.toSet(), actual.toSet())
            assertEquals("Overlapping ranges for subset $mask", input.size, actual.size)
            result.checked()
        }
    }

    @Test fun laterInputMutationDoesNotChangeConvertedPeriodsOrCourses() {
        val inputPeriods = periods.toMutableList()
        val inputLessons = mutableListOf(lesson())
        val result = convert(inputLessons, inputPeriods = inputPeriods)
        inputPeriods.clear()
        inputLessons.clear()
        assertEquals(12, result.periods.size)
        assertEquals(1, result.courses.size)
        result.checked()
    }
}
