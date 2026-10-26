package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test

class SchedulePresentationTest {
    private fun course(id: Int, start: Int, end: Int, day: Int = 1) = Course("00000000-0000-0000-0000-${id.toString().padStart(12, '0')}", "设计课", "", "", day, start, end, 1, 20, WeekPattern.EVERY, null)
    @Test fun consecutivePeriodsAreOneBlockAndAdjacentCoursesRemainSeparate() {
        val first = course(1, 1, 2); val second = course(2, 3, 4)
        val blocks = SchedulePresentation.blocks(listOf(second, first))
        assertEquals(listOf(1, 3), blocks.map { it.startPeriod })
        assertEquals(listOf(2, 4), blocks.map { it.endPeriod })
        assertEquals(listOf(first, second), blocks.flatMap { it.courses })
    }
    @Test fun connectedOverlapsAreGroupedButNeverDiscardedOrMergedInStorage() {
        val courses = listOf(course(1, 1, 2), course(2, 2, 3), course(3, 3, 4), course(4, 1, 2, 2))
        val blocks = SchedulePresentation.blocks(courses.reversed())
        assertEquals(2, blocks.size)
        assertEquals(1, blocks.first().startPeriod); assertEquals(4, blocks.first().endPeriod)
        assertEquals(courses.take(3), blocks.first().courses)
        assertEquals(courses.map { it.id }.toSet(), blocks.flatMap { it.courses }.map { it.id }.toSet())
        assertEquals(blocks, SchedulePresentation.blocks(courses))
    }
    @Test fun emptyAndSinglePeriodHaveNoDuplicateNumbering() {
        assertTrue(SchedulePresentation.blocks(emptyList()).isEmpty())
        assertEquals("第1节", SchedulePresentation.periodLabel(1))
        assertEquals("第1节", SchedulePresentation.periodRange(course(1, 1, 1)))
        assertEquals("第1–2节", SchedulePresentation.periodRange(course(1, 1, 2)))
        assertEquals("第23–24节", SchedulePresentation.periodRange(course(1, 23, 24)))
    }
    @Test fun courseColorIsStableAcrossWeeksAndNamesAlwaysMapToThePalette() {
        listOf("设计课", "", "A".repeat(80), "高级数字绘画", "\uFFFF".repeat(40)).forEach {
            assertTrue(SchedulePresentation.colorIndex(it) in 0..5)
            assertEquals(SchedulePresentation.colorIndex(it), SchedulePresentation.colorIndex(String(it.toCharArray())))
        }
    }
}
