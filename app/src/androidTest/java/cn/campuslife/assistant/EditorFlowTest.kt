package cn.campuslife.assistant

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
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
class EditorFlowTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    @Test fun realEditorPreviewsEditsSavesToGalleryAndRestoresAfterRecreate() {
        val store=LocalStore(context)
        val beforeProfile=store.profile(); val beforeDetails=store.details(); val beforeConfig=store.config(); val beforeDraft=store.draftName()
        val bitmap=Bitmap.createBitmap(4000,3000,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        canvas.drawColor(Color.rgb(103,148,167))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color=Color.rgb(178,203,159); canvas.drawRect(0f,1600f,4000f,3000f,paint)
        paint.color=Color.rgb(226,216,196); canvas.drawRect(500f,800f,3500f,2000f,paint)
        paint.color=Color.rgb(108,139,156)
        for(x in 0..5) for(y in 0..2) canvas.drawRect(700f+x*430,1000f+y*270,920f+x*430,1160f+y*270,paint)
        val file=File(context.cacheDir,"capture/flow-original.jpg").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) }; bitmap.recycle()
        val original=file.readBytes()
        val asset=PhotoLoader(context).loadFile(file)
        val copy=File(PhotoLoader(context).draftFolder,"flow-draft.jpg").apply { file.copyTo(this,overwrite=true) }
        asset.preview.recycle()
        store.saveDraftName(copy.name)
        store.saveProfile(CampusProfile(mapOf(FieldKey.COLLEGE to "测试学院",FieldKey.CLASS_NAME to "测试班级",FieldKey.NAME to "测试同学",FieldKey.STUDENT_ID to "2026000123456789")))
        store.saveDetails(ActivityDetails(mapOf(FieldKey.EVENT_NAME to "校园志愿服务 · 测试图片",FieldKey.CAPTURED_AT to "2026-10-21（测试）")))
        store.saveConfig(EditorConfig())
        var scenario: ActivityScenario<WatermarkActivity>?=null
        try {
            scenario=ActivityScenario.launch(WatermarkActivity::class.java)
            lateinit var editor: WatermarkActivity
            scenario.onActivity { editor=it }
            waitUntil { editor.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview").photo!=null }
            onMain {
                val input=editor.window.decorView.findViewWithTag<EditText>("detail_CAPTURED_AT")
                input.setText("2026-10-22（编辑验证）")
                val preview=editor.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview")
                assertTrue(preview.layout!!.lines.any { it.text=="2026-10-22（编辑验证）" })
                assertFalse(preview.layout!!.lines.any { "2026000123456789" in it.text })
            }
            screenshot("editor-content.png")
            // Cancellation must preserve both the imported file and the visible layout.
            onMain {
                val method=WatermarkActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
                method.isAccessible=true; method.invoke(editor,100,Activity.RESULT_CANCELED,null)
                assertNotNull(editor.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview").photo)
            }
            val countBefore=ownGalleryCount()
            onMain {
                val save=editor.window.decorView.findViewWithTag<Button>("save_photo")
                save.performClick(); save.performClick()
                val dialog=exportDialog(editor)
                val confirm=dialog.window!!.decorView.findViewWithTag<Button>("export_confirm")
                confirm.performClick();confirm.performClick()
            }
            waitUntil(20_000) { ownGalleryCount()>countBefore }
            assertEquals(countBefore+1,ownGalleryCount())
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),"${MediaStore.Images.Media.RELATIVE_PATH} = ?",arrayOf("Pictures/校园生活助手/"),"${MediaStore.Images.Media._ID} DESC")!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                val uri=android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cursor.getLong(0))
                val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                context.contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it,null,bounds) }
                assertEquals(4000,bounds.outWidth); assertEquals(3000,bounds.outHeight)
            }
            assertArrayEquals(original,file.readBytes())
            scenario.recreate()
            scenario.onActivity { editor=it }
            waitUntil { store.details().values[FieldKey.CAPTURED_AT]=="2026-10-22（编辑验证）" }
            waitUntil { editor.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview").photo!=null }
            onMain {
                val preview=editor.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview")
                assertEquals(4000,preview.photo!!.width); assertEquals(3000,preview.photo!!.height)
                assertTrue(preview.layout!!.lines.any { it.text=="2026-10-22（编辑验证）" })
            }
            screenshot("editor-restored.png")
        } finally {
            scenario?.close(); instrumentation.waitForIdleSync()
            store.saveProfile(beforeProfile); store.saveDetails(beforeDetails); store.saveConfig(beforeConfig); store.saveDraftName(beforeDraft)
        }
    }

    @Test fun allPrimaryScreensOpenWithoutAnImageOrAccount() {
        for((klass,name) in listOf(MainActivity::class.java to "home.png", ProfileActivity::class.java to "profile.png",
            cn.campuslife.assistant.express.ExpressActivity::class.java to "express.png")) {
            val activity=launch(klass)
            try { instrumentation.waitForIdleSync(); assertFalse(activity.isFinishing); screenshot(name) }
            finally { onMain { activity.finish() } }
        }
    }

    @Test fun largePhotoOffersAnExplicitSmallerOutputAndSavesIt() {
        org.junit.Assume.assumeTrue(Runtime.getRuntime().maxMemory()*.65<400_000_000)
        val store=LocalStore(context)
        val profile=store.profile(); val details=store.details(); val config=store.config(); val draft=store.draftName()
        val source=File(PhotoLoader(context).draftFolder,"large-48mp.jpg")
        val bitmap=Bitmap.createBitmap(8000,6000,Bitmap.Config.ARGB_8888).apply { eraseColor(0xff99bbdd.toInt()) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,90,it) }; bitmap.recycle()
        val original=source.readBytes()
        store.saveDraftName(source.name)
        store.saveProfile(CampusProfile()); store.saveDetails(ActivityDetails(mapOf(FieldKey.CAPTURED_AT to "2026-10-22（大图测试）")))
        store.saveConfig(EditorConfig(enabled=setOf(FieldKey.CAPTURED_AT)))
        var scenario: ActivityScenario<WatermarkActivity>?=null
        try {
            scenario=ActivityScenario.launch(WatermarkActivity::class.java)
            lateinit var activity: WatermarkActivity
            scenario.onActivity { activity=it }
            waitUntil { activity.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview").photo!=null }
            waitUntil { activity.window.decorView.findViewWithTag<Button>("save_photo").isEnabled }
            val before=ownGalleryCount()
            onMain {
                activity.window.decorView.findViewWithTag<Button>("save_photo").performClick()
                exportDialog(activity).window!!.decorView.findViewWithTag<Button>("export_confirm").performClick()
            }
            // A posted dialog can become visible after the activity itself is idle.
            // Wait for its actual row instead of inspecting a stale accessibility window.
            var node: android.view.accessibility.AccessibilityNodeInfo?=null
            val deadline=SystemClock.elapsedRealtime()+5000
            while(node==null && SystemClock.elapsedRealtime()<deadline) {
                node=instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("4000 × 3000")?.firstOrNull()
                if(node==null) SystemClock.sleep(50)
            }
            val windowText=mutableListOf<String>()
            fun collect(value: android.view.accessibility.AccessibilityNodeInfo?) {
                if(value==null) return
                value.text?.let { windowText.add(it.toString()) }
                for(index in 0 until value.childCount) collect(value.getChild(index))
            }
            collect(instrumentation.uiAutomation.rootInActiveWindow)
            assertNotNull("The output size chooser did not offer a fitting size; visible: $windowText",node)
            val bounds=android.graphics.Rect().also { node!!.getBoundsInScreen(it) }
            assertFalse(bounds.isEmpty)
            val now=SystemClock.uptimeMillis()
            val down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0)
            val up=android.view.MotionEvent.obtain(now,now+30,android.view.MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0)
            instrumentation.uiAutomation.injectInputEvent(down,true); instrumentation.uiAutomation.injectInputEvent(up,true)
            down.recycle(); up.recycle()
            waitUntil(20_000) { ownGalleryCount()>before }
            assertArrayEquals(original,source.readBytes())
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?",arrayOf("Pictures/校园生活助手/"),"${MediaStore.Images.Media._ID} DESC")!!.use {
                assertTrue(it.moveToFirst())
                val uri=android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,it.getLong(0))
                val result=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                context.contentResolver.openInputStream(uri).use { input-> android.graphics.BitmapFactory.decodeStream(input,null,result) }
                assertEquals(4000,result.outWidth); assertEquals(3000,result.outHeight)
            }
        } finally {
            scenario?.close(); instrumentation.waitForIdleSync()
            store.saveProfile(profile); store.saveDetails(details); store.saveConfig(config); store.saveDraftName(draft)
        }
    }

    private fun ownGalleryCount(): Int = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),"${MediaStore.Images.Media.RELATIVE_PATH} = ?",arrayOf("Pictures/校园生活助手/"),null)?.use { it.count } ?: 0

    private fun exportDialog(activity: WatermarkActivity)=WatermarkActivity::class.java.getDeclaredField("exportDialog").apply { isAccessible=true }.get(activity) as android.app.Dialog

    private fun <T: Activity> launch(klass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return instrumentation.startActivitySync(Intent(context,klass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as T
    }
    private fun onMain(block: ()->Unit) { instrumentation.runOnMainSync(block) }
    private fun waitUntil(timeout: Long=10_000, condition: ()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<until) {
            var success=false
            onMain { success=condition() }
            if(success) return
            SystemClock.sleep(50)
        }
        fail("Timed out waiting for the real editor operation")
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(150,4000)
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val destination=File(context.getExternalFilesDir(null),"verification/$name").apply { parentFile!!.mkdirs() }
        destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
}
