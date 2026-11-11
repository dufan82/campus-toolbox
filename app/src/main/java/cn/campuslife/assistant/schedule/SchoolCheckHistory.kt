package cn.campuslife.assistant.schedule

import java.time.LocalDate

data class SchoolCheckRecord(val at:Long,val result:String,val details:String)
object SchoolCheckHistory {
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
        })
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
