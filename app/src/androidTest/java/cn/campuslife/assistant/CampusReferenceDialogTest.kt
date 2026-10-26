package cn.campuslife.assistant

import android.graphics.BitmapFactory
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.map.CampusReferenceDialog
import cn.campuslife.assistant.ui.MainActivity
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class CampusReferenceDialogTest {
    @Test fun referenceSelectionSurvivesMapActivityRecreationWithoutStartingSdk() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=context.getSharedPreferences("campus_map",0)
        val before=preferences.getBoolean("privacy_consent_v1",false)
        preferences.edit().putBoolean("privacy_consent_v1",false).commit()
        try {
            ActivityScenario.launch<Activity>(Intent().setClassName(context,"cn.campuslife.assistant.map.CampusMapActivity")).use { scenario ->
                scenario.onActivity { activity ->
                    activity.javaClass.getDeclaredMethod("showReference",String::class.java).apply { isAccessible=true }.invoke(activity,"dorm-2")
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val dialog=activity.javaClass.getDeclaredField("referenceDialog").apply { isAccessible=true }.get(activity) as CampusReferenceDialog
                    assertTrue(dialog.isShowing)
                    assertEquals("dorm-2",dialog.selectedId)
                    assertFalse(preferences.getBoolean("privacy_consent_v1",false))
                    dialog.dismiss()
                }
            }
        } finally { preferences.edit().putBoolean("privacy_consent_v1",before).commit() }
    }

    @Test fun referenceControlsKeepUsableBoundsInBothOrientations() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        for(orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.requestedOrientation=orientation }
                val expected=if(orientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
                var oriented=false
                val rotationDeadline=SystemClock.uptimeMillis()+5000
                while(!oriented && SystemClock.uptimeMillis()<rotationDeadline) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val root=activity.window.decorView
                        oriented=activity.resources.configuration.orientation==expected && root.isLaidOut &&
                            root.hasWindowFocus() && if(expected==android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                            root.width>root.height else root.height>root.width
                    }
                    if(!oriented) SystemClock.sleep(40)
                }
                assertTrue("Activity rotation did not finish",oriented)
                var dialog: CampusReferenceDialog?=null
                scenario.onActivity { activity -> dialog=CampusReferenceDialog(activity,"dorm-2",true) {};dialog!!.show() }
                var laidOut=false
                val layoutDeadline=SystemClock.uptimeMillis()+5000
                while(!laidOut && SystemClock.uptimeMillis()<layoutDeadline) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        val root=dialog!!.window!!.decorView
                        laidOut=dialog!!.isShowing && root.isLaidOut && root.findViewWithTag<Button>("ref_close").height>0
                    }
                    if(!laidOut) SystemClock.sleep(40)
                }
                assertTrue("Reference dialog has not completed layout",laidOut)
                if(InstrumentationRegistry.getArguments().getString("captureReferenceVisuals")=="true") {
                    var loaded=false
                    val deadline=SystemClock.uptimeMillis()+5000
                    while(!loaded && SystemClock.uptimeMillis()<deadline) {
                        scenario.onActivity { loaded=dialog!!.window!!.decorView.findViewWithTag<View>("ref_canvas").contentDescription.contains("原图已载入") }
                        if(!loaded) SystemClock.sleep(40)
                    }
                    assertTrue("Reference bitmap did not load",loaded)
                    // Bitmap assignment precedes the next rendered frame; capture only after UI idle.
                    instrumentation.waitForIdleSync()
                    instrumentation.uiAutomation.waitForIdle(150,5000)
                    val name=if(orientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) "reference-landscape.png" else "reference-portrait.png"
                    val screenshot=instrumentation.uiAutomation.takeScreenshot()
                    File(instrumentation.targetContext.getExternalFilesDir("verification"),name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
                    screenshot.recycle()
                }
                scenario.onActivity { activity ->
                    val root=dialog!!.window!!.decorView
                    for(tag in listOf("ref_close","ref_choose","ref_zoom_0","ref_zoom_1","ref_zoom_2","ref_mark")) {
                        val button=root.findViewWithTag<Button>(tag)
                        assertTrue("$tag has no usable height",button.height>=48*activity.resources.displayMetrics.density)
                        assertTrue("$tag clips text",button.layout.height<=button.height-button.compoundPaddingTop-button.compoundPaddingBottom)
                    }
                    assertTrue(root.findViewWithTag<View>("ref_canvas").height>0)
                    dialog!!.dismiss()
                }
            }
        }
    }

    @Test fun selectedDormIsHighlightedAndReturningToAmapKeepsItsId() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            var marked: String?=null
            val dialog=CampusReferenceDialog(activity,"dorm-2",true) { marked=it }
            dialog.show()
            try {
                val root=dialog.window!!.decorView
                assertTrue(root.findViewWithTag<TextView>("ref_selection").text.contains("2号楼"))
                assertNotNull(root.findViewWithTag<View>("ref_canvas"))
                root.findViewWithTag<Button>("ref_mark").performClick()
                assertEquals("dorm-2",marked)
                assertFalse(dialog.isShowing)
                assertFalse(activity.isFinishing)
            } finally { dialog.dismiss() }
        } }
    }

    @Test fun missingDiagramAnchorIsExplicitAndClosingDoesNotExitTheMap() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val dialog=CampusReferenceDialog(activity,"sunken-plaza",false) {}
            dialog.show()
            try {
                val root=dialog.window!!.decorView
                assertTrue(root.findViewWithTag<TextView>("ref_selection").text.contains("原图暂未关联"))
                assertTrue(root.findViewWithTag<TextView>("ref_notice").text.contains("不是实时定位"))
                root.findViewWithTag<Button>("ref_close").performClick()
                assertFalse(dialog.isShowing)
                assertFalse(activity.isFinishing)
            } finally { dialog.dismiss() }
        } }
    }

    @Test fun originalHighResolutionReferenceIsBundledWithoutChangingDimensions() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Campus reference image is an optional local asset, not included in the public repository.",
            context.assets.list("").orEmpty().contains("pingtan-reference.png"))
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        context.assets.open("pingtan-reference.png").use { BitmapFactory.decodeStream(it,null,options) }
        assertEquals(4822,options.outWidth)
        assertEquals(3470,options.outHeight)
    }
}
