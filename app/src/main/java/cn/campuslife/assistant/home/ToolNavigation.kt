package cn.campuslife.assistant.home

import android.app.Activity
import android.content.Intent
import cn.campuslife.assistant.ui.WatermarkActivity
import cn.campuslife.assistant.map.CampusMapActivity
import cn.campuslife.assistant.schedule.ScheduleActivity
import cn.campuslife.assistant.planner.PlannerActivity

object ToolNavigation {
    fun open(activity: Activity,id: String) {
        val type=when(id) {
            "map"->CampusMapActivity::class.java
            "watermark"->WatermarkActivity::class.java
            "schedule"->ScheduleActivity::class.java
            "planner"->PlannerActivity::class.java
            else->return
        }
        activity.startActivity(Intent(activity,type))
    }
}
