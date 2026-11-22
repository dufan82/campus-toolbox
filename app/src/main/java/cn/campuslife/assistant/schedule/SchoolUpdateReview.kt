package cn.campuslife.assistant.schedule

import java.time.LocalDate

data class SchoolReviewPlan(val preview:SchoolCheckPreview,val legacyToConfirm:Set<String>)

/** Presentation-only proposals. The store still validates explicit consent and all protections. */
object SchoolUpdateReview {
    fun plan(snapshot:ScheduleSnapshot,school:ScheduleData,today:LocalDate):SchoolReviewPlan {
        val original=SchoolUpdateCheck.preview(snapshot,school,today)
        val legacy=original.confirmableLegacy
        return SchoolReviewPlan(if(legacy.isEmpty())original else SchoolUpdateCheck.preview(snapshot,school,today,legacy),legacy)
    }
    fun summary(plan:SchoolAutoPlan,today:LocalDate,applied:Boolean):String {
        val semester=plan.data.semester ?: return ""
        fun weeks(c:Course)=(c.startWeek..c.endWeek).filter {c.occurs(it) &&
            !semester.startMonday.plusWeeks((it-1).toLong()).plusDays((c.weekday-1).toLong()).isBefore(today)}
        fun times(cs:List<Course>)=cs.map {"周${"一二三四五六日"[it.weekday-1]} · 第${it.startPeriod}–${it.endPeriod}节"}.distinct().joinToString("；")
        fun values(cs:List<Course>,field:(Course)->String)=cs.map {field(it).ifBlank {"未填写"}}.distinct().joinToString("、")
        return SchoolCheckHistory.boundedDetails(plan.differences.joinToString("\n\n") {d->
            val before=d.local.filter {weeks(it).isNotEmpty()};val after=d.school.filter {weeks(it).isNotEmpty()}
            val lines=mutableListOf("${d.name} · ${if(d.blocked)"未更新" else if(applied)"已更新" else "待更新"}")
            if(before.isEmpty())lines+="新增课程：${values(after) {it.classroom}}\n${times(after)}"
            else if(after.isEmpty())lines+="取消课程：${values(before) {it.classroom}}\n${times(before)}"
            else if(before.size>1 || after.size>1) {
                fun describe(cs:List<Course>)=cs.joinToString("\n") {
                    "${times(listOf(it))} · ${it.classroom.ifBlank {"教室未填"}} · ${it.teacher.ifBlank {"教师未填"}} · 第${weeks(it).joinToString("、")}周"
                }
                lines+="原安排：\n${describe(before)}\n新安排：\n${describe(after)}"
            }
            else {
                fun field(label:String,get:(List<Course>)->String) {val a=get(before);val b=get(after);if(a!=b)lines+="$label：$a → $b"}
                field("教室") {values(it) {c->c.classroom}}
                field("教师") {values(it) {c->c.teacher}}
                val a=times(before);val b=times(after)
                if(a!=b)lines+="时间：$a → $b" else lines+=b
                fun weekText(cs:List<Course>)="第${cs.flatMap(::weeks).distinct().sorted().joinToString("、")}周"
                field("周次",::weekText)
            }
            if(d.blocked)lines+=d.reasons.map {when(it) {
                SchoolSkipReason.MANUAL_EDIT->"这门课有手动修改或地点关联，保留你的设置"
                SchoolSkipReason.DELETED->"你删除过这门课，不自动恢复"
                SchoolSkipReason.TIME_CONFLICT->"与${d.blockers.map {c->c.name}.distinct().joinToString("、")}时间重叠，暂不修改"
                SchoolSkipReason.UNKNOWN_ORIGIN->"这门旧课的来源需要核对，尚未更新"
                SchoolSkipReason.REVOKED_SOURCE->"无法确认是否为你修改或删除过的课程，暂不新增"
            }}.distinct()
            lines.joinToString("\n")
        })
    }
}
