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
            "story"->cn.campuslife.assistant.story.StoryActivity::class.java
            "daily60"->cn.campuslife.assistant.daily.Daily60Activity::class.java
            else->return
        }
        if(id=="schedule" && activity is cn.campuslife.assistant.ui.MainActivity) {
            cn.campuslife.assistant.ui.MainNavigation.navigate(activity,"home","schedule")
        } else activity.startActivity(Intent(activity,type).apply {
            if(id=="schedule")addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
    }
}
