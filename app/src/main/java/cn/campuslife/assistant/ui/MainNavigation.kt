package cn.campuslife.assistant.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import cn.campuslife.assistant.R
import cn.campuslife.assistant.schedule.ScheduleActivity

/** Bottom-tab navigation only. IDs, Activity reuse and stack policy match Ui.bottomNavigation. */
object MainNavigation {
    /** Physical entry edge of the destination, independent of task history or layout direction. */
    enum class Direction { FROM_LEFT, FROM_RIGHT, NONE }

    /** Pure rule: home (0), schedule (1), settings (2). Same/unknown IDs are no-ops. */
    fun direction(current: String,destination: String): Direction {
        val from=index(current);val to=index(destination)
        return when {
            from<0 || to<0 || from==to -> Direction.NONE
            to<from -> Direction.FROM_LEFT
            else -> Direction.FROM_RIGHT
        }
    }

    /** Call on the Activity's UI thread. Returns false without side effects for same/unknown IDs.
     * Does not finish/recreate Activities, mutate the source Intent or read/write user data. */
    fun navigate(activity: Activity,current: String,destination: String): Boolean {
        val direction=direction(current,destination)
        if(direction==Direction.NONE) return false
        val target=when(destination) {
            "home" -> MainActivity::class.java
            "schedule" -> ScheduleActivity::class.java
            else -> SettingsActivity::class.java
        }
        val flags=Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        val enter=if(direction==Direction.FROM_LEFT) R.anim.main_nav_enter_left else R.anim.main_nav_enter_right
        val exit=if(direction==Direction.FROM_LEFT) R.anim.main_nav_exit_right else R.anim.main_nav_exit_left
        // Attach options to the launch, including reused/REORDER_TO_FRONT Activities.
        // Platform XML transitions obey the system animation scale (including zero).
        val options=ActivityOptions.makeCustomAnimation(activity,enter,exit)
        activity.startActivity(Intent(activity,target).addFlags(flags),options.toBundle())
        return true
    }

    private fun index(id: String): Int=when(id) {
        "home" -> 0
        "schedule" -> 1
        "settings" -> 2
        else -> -1
    }
}
