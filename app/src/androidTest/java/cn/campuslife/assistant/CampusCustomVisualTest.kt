package cn.campuslife.assistant

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.map.*
import cn.campuslife.assistant.ui.MainActivity
import cn.campuslife.assistant.ui.Ui
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CampusCustomVisualTest {
    @Test fun smallDotsKeepA48dpTouchImageAndAVisibleSelectionHalo() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val normal=CampusMarkerIcons.bitmap(context,false)
        val selected=CampusMarkerIcons.bitmap(context,true)
        try {
            assertEquals(Ui.dp(context,48),normal.width)
            assertEquals(normal.width,normal.height)
            assertEquals(0,Color.alpha(normal.getPixel(normal.width/2,0)))
            assertEquals(Ui.blue,normal.getPixel(normal.width/2,normal.height/2))
            fun occupied(image: Bitmap): Int {
                var count=0
                for(y in 0 until image.height) for(x in 0 until image.width) if(Color.alpha(image.getPixel(x,y))>0) count++
                return count
            }
            assertTrue(occupied(selected)>occupied(normal))
        } finally { normal.recycle();selected.recycle() }
    }

    @Test fun customFormHasVisibleNativeSaveAndCancelActionsInBothOrientations() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        for(orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.requestedOrientation=orientation }
                val expected=if(orientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                val deadline=SystemClock.uptimeMillis()+5000
                var ready=false
                do {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { ready=it.resources.configuration.orientation==expected && it.window.decorView.hasWindowFocus() }
                    if(!ready) SystemClock.sleep(50)
                } while(!ready && SystemClock.uptimeMillis()<deadline)
                assertTrue("Activity did not settle after rotation",ready)
                lateinit var editor: CampusPlaceEditor
                scenario.onActivity { activity -> editor=CampusPlaceEditor(activity,null,"实训2楼北门",MapCategory.STUDY) { _,_->null };editor.show() }
                instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(150,5000)
                val root=instrumentation.uiAutomation.rootInActiveWindow
                assertNotNull(root)
                val windowBounds=Rect();root!!.getBoundsInScreen(windowBounds)
                for(text in listOf("保存并标位置","取消")) {
                    val node=root.findAccessibilityNodeInfosByText(text).firstOrNull { it.text?.toString()==text }
                    assertNotNull("Missing form action: $text",node)
                    val bounds=Rect();node!!.getBoundsInScreen(bounds)
                    assertTrue("Form action too short: $text",bounds.height()>=Ui.dp(instrumentation.targetContext,48))
                    assertTrue("Form action outside window: $text",windowBounds.contains(bounds))
                }
                if(InstrumentationRegistry.getArguments().getString("captureCustomVisuals")=="true") {
                    val filename=if(orientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) "custom-form-landscape.png" else "custom-form-portrait.png"
                    val screenshot=instrumentation.uiAutomation.takeScreenshot()
                    File(instrumentation.targetContext.getExternalFilesDir("verification"),filename).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
                    screenshot.recycle()
                }
                scenario.onActivity { editor.dismiss() }
            }
        }
    }
}
