package cn.campuslife.assistant

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.exifinterface.media.ExifInterface
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
class ReviewRegressionTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    @Test fun fieldEditDoesNotUndoDraggedPosition() = editor { activity ->
        val preview=activity.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview")
        preview.onMoved!!.invoke(.75,.2)
        activity.window.decorView.findViewWithTag<CheckBox>("visible_NAME").performClick()
        assertEquals(.75,preview.config.style.anchorX,0.001)
        assertEquals(.2,preview.config.style.anchorY,0.001)
    }

    @Test fun reorderedFieldsDoNotUndoDraggedPosition() = editor { activity ->
        val preview=activity.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview")
        preview.onMoved!!.invoke(.35,.7)
        val chip=activity.window.decorView.findViewWithTag<CheckBox>("visible_CLASS_NAME")
        chip.performClick();chip.performClick()
        assertEquals(.35,preview.config.style.anchorX,0.001)
        assertEquals(.7,preview.config.style.anchorY,0.001)
    }

    @Test fun unsavedProfileAndVisibilitySurviveRecreation() {
        ActivityScenario.launch(ProfileActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.window.decorView.findViewWithTag<EditText>("profile_NAME").setText("尚未保存的姓名")
                it.window.decorView.findViewWithTag<CheckBox>("profile_visible_STUDENT_ID").isChecked=true
            }
            scenario.recreate()
            scenario.onActivity {
                assertEquals("尚未保存的姓名",it.window.decorView.findViewWithTag<EditText>("profile_NAME").text.toString())
                assertTrue(it.window.decorView.findViewWithTag<CheckBox>("profile_visible_STUDENT_ID").isChecked)
            }
        }
    }

    @Test fun modifiedTimeIsNotAutomaticallyClaimedAsCaptureTime() {
        val file=File(context.cacheDir,"capture/modified-only.jpg").apply { parentFile!!.mkdirs() }
        val bitmap=Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) }; bitmap.recycle()
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_DATETIME,"2026:10:21 12:00:00"); saveAttributes() }
        val asset=PhotoLoader(context).loadFile(file)
        try { assertNull(asset.capturedAt) } finally { asset.preview.recycle() }
    }

    @Test fun returnedPickerPhotoQueuesBehindWorkAndSurvivesRecreation() {
        val store=LocalStore(context); val previous=store.draftName()
        store.saveDraftName(null)
        val source=File(context.cacheDir,"capture/returned-picker.jpg").apply { parentFile!!.mkdirs() }
        val bitmap=Bitmap.createBitmap(973,641,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) }; bitmap.recycle()
        val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",source)
        val gate=java.util.concurrent.CountDownLatch(1)
        var deliveredTo: WatermarkActivity?=null
        val scenario=ActivityScenario.launch(WatermarkActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val field=WatermarkActivity::class.java.getDeclaredField("session").apply { isAccessible=true }
                val session=field.get(activity) as EditorSession
                assertTrue(session.work.submit("slow existing operation",{ gate.await(10,java.util.concurrent.TimeUnit.SECONDS) },{ deliveredTo=this }))
                val result=WatermarkActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,android.content.Intent::class.java).apply { isAccessible=true }
                result.invoke(activity,100,android.app.Activity.RESULT_OK,android.content.Intent().setData(uri))
            }
            scenario.recreate()
            lateinit var recreated: WatermarkActivity
            scenario.onActivity { recreated=it; gate.countDown() }
            val deadline=android.os.SystemClock.elapsedRealtime()+10_000
            var ready=false
            while(android.os.SystemClock.elapsedRealtime()<deadline && !ready) {
                scenario.onActivity { ready=it.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview").photo?.rawWidth==973 }
                if(!ready) android.os.SystemClock.sleep(30)
            }
            assertTrue("Returned picker result was not delivered",ready)
            assertSame(recreated,deliveredTo)
        } finally { gate.countDown(); scenario.close(); store.saveDraftName(previous) }
    }

    @Test fun editingFieldRemainsVisibleAboveTheRealSoftKeyboard() {
        val previous=android.provider.Settings.Secure.getString(context.contentResolver,"show_ime_with_hard_keyboard")
        instrumentation.uiAutomation.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1").close()
        try {
            ActivityScenario.launch(WatermarkActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                lateinit var input: EditText
                lateinit var activity: WatermarkActivity
                scenario.onActivity {
                    activity=it
                    input=it.window.decorView.findViewWithTag("detail_CAPTURED_AT")
                    input.requestFocus()
                    (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                val deadline=android.os.SystemClock.elapsedRealtime()+5000
                var keyboard=false
                while(!keyboard && android.os.SystemClock.elapsedRealtime()<deadline) {
                    instrumentation.runOnMainSync {
                        keyboard=androidx.core.view.ViewCompat.getRootWindowInsets(input)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
                    }
                    if(!keyboard) android.os.SystemClock.sleep(50)
                }
                assertTrue("The real soft keyboard must be open for this regression",keyboard)
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val visible=android.graphics.Rect()
                    assertTrue("Keyboard squeezed the active field out of the editor",
                        input.getGlobalVisibleRect(visible) && visible.height()>=Ui.dp(it,40))
                }
                val image=instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
                val screenshot=File(context.getExternalFilesDir(null),"verification/keyboard-edit.png").apply { parentFile!!.mkdirs() }
                screenshot.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
                scenario.onActivity {
                    (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(input.windowToken,0)
                }
                val hiddenDeadline=android.os.SystemClock.elapsedRealtime()+5000
                while(keyboard && android.os.SystemClock.elapsedRealtime()<hiddenDeadline) {
                    instrumentation.runOnMainSync {
                        keyboard=androidx.core.view.ViewCompat.getRootWindowInsets(input)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true
                    }
                    if(keyboard) android.os.SystemClock.sleep(50)
                }
                assertFalse("Keyboard did not close",keyboard)
                scenario.onActivity {
                    val preview=activity.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview")
                    assertTrue("Preview must return after typing",preview.isShown && preview.height>0)
                }
            }
        } finally {
            val command=if(previous==null) "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $previous"
            instrumentation.uiAutomation.executeShellCommand(command).close()
        }
    }

    private fun editor(test: (WatermarkActivity)->Unit) {
        ActivityScenario.launch(WatermarkActivity::class.java).use { scenario -> scenario.onActivity(test) }
    }
    private fun find(view: View,predicate: (View)->Boolean): View {
        if(predicate(view)) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) {
            try { return find(view.getChildAt(index),predicate) } catch(_: NoSuchElementException) {}
        }
        throw NoSuchElementException("Expected control was not present")
    }
}
