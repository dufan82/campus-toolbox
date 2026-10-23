package cn.campuslife.assistant

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.view.View
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.express.*
import cn.campuslife.assistant.ui.*
import cn.campuslife.watermark.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimplifiedEditorTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    @Test fun optionsAreChipsAboveThePreviewWithoutTemplatePages() {
        ActivityScenario.launch(WatermarkActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root=activity.window.decorView
                val preview=root.findViewWithTag<WatermarkPreview>("watermark_preview")
                val bounds=Rect();assertTrue(preview.getGlobalVisibleRect(bounds))
                for(key in listOf("COLLEGE","CLASS_NAME","NAME","SEAT_NUMBER","STUDENT_ID","CAPTURED_AT")) {
                    val chip=root.findViewWithTag<CheckBox>("visible_$key")
                    assertNotNull(chip)
                    assertNull("Fields must use the same chip control as export",chip.buttonDrawable)
                    val chipBounds=Rect();assertTrue(chip.getGlobalVisibleRect(chipBounds))
                    assertTrue("All field options belong above the image",chipBounds.bottom<=bounds.top)
                }
                for(index in 0..3) assertNull(root.findViewWithTag<View>("tab_$index"))
            }
        }
    }

    @Test fun tappingFieldsControlsPrintOrderAndPersistsWithoutChangingNaming() {
        val store=LocalStore(context)
        val before=store.config();val profile=store.profile();val naming=store.namingFields()
        store.saveProfile(CampusProfile(mapOf(FieldKey.CLASS_NAME to "测试班级",FieldKey.NAME to "测试同学",FieldKey.SEAT_NUMBER to "05")))
        store.saveConfig(EditorConfig(enabled=emptySet()))
        try {
            ActivityScenario.launch(WatermarkActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val root=activity.window.decorView
                    for(key in listOf("NAME","SEAT_NUMBER","CLASS_NAME")) root.findViewWithTag<CheckBox>("visible_$key").performClick()
                    val preview=root.findViewWithTag<WatermarkPreview>("watermark_preview")
                    assertEquals(listOf("测试同学","05","测试班级"),formattedFields(store.profile(),store.details(),preview.config))
                    root.findViewWithTag<CheckBox>("visible_SEAT_NUMBER").performClick()
                    root.findViewWithTag<CheckBox>("visible_SEAT_NUMBER").performClick()
                    assertEquals(listOf(FieldKey.NAME,FieldKey.CLASS_NAME,FieldKey.SEAT_NUMBER),preview.config.orderedFields().filter { it in preview.config.enabled })
                    assertTrue(root.findViewWithTag<CheckBox>("visible_SEAT_NUMBER").text.startsWith("3"))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val preview=activity.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview")
                    assertEquals(listOf(FieldKey.NAME,FieldKey.CLASS_NAME,FieldKey.SEAT_NUMBER),preview.config.orderedFields().filter { it in preview.config.enabled })
                }
            }
            assertEquals(naming,store.namingFields())
            assertEquals(setOf(FieldKey.NAME,FieldKey.CLASS_NAME,FieldKey.SEAT_NUMBER),store.config().enabled)
        } finally { store.saveConfig(before);store.saveProfile(profile) }
    }

    @Test fun successfulShortcutHasNoTransientReminder() {
        // Intercept the external activity only; exercise the actual routing and result.
        val filter=IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE);addDataScheme("pinduoduo");addDataScheme("https")
        }
        val monitor=instrumentation.addMonitor(filter,Instrumentation.ActivityResult(Activity.RESULT_OK,null),true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val result=ExpressLauncher(activity,ExpressConfiguration(ExpressPreferences(activity))).launch("pdd_code")
                    assertNotNull(result.candidate)
                    assertEquals(RouteKind.LINK,result.candidate!!.kind)
                    assertEquals("Successful launch must be silent", "",result.message)
                }
            }
            assertTrue("The external intent was safely intercepted",monitor.hits>0)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun landscapeKeepsThePreviewAndSaveActionUsable() {
        ActivityScenario.launch(WatermarkActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            val deadline=android.os.SystemClock.elapsedRealtime()+5000
            var landscape=false
            while(!landscape && android.os.SystemClock.elapsedRealtime()<deadline) {
                scenario.onActivity { landscape=it.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE }
                if(!landscape) android.os.SystemClock.sleep(40)
            }
            assertTrue("Rotation did not complete",landscape)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val preview=activity.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview")
                val bounds=Rect()
                assertTrue("Landscape must not squeeze away the image",preview.getGlobalVisibleRect(bounds))
                assertTrue("Keep enough image area to move a watermark",bounds.height()>=Ui.dp(activity,64))
                assertTrue(activity.window.decorView.findViewWithTag<View>("save_photo").getGlobalVisibleRect(Rect()))
            }
        }
    }
}
