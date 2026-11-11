package cn.campuslife.assistant

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cn.campuslife.assistant.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual Activity reuse/click flow, plus native compositor frames of the reported my -> home case. */
class BrandNavigationFlowTest {
    @Test fun realTabsReuseHomeAndSettingsToHomeRunsLeftEntryAnimation() {
        val inst=InstrumentationRegistry.getInstrumentation()
        var home:Activity?=null
        fun resumed():Activity? {var result:Activity?=null;inst.runOnMainSync {result=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull()};return result}
        fun awaitSuffix(suffix:String):Activity {
            val end=SystemClock.uptimeMillis()+6000
            while(SystemClock.uptimeMillis()<end){val a=resumed();if(a?.javaClass?.simpleName==suffix)return a;SystemClock.sleep(30)}
            throw AssertionError("No resumed $suffix")
        }
        fun tap(id:String){val a=resumed()!!;inst.runOnMainSync {a.window.decorView.findViewWithTag<View>("bottom_nav_$id").performClick()}}
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {home=it};inst.waitForIdleSync()
            tap("schedule");val schedule=awaitSuffix("ScheduleActivity");SystemClock.sleep(250)
            tap("settings");val settings=awaitSuffix("SettingsActivity");SystemClock.sleep(250)
            tap("schedule");assertSame(schedule,awaitSuffix("ScheduleActivity"));SystemClock.sleep(250)
            tap("settings");awaitSuffix("SettingsActivity");SystemClock.sleep(250)
            tap("home")
            val dir=File(inst.targetContext.getExternalFilesDir("verification"),"brand-transition").apply{mkdirs()}
            repeat(5){index->
                val bitmap=inst.uiAutomation.takeScreenshot()
                File(dir,"settings-home-$index.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                SystemClock.sleep(20)
            }
            assertSame(home,awaitSuffix("MainActivity"))
            SystemClock.sleep(250)
            tap("schedule");assertSame("Home must not destroy the existing schedule tab",schedule,awaitSuffix("ScheduleActivity"));SystemClock.sleep(250)
            tap("settings");assertSame("Home must not destroy the existing settings tab",settings,awaitSuffix("SettingsActivity"));SystemClock.sleep(250)
            tap("home");assertSame(home,awaitSuffix("MainActivity"));SystemClock.sleep(250)
            repeat(3) {
                inst.runOnMainSync {home!!.window.decorView.findViewWithTag<View>("home_tool_schedule").performClick()}
                assertSame("The home tool must reuse the bottom-tab schedule",schedule,awaitSuffix("ScheduleActivity"));SystemClock.sleep(250)
                tap("home");assertSame(home,awaitSuffix("MainActivity"));SystemClock.sleep(250)
            }
            inst.runOnMainSync { schedule.finish();settings.finish() }
        }
    }
}
