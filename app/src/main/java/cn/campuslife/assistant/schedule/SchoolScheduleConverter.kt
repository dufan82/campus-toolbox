package cn.campuslife.assistant.schedule

import java.util.UUID

data class SchoolLesson(
    val name: String,
    val teacher: String,
    val classroom: String,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val week: Int
)

object SchoolScheduleConverter {
    fun convert(semester: Semester, periods: List<SchedulePeriod>, lessons: List<SchoolLesson>): ScheduleData {
        val base = ScheduleData(semester, periods.toList()).checked()
        // At most 500 output courses, each spanning at most 60 teaching weeks.
        require(lessons.size <= 30_000) { "逐次课程记录最多30000条" }
        val grouped = mutableMapOf<LessonSlot, MutableSet<Int>>()
        lessons.forEachIndexed { index, lesson ->
            val row = index + 1
            require(validText(lesson.name, 80) && lesson.name.isNotBlank()) { "第${row}条课程名称为空、过长或包含控制字符" }
            require(validText(lesson.teacher, 80)) { "第${row}条课程教师过长或包含控制字符" }
            require(validText(lesson.classroom, 120)) { "第${row}条课程教室过长或包含控制字符" }
            require(lesson.week in 1..semester.totalWeeks) { "第${row}条课程周次超出学期范围" }
            require(lesson.weekday in 1..7) { "第${row}条课程星期应为1–7" }
            require(lesson.startPeriod in 1..periods.size && lesson.endPeriod in lesson.startPeriod..periods.size) {
                "第${row}条课程节次超出范围或起止顺序错误"
            }
            val slot = LessonSlot(lesson.name, lesson.teacher, lesson.classroom,
                lesson.weekday, lesson.startPeriod, lesson.endPeriod)
            grouped.getOrPut(slot) { mutableSetOf() }.add(lesson.week)
        }

        val slots = grouped.keys.sortedWith(compareBy(
            { it.weekday }, { it.startPeriod }, { it.endPeriod }, { it.name }, { it.teacher }, { it.classroom }
        ))
        val courses = mutableListOf<Course>()
        slots.forEach { slot ->
            compactWeeks(grouped.getValue(slot)).forEach { range ->
                require(courses.size < 500) { "转换后课程超过500条，请缩小导入范围" }
                courses.add(Course(
                    stableId(semester, slot, range), slot.name, slot.teacher, slot.classroom,
                    slot.weekday, slot.startPeriod, slot.endPeriod,
                    range.start, range.end, range.pattern, null
                ))
            }
        }
        return base.copy(courses = courses.toList()).checked()
    }

    private fun validText(value: String, max: Int) = value.length <= max && value.none { it.isISOControl() }

    private data class LessonSlot(
        val name: String, val teacher: String, val classroom: String,
        val weekday: Int, val startPeriod: Int, val endPeriod: Int
    )

    private data class WeekRange(val start: Int, val end: Int, val pattern: WeekPattern)

    /** Partition sorted teaching weeks into exact ranges; a gap is never filled in. */
    private fun compactWeeks(weeks: Set<Int>): List<WeekRange> {
        val sorted = weeks.sorted()
        val counts = IntArray(sorted.size + 1)
        val ends = IntArray(sorted.size) { it }
        val patterns = Array(sorted.size) { WeekPattern.EVERY }
        for (start in sorted.lastIndex downTo 0) {
            counts[start] = 1 + counts[start + 1]
            for (step in 1..2) {
                var end = start + 1
                while (end < sorted.size && sorted[end] - sorted[end - 1] == step) {
                    val count = 1 + counts[end + 1]
                    // Prefer fewer ranges, then the longer first range to make ties deterministic.
                    if (count < counts[start] || (count == counts[start] && end > ends[start])) {
                        counts[start] = count
                        ends[start] = end
                        patterns[start] = when {
                            step == 1 -> WeekPattern.EVERY
                            sorted[start] % 2 == 1 -> WeekPattern.ODD
                            else -> WeekPattern.EVEN
                        }
                    }
                    end++
                }
            }
        }
        return buildList {
            var start = 0
            while (start < sorted.size) {
                val end = ends[start]
                add(WeekRange(sorted[start], sorted[end], patterns[start]))
                start = end + 1
            }
        }
    }

    private fun stableId(semester: Semester, slot: LessonSlot, range: WeekRange): String {
        val fields = listOf(
            "school-schedule-v1", semester.startMonday.toString(), semester.totalWeeks.toString(),
            slot.name, slot.teacher, slot.classroom, slot.weekday.toString(),
            slot.startPeriod.toString(), slot.endPeriod.toString(),
            range.start.toString(), range.end.toString(), range.pattern.name
        )
        // Length prefixes avoid ambiguous IDs when school text contains separators.
        val identity = fields.joinToString("") { "${it.length}:$it" }
        return UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
    }
}
