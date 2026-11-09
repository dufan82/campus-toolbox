package cn.campuslife.assistant.schedule

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.feedback.FeedbackActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DailySyncVisualDeviceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList())
    private fun capture(name:String){
        inst.waitForIdleSync();Thread.sleep(150)
        val out=File(context.getExternalFilesDir(null),"daily-sync-screens").apply {mkdirs()}
        val mode=if(context.resources.configuration.fontScale>1.5f)"large"else"normal"
        val bitmap=inst.uiAutomation.takeScreenshot();File(out,"$name-$mode.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun syncSettingsFitsScrollableLargeTextAndRecreatesWithoutChangingPreference() {
        DailySchoolSync.setEnabled(context,false)
        ActivityScenario.launch(DailySyncSettingsActivity::class.java).use {scenario->
            Thread.sleep(350);scenario.onActivity {a->
                val root=a.window.decorView
                assertTrue(views(root).filterIsInstance<TextView>().any {it.text.contains("07:00")})
                assertFalse(root.findViewWithTag<Switch>("school_sync_switch").isChecked)
                assertTrue(views(root).any {it is ScrollView})
            };capture("daily-settings")
            scenario.recreate();Thread.sleep(350)
            scenario.onActivity {a->assertFalse(a.window.decorView.findViewWithTag<Switch>("school_sync_switch").isChecked)}
        }
    }
    @Test fun feedbackBottomActionIsReachableAtCurrentFontAndOrientation() {
        ActivityScenario.launch(FeedbackActivity::class.java).use {scenario->
            scenario.onActivity {a->
                val scroll=views(a.window.decorView).filterIsInstance<ScrollView>().single()
                scroll.post {scroll.fullScroll(View.FOCUS_DOWN)}
            };inst.waitForIdleSync();Thread.sleep(300)
            scenario.onActivity {a->
                val check=a.window.decorView.findViewWithTag<Button>("feedback_check_draft")
                assertTrue(check.getGlobalVisibleRect(Rect()));assertTrue(check.height>=44*context.resources.displayMetrics.density)
                check.performClick()
            };capture("feedback-bottom")
        }
    }
}
