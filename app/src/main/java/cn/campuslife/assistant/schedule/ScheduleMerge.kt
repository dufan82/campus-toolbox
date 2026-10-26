package cn.campuslife.assistant.schedule

class ScheduleConflictException(message: String, val latest: ScheduleData) : IllegalStateException(message)

object ScheduleMerge {
    /** Apply only this editor's changes, preserving unrelated latest records and settings. */
    fun merge(before: ScheduleData, proposed: ScheduleData, latest: ScheduleData): ScheduleData {
        before.checked(); proposed.checked(); latest.checked()
        fun <T> field(original: T, changed: T, current: T, message: String): T {
            if (original == changed) return current
            if (current != original && current != changed) throw ScheduleConflictException(message, latest)
            return changed
        }
        val semester = field(before.semester, proposed.semester, latest.semester, "其他页面已修改学期，请保留草稿并重新打开设置核对")
        val periods = field(before.periods, proposed.periods, latest.periods, "其他页面已修改节次，请保留草稿并重新打开设置核对")
        val old = before.courses.associateBy { it.id }
        val requested = proposed.courses.associateBy { it.id }
        val current = latest.courses.associateBy { it.id }.toMutableMap()
        (old.keys + requested.keys).filter { old[it] != requested[it] }.forEach { id ->
            if (current[id] != old[id] && current[id] != requested[id]) {
                throw ScheduleConflictException("这条课程已被其他页面修改或删除。草稿已保留，请取消后重新打开课程核对", latest)
            }
            requested[id]?.let { current[id] = it } ?: current.remove(id)
        }
        return try { ScheduleData(semester, periods, current.values.toList()).checked() }
        catch (_: IllegalArgumentException) {
            throw ScheduleConflictException("当前课程与其他页面更新的学期或节次不兼容。草稿已保留，请重新核对", latest)
        }
    }
}
