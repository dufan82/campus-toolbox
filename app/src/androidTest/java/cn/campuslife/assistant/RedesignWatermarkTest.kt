package cn.campuslife.assistant

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.image.PhotoLoader
import cn.campuslife.assistant.ui.ProfileActivity
import cn.campuslife.assistant.ui.WatermarkActivity
import cn.campuslife.assistant.ui.WatermarkPreview
import cn.campuslife.watermark.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Offline synthetic photo only; run on the dedicated test emulator, not a user's phone. */
@RunWith(AndroidJUnit4::class)
class RedesignWatermarkTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var store: LocalStore
    private lateinit var originalConfig: EditorConfig
    private lateinit var originalProfile: CampusProfile
    private lateinit var originalDetails: ActivityDetails
    private var originalDraft: String? = null
    private lateinit var fixture: File

    @Before fun setUp() {
        store = LocalStore(context)
        originalConfig = store.config(); originalProfile = store.profile()
        originalDetails = store.details(); originalDraft = store.draftName()
        fixture = File(PhotoLoader(context).draftFolder, "redesign-offline-${System.nanoTime()}.png")
        Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.LTGRAY)
            fixture.outputStream().use { assertTrue(compress(Bitmap.CompressFormat.PNG, 100, it)) }
            recycle()
        }
        store.saveProfile(CampusProfile(mapOf(FieldKey.CLASS_NAME to "离线班级", FieldKey.NAME to "测试同学")))
        store.saveDetails(ActivityDetails())
        store.saveConfig(EditorConfig(enabled = setOf(FieldKey.CLASS_NAME, FieldKey.NAME)))
        store.saveDraftName(fixture.name)
    }

    @After fun tearDown() {
        if (::store.isInitialized && ::originalConfig.isInitialized) {
            store.saveConfig(originalConfig); store.saveProfile(originalProfile)
            store.saveDetails(originalDetails); store.saveDraftName(originalDraft)
        }
        if (::fixture.isInitialized) fixture.delete()
    }

    private fun awaitPhoto(scenario: ActivityScenario<WatermarkActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { ready = it.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview").photo != null }
            if (ready) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(50)
        }
        fail("The offline photo draft did not restore")
    }

    private fun tap(label: String) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            // On a 320dp display the existing bottom sheet scrolls; bring the real button into view.
            instrumentation.runOnMainSync {
                fun walk(v: View): List<View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap {walk(v.getChildAt(it))}else emptyList()
                android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::walk).filterIsInstance<Button>()
                    .firstOrNull {it.text.toString()==label && it.isEnabled}?.let {it.requestRectangleOnScreen(Rect(0,0,it.width,it.height),true)}
            }
            instrumentation.waitForIdleSync()
            val nodes = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label).orEmpty()
            val node = nodes.firstOrNull { it.isClickable && it.isVisibleToUser && it.isEnabled }
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                instrumentation.waitForIdleSync(); return
            }
            SystemClock.sleep(60)
        }
        fail("Cannot click $label")
    }

    @Test fun photoPrecedesCompactFieldsAndSaveShareStayAtBottom() {
        ActivityScenario.launch(WatermarkActivity::class.java).use { scenario ->
            awaitPhoto(scenario)
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val photo = Rect(); val fields = Rect(); val footer = Rect()
                assertTrue(root.findViewWithTag<View>("watermark_preview").getGlobalVisibleRect(photo))
                assertTrue(root.findViewWithTag<View>("watermark_content").getGlobalVisibleRect(fields))
                assertTrue(root.findViewWithTag<View>("watermark_export_actions").getGlobalVisibleRect(footer))
                assertTrue("The photo must be above its controls", photo.bottom <= fields.top)
                assertTrue("Save/share must not scroll with fields", fields.bottom <= footer.top)
                assertTrue(root.findViewWithTag<Button>("save_photo").isEnabled)
                assertTrue(root.findViewWithTag<Button>("share_photo").isEnabled)
                val picker = root.findViewWithTag<Button>("change_photo")
                assertNotNull(picker)
                assertTrue(picker.height >= (48 * context.resources.displayMetrics.density).toInt())
                assertNull("Unselected fields live in More fields", root.findViewWithTag<View>("visible_STUDENT_ID"))
            }
        }
    }

    @Test fun threeStyleTabsApplyWithoutHidingTheEntirePhotoAndSurviveRecreation() {
        ActivityScenario.launch(WatermarkActivity::class.java).use { scenario ->
            awaitPhoto(scenario)
            var photoTop = 0
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val bounds = Rect(); root.findViewWithTag<View>("watermark_preview").getGlobalVisibleRect(bounds)
                photoTop = bounds.top
                root.findViewWithTag<Button>("watermark_styles").performClick()
            }
            instrumentation.waitForIdleSync()
            val sheet = Rect()
            instrumentation.runOnMainSync {
                val decor=android.view.inspector.WindowInspector.getGlobalWindowViews().first {
                    it.findViewWithTag<View>("style_tab_text")!=null
                }
                val xy=IntArray(2);decor.getLocationOnScreen(xy)
                sheet.set(xy[0],xy[1],xy[0]+decor.width,xy[1]+decor.height)
            }
            assertTrue("Leave upper photo visible: sheet=$sheet photoTop=$photoTop", sheet.top > photoTop)
            tap("黑色")
            tap("排版")
            tap("横向排列")
            tap("左上")
            tap("背景")
            tap("透明")
            tap("完成")
            scenario.onActivity { activity ->
                val preview = activity.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview")
                assertEquals(Color.BLACK, preview.config.style.textColor)
                assertFalse(preview.config.style.multiline)
                assertEquals(0.0, preview.config.style.backgroundOpacity, 0.0)
                assertEquals(Position.TOP_LEFT, preview.config.style.position)
                preview.onMoved!!.invoke(.61, .29)
                assertEquals(Position.CUSTOM, preview.config.style.position)
            }
            scenario.recreate()
            awaitPhoto(scenario)
            scenario.onActivity { activity ->
                val style = activity.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview").config.style
                assertEquals(Color.BLACK, style.textColor)
                assertEquals(Position.CUSTOM, style.position)
                assertEquals(.61, style.anchorX, .001)
                assertEquals(.29, style.anchorY, .001)
                assertFalse(style.multiline)
            }
            assertTrue("Editing never replaces the photo draft", fixture.isFile)
            assertEquals(fixture.name, store.draftName())
        }
    }

    @Test fun moreFieldsOptInToStudentIdAndKeepSelectionOrder() {
        assertFalse(FieldKey.STUDENT_ID in EditorConfig().enabled)
        ActivityScenario.launch(WatermarkActivity::class.java).use { scenario ->
            awaitPhoto(scenario)
            scenario.onActivity { it.window.decorView.findViewWithTag<Button>("watermark_more_fields").performClick() }
            tap("水印包含学号")
            tap("水印包含拍摄时间")
            tap("完成")
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val config = root.findViewWithTag<WatermarkPreview>("watermark_preview").config
                assertEquals(listOf(FieldKey.CLASS_NAME, FieldKey.NAME, FieldKey.STUDENT_ID, FieldKey.CAPTURED_AT),
                    config.orderedFields().filter { it in config.enabled })
                assertTrue(root.findViewWithTag<CheckBox>("visible_STUDENT_ID").isChecked)
                assertTrue(root.findViewWithTag<CheckBox>("visible_CAPTURED_AT").isChecked)
            }
            scenario.recreate()
            awaitPhoto(scenario)
            scenario.onActivity {
                assertTrue(it.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview").config.enabled.contains(FieldKey.STUDENT_ID))
            }
        }
    }

    @Test fun profileFormKeepsUnsavedValuesAndLegacyDataWithStudentIdOptIn() {
        store.saveProfile(store.profile().copy(values = store.profile().values + (FieldKey.SCHOOL to "保留的旧字段")))
        ActivityScenario.launch(ProfileActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertFalse(root.findViewWithTag<CheckBox>("profile_visible_STUDENT_ID").isChecked)
                root.findViewWithTag<EditText>("profile_NAME").setText("离线编辑姓名")
                root.findViewWithTag<CheckBox>("profile_visible_STUDENT_ID").isChecked = true
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertEquals("离线编辑姓名", root.findViewWithTag<EditText>("profile_NAME").text.toString())
                assertTrue(root.findViewWithTag<CheckBox>("profile_visible_STUDENT_ID").isChecked)
                root.findViewWithTag<Button>("save_profile").performClick()
            }
        }
        assertEquals("离线编辑姓名", store.profile().values[FieldKey.NAME])
        assertEquals("保留的旧字段", store.profile().values[FieldKey.SCHOOL])
        assertTrue(FieldKey.STUDENT_ID in store.config().enabled)
    }
}
