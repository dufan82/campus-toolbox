package cn.campuslife.assistant.schedule

import java.time.LocalDate

data class SchoolCheckRecord(val at:Long,val result:String,val details:String,val outcome:SchoolCheckOutcome?=null)
object SchoolCheckHistory {
    const val MAX_DETAILS = 64000

    fun needsConflictRecheck(outcome: SchoolCheckOutcome, details: String): Boolean = outcome.protectedGroups > 0 &&
        !(details.contains(" · 未应用：") && details.contains("\n本地：") && details.contains("\n学校："))

    fun boundedDetails(value: String): String = if (value.length <= MAX_DETAILS) value else
        value.take(MAX_DETAILS - 40) + "\n…详情过长，部分课程未展示；请在学校与本地课表核对。"

    /** Render the school's evidence, not just the successfully written subset. No ownership changes. */
    fun details(plan: SchoolAutoPlan, today: LocalDate, applied: Boolean): String {
        val semester = plan.data.semester ?: return ""
        fun describe(courses: List<Course>): String {
            val lines = courses.mapNotNull { c ->
                val weeks = (c.startWeek..c.endWeek).filter { c.occurs(it) &&
                    !semester.startMonday.plusWeeks((it-1).toLong()).plusDays((c.weekday-1).toLong()).isBefore(today) }
                if (weeks.isEmpty()) null else {
                    fun date(w: Int) = semester.startMonday.plusWeeks((w-1).toLong()).plusDays((c.weekday-1).toLong())
                    "${c.name} · ${c.classroom.ifBlank { "教室未填" }} · ${c.teacher.ifBlank { "教师未填" }} · " +
                        "周${c.weekday} 第${c.startPeriod}–${c.endPeriod}节 · 第${weeks.joinToString(",")}周（${date(weeks.first())}至${date(weeks.last())}）"
                }
            }
            // Do not distinct(): duplicate occurrences are real multiset differences.
            return lines.joinToString("\n").ifBlank { "无今天起的课程" }
        }
        return boundedDetails(plan.differences.joinToString("\n\n") { d ->
            val status = if (d.blocked) "未应用：${d.reasons.joinToString("；") { it.explanation }}" else
                if (applied) "已应用（仅今天起）" else "待确认，尚未应用（仅今天起）"
            "${d.name} · $status\n本地：${describe(d.local)}\n学校：${describe(d.school)}" +
                if (d.blockers.isEmpty()) "" else "\n冲突的本地课程：${describe(d.blockers)}"
        })
    }

    fun latest(auto:DailySyncState?,manual:SchoolCheckRecord?):SchoolCheckRecord? {
        if(manual!=null && manual.at>=(auto?.lastAttempt?:0))return manual
        if(auto==null || auto.lastAttempt==0L)return null
        val result=when(auto.result) {
            SyncResult.NO_CHANGE -> "已检查，课表没有变化"
            SyncResult.UPDATED -> "已检查，课表已更新"
            else -> DailySyncPresentation.summary(auto.copy(enabled=true))
        }
        return SchoolCheckRecord(auto.lastAttempt,result,auto.details.ifBlank {
            auto.summary.takeIf {auto.result in setOf(SyncResult.UPDATED,SyncResult.CONFLICT)}.orEmpty()
        },SchoolCheckOutcome.automatic(auto))
    }
    fun details(before:ScheduleData,after:ScheduleData,today:LocalDate):String {
        val diff=ScheduleUpdatePreview.compare(before,after,today)
        fun describe(label:String,rows:List<ScheduleOccurrence>):String {
            val unique=rows.distinct()
            if(unique.isEmpty())return ""
            val lines=unique.take(12).joinToString("\n") {
                listOf("${it.date.monthValue}月${it.date.dayOfMonth}日",it.name,
                    it.classroom.ifBlank {"教室未填"},it.teacher.takeIf {name->name.isNotBlank()},
                    "第${it.startPeriod}–${it.endPeriod}节").filterNotNull().joinToString(" · ")
            }
            return label+"\n"+lines+if(unique.size>12) "\n另${unique.size-12}次课程" else ""
        }
        return listOf(describe("更新后",diff.onlySchool),describe("更新前",diff.onlyLocal)).filter {it.isNotEmpty()}.joinToString("\n\n").take(4000)
    }
}
