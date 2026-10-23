package cn.campuslife.assistant

import android.app.Dialog
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.image.PhotoLoader
import cn.campuslife.assistant.ui.*
import cn.campuslife.watermark.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExportDialogFlowTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun dialog(activity: WatermarkActivity)=WatermarkActivity::class.java.getDeclaredField("exportDialog").apply { isAccessible=true }.get(activity) as? Dialog
    private fun count()=context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.RELATIVE_PATH} = ?",arrayOf("Pictures/校园生活助手/"),null)?.use { it.count } ?: 0
    private fun waitUntil(timeout: Long=10000,predicate: ()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<deadline) { var done=false;instrumentation.runOnMainSync { done=predicate() };if(done)return;SystemClock.sleep(40) }
        fail("UI operation did not complete before timeout")
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        // Wait for native checkbox/checkmark transitions before capturing evidence.
        instrumentation.uiAutomation.waitForIdle(300,4000)
        if(name.contains("export")) {
            val deadline=SystemClock.elapsedRealtime()+5000
            while(instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("导出照片").isNullOrEmpty()) {
                check(SystemClock.elapsedRealtime()<deadline) { "Export dialog has not appeared in the real UI" }
                SystemClock.sleep(40)
            }
            instrumentation.uiAutomation.waitForIdle(250,4000)
        }
        val image=instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(context.filesDir,"verification").apply { mkdirs() }.resolve(name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        if(android.os.Build.VERSION.SDK_INT>=29) {
            val values=android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME,name.removeSuffix(".png")+"-${System.currentTimeMillis()}.png")
                put(MediaStore.Downloads.MIME_TYPE,"image/png")
                put(MediaStore.Downloads.RELATIVE_PATH,"Download/CampusLifeAssistantVerification")
                put(MediaStore.Downloads.IS_PENDING,1)
            }
            val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error("Cannot create test screenshot")
            context.contentResolver.openOutputStream(uri)!!.use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
            context.contentResolver.update(uri,android.content.ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)
        }
        image.recycle()
    }
    @Test fun namingCancelRotationAndPngSaveUseTheRealEditorWithoutOverwriting() {
        val store=LocalStore(context)
        val previousProfile=store.profile();val previousDetails=store.details();val previousConfig=store.config();val previousDraft=store.draftName();val previousNaming=store.namingFields()
        val file=File(PhotoLoader(context).draftFolder,"naming-flow-sample.jpg")
        instrumentation.context.assets.open("campus-sample.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
        val original=file.readBytes()
        store.saveProfile(CampusProfile(mapOf(FieldKey.COLLEGE to "示例学院",FieldKey.CLASS_NAME to "示例班级",FieldKey.NAME to "示例同学",FieldKey.SEAT_NUMBER to "05",FieldKey.STUDENT_ID to "IDTEST001")))
        store.saveDetails(ActivityDetails(mapOf(FieldKey.CAPTURED_AT to "2026-10-22")))
        store.saveConfig(EditorConfig());store.saveDraftName(file.name);store.saveNamingFields(ExportNaming.defaultFields)
        var scenario: ActivityScenario<WatermarkActivity>?=null
        try {
            scenario=ActivityScenario.launch(WatermarkActivity::class.java)
            lateinit var activity: WatermarkActivity
            scenario.onActivity { activity=it }
            waitUntil { activity.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview").photo!=null }
            scenario.onActivity {
                val preview=it.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview")
                assertEquals(Position.BOTTOM_RIGHT,preview.config.style.position)
                assertEquals(listOf("示例学院","示例班级","示例同学","05","2026-10-22"),preview.layout!!.lines.map { line->line.text })
                assertEquals(900.0-600*.028,preview.layout!!.box.right,.01)
            }
            screenshot("v1.3.1-editor.png")
            scenario.onActivity {
                val toggle=it.window.decorView.findViewWithTag<CheckBox>("content_only")
                toggle.performClick();assertFalse(toggle.isChecked)
                val preview=it.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview")
                assertEquals(listOf("学院：示例学院","班级：示例班级","姓名：示例同学","座号：05","拍摄时间：2026-10-22"),preview.layout!!.lines.map { line->line.text })
            }
            screenshot("v1.3.1-labels.png")
            val before=count()
            scenario.onActivity {
                it.window.decorView.findViewWithTag<Button>("save_photo").performClick()
                val root=dialog(it)!!.window!!.decorView
                assertEquals("示例班级_示例同学_05",root.findViewWithTag<EditText>("export_filename").text.toString())
                assertFalse(root.findViewWithTag<CheckBox>("naming_STUDENT_ID").isChecked)
            }
            screenshot("v1.3.1-export.png")
            scenario.onActivity {
                val root=dialog(it)!!.window!!.decorView
                root.findViewWithTag<EditText>("export_filename").setText("手动文件名")
                root.findViewWithTag<Button>("export_format_PNG").performClick()
            }
            scenario.recreate();scenario.onActivity { activity=it }
            waitUntil { dialog(activity)?.isShowing==true }
            scenario.onActivity {
                val root=dialog(it)!!.window!!.decorView
                assertEquals("手动文件名",root.findViewWithTag<EditText>("export_filename").text.toString())
                assertEquals(".png",root.findViewWithTag<TextView>("export_suffix").text.toString())
                dialog(it)!!.dismiss()
            }
            assertEquals(before,count())
            scenario.onActivity {
                it.window.decorView.findViewWithTag<Button>("save_photo").performClick()
                val root=dialog(it)!!.window!!.decorView
                root.findViewWithTag<Button>("export_regenerate").performClick()
                assertEquals("示例班级_示例同学_05",root.findViewWithTag<EditText>("export_filename").text.toString())
                root.findViewWithTag<CheckBox>("naming_NAME").performClick()
                assertEquals("示例班级_05",root.findViewWithTag<EditText>("export_filename").text.toString())
                root.findViewWithTag<EditText>("export_filename").setText("bad/name")
                assertFalse(root.findViewWithTag<Button>("export_confirm").isEnabled)
                root.findViewWithTag<EditText>("export_filename").setText("界面测试照片")
                root.findViewWithTag<Button>("export_confirm").performClick()
            }
            waitUntil(20000) { count()==before+1 && activity.window.decorView.findViewWithTag<Button>("save_photo").isEnabled }
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.MIME_TYPE),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?",arrayOf("Pictures/校园生活助手/"),"${MediaStore.Images.Media._ID} DESC")!!.use {
                assertTrue(it.moveToFirst());assertTrue(it.getString(0).startsWith("界面测试照片"));assertTrue(it.getString(0).endsWith(".png"));assertEquals("image/png",it.getString(1))
            }
            assertArrayEquals(original,file.readBytes())
        } finally {
            scenario?.close();instrumentation.waitForIdleSync()
            store.saveProfile(previousProfile);store.saveDetails(previousDetails);store.saveConfig(previousConfig);store.saveDraftName(previousDraft);store.saveNamingFields(previousNaming)
        }
    }
}
