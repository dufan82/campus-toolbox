package cn.campuslife.assistant

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HomeLayoutMatrixTest {
    @Test fun expressTilesAreFullyVisibleAndTappableInBothOrientations() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        for(landscape in listOf(false,true)) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.requestedOrientation=if(landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                var ready=false;val until=android.os.SystemClock.uptimeMillis()+6000
                while(!ready && android.os.SystemClock.uptimeMillis()<until) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val expected=if(landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                        ready=activity.resources.configuration.orientation==expected && activity.window.decorView.findViewWithTag<View>("home_all_tools")!=null && activity.window.decorView.hasWindowFocus()
                    }
                    if(!ready) Thread.sleep(40)
                }
                assertTrue("Home layout must settle",ready)
                scenario.onActivity { activity ->
                    for(id in listOf("pdd_packages","pdd_code","douyin_orders")) {
                        val tile=activity.window.decorView.findViewWithTag<View>("shortcut_$id")
                        val rect=Rect();assertTrue(tile.getGlobalVisibleRect(rect))
                        assertEquals("Express tile $id must not be clipped",tile.height,rect.height())
                        assertEquals(tile.width,rect.width());assertTrue(tile.isClickable && tile.isEnabled)
                    }
                }
                if(InstrumentationRegistry.getArguments().getString("capture160")=="true") {
                    val screenshot=instrumentation.uiAutomation.takeScreenshot()
                    val folder=File(instrumentation.targetContext.getExternalFilesDir("verification"),"v160-layout").apply { mkdirs() }
                    val name=if(landscape) "home-landscape.png" else "home-portrait.png"
                    File(folder,name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) };screenshot.recycle()
                }
            }
        }
    }
}
