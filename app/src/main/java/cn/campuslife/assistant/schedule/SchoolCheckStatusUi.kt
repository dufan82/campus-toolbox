package cn.campuslife.assistant.schedule

import android.app.Activity
import android.widget.LinearLayout
import cn.campuslife.assistant.ui.Ui

/** Two explicit result rows reused by manual and automatic check pages. */
object SchoolCheckStatusUi {
    fun add(activity:Activity,body:LinearLayout,outcome:SchoolCheckOutcome,details:String="") {
        val text=SchoolCheckOutcomeText.from(outcome)
        body.addView(Ui.space(activity,10))
        body.addView(Ui.text(activity,"学校课表：${text.school}",14f).apply {tag="school_check_school"})
        body.addView(Ui.space(activity,6))
        body.addView(Ui.text(activity,"本机课表：${text.local}",14f).apply {tag="school_check_local"})
        if(text.note.isNotEmpty()){
            body.addView(Ui.space(activity,6))
            body.addView(Ui.text(activity,text.note,13f,Ui.muted).apply {tag="school_check_note"})
        }
        if(SchoolCheckHistory.needsConflictRecheck(outcome,details)) {
            body.addView(Ui.space(activity,6))
            body.addView(Ui.text(activity,"旧结果未重新核验，且缺少冲突课程明细；请重新检查。",13f,Ui.muted)
                .apply {tag="school_check_recheck"})
        }
    }
}
