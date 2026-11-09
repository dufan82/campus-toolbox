package cn.campuslife.assistant

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.image.PhotoLoader
import cn.campuslife.assistant.ui.WatermarkActivity
import cn.campuslife.assistant.ui.WatermarkPreview
import cn.campuslife.watermark.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WatermarkStyleFlowTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun tapText(text:String) {
        val deadline=SystemClock.uptimeMillis()+10000
        while(SystemClock.uptimeMillis()<deadline) {
            val node=instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.isClickable }
            if(node!=null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            SystemClock.sleep(100)
        }
        fail("Cannot click $text")
    }
    @Test fun styleChoicesApplyToPreviewAndPersist() {
        val context=instrumentation.targetContext; val store=LocalStore(context)
        val oldConfig=store.config();val oldProfile=store.profile();val oldDraft=store.draftName()
        val fixture=File(PhotoLoader(context).draftFolder,"style-offline-fixture.png")
        Bitmap.createBitmap(900,1800,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.LTGRAY);fixture.outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) };recycle()
        }
        store.saveProfile(CampusProfile(mapOf(FieldKey.NAME to "测试同学",FieldKey.CLASS_NAME to "测试班级")))
        store.saveConfig(EditorConfig(enabled=setOf(FieldKey.NAME,FieldKey.CLASS_NAME)))
        store.saveDraftName(fixture.name)
        try {
            ActivityScenario.launch(WatermarkActivity::class.java).use { scenario ->
                val deadline=SystemClock.uptimeMillis()+10000; var loaded=false
                while(!loaded && SystemClock.uptimeMillis()<deadline) {
                    scenario.onActivity { loaded=it.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview").photo!=null }
                    if(!loaded) SystemClock.sleep(40)
                }
                assertTrue(loaded)
                scenario.onActivity { it.window.decorView.findViewWithTag<Button>("watermark_styles").performClick() }
                tapText("文字颜色");tapText("黑色")
                tapText("排列方式");tapText("横向排列")
                tapText("背景");tapText("透明")
                tapText("完成")
                scenario.onActivity {
                    val preview=it.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview")
                    assertEquals(Color.BLACK,preview.config.style.textColor)
                    assertEquals(0.0,preview.config.style.backgroundOpacity,0.0)
                    assertFalse(preview.config.style.multiline)
                    assertTrue(preview.layout!!.lines.joinToString("") { line -> line.text }.contains(" · "))
                }
                SystemClock.sleep(800)
                assertEquals(Color.BLACK,store.config().style.textColor)
                assertEquals(0.0,store.config().style.backgroundOpacity,0.0)
                assertFalse(store.config().style.multiline)
            }
        } finally { store.saveConfig(oldConfig);store.saveProfile(oldProfile);store.saveDraftName(oldDraft);fixture.delete() }
    }
}
