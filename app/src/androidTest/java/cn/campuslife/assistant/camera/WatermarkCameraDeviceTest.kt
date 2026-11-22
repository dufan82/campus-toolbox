package cn.campuslife.assistant.camera

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.testing.DeviceTestUi
import cn.campuslife.assistant.ui.Ui
import cn.campuslife.assistant.ui.WatermarkActivity
import cn.campuslife.watermark.CampusProfile
import cn.campuslife.watermark.FieldKey
import cn.campuslife.watermark.Position
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses only emulated cameras, fictional fields, and app-owned test MediaStore rows. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class WatermarkCameraDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private val profilePrefs get() = context.getSharedPreferences("campus_local_v1", Context.MODE_PRIVATE)
    private val cameraPrefs get() = context.getSharedPreferences("watermark_camera_v1", Context.MODE_PRIVATE)
    private var oldProfile: String? = null
    private lateinit var oldChoices: CameraSettings
    private lateinit var prefKeys: Set<String>
    private val generated = mutableListOf<Uri>()

    @Before fun setup() {
        assertTrue("The test host must grant CAMERA on the dedicated emulator first", allowed(Manifest.permission.CAMERA))
        oldProfile = profilePrefs.getString("profile", null)
        oldChoices = CameraSettingsStore(context).read()
        prefKeys = cameraPrefs.all.keys.toSet()
        LocalStore(context).saveProfile(CampusProfile(mapOf(
            FieldKey.NAME to "示例同学", FieldKey.CLASS_NAME to "数字媒体示例班", FieldKey.STUDENT_ID to "TEST-123",
            FieldKey.COLLEGE to "保留旧学院字段"
        )))
        CameraSettingsStore(context).save(CameraSettings())
    }

    @After fun restore() {
        generated.forEach { context.contentResolver.delete(it, null, null) }
        if (!::oldChoices.isInitialized) return
        profilePrefs.edit().putString("profile", oldProfile).commit()
        CameraSettingsStore(context).save(oldChoices)
        cameraPrefs.edit().apply { (cameraPrefs.all.keys - prefKeys).forEach { remove(it) } }.commit()
    }

    @Test fun realCameraPreviewSettingsOriginalResolutionAndSave() {
        val before = mediaIds()
        ActivityScenario.launch(WatermarkCameraActivity::class.java).use { scenario ->
            waitFor("rear camera ready") { visibleAndEnabled(scenario, "camera_shutter") }
            assertTrue(read(scenario, "camera_stamp").contains("示例同学"))
            assertFalse(read(scenario, "camera_stamp").contains("TEST-123"))
            scenario.onActivity {
                val root = it.window.decorView
                val surface = walk(root).filterIsInstance<SurfaceView>().single()
                val stamp = root.findViewWithTag<View>("camera_stamp")
                assertTrue(surface.holder.surface.isValid)
                assertEquals(surface.width, stamp.width)
                assertEquals(surface.height, stamp.height)
                assertTrue(surface.width >= 200 && surface.height >= 200)
            }
            click(scenario, "camera_location")
            dialogButton("手动填写")
            DeviceTestUi.main { dialogView<EditText>("camera_manual_location").setText("实训楼（示例）") }
            dialogButton("完成")
            assertTrue(read(scenario, "camera_stamp").contains("手填地点 · 实训楼（示例）"))
            waitFor("manual location dialog closed") { visibleAndEnabled(scenario, "camera_shutter") }
            shot("01-live")
            click(scenario, "camera_settings")
            waitFor("settings opened") { DeviceTestUi.main { globalViews().any { it.tag == "camera_field_STUDENT_ID" } } }
            DeviceTestUi.main { assertTrue(dialogView<Button>("camera_settings_done").getGlobalVisibleRect(Rect())) }
            shot("02-settings")
            DeviceTestUi.main {
                assertFalse(dialogView<CheckBox>("camera_field_STUDENT_ID").isChecked)
                dialogView<CheckBox>("camera_field_STUDENT_ID").isChecked = true
                dialogView<CheckBox>("camera_field_CLASS").isChecked = false
                dialogView<EditText>("camera_profile_NAME").setText("示例记录者")
                dialogView<EditText>("camera_note").setText("社团活动记录")
            }
            clickDialogTag("camera_settings_done")
            assertEquals("保留旧学院字段", LocalStore(context).profile().values[FieldKey.COLLEGE])
            val stamp = read(scenario, "camera_stamp")
            assertTrue(stamp.contains("示例记录者") && stamp.contains("TEST-123") && stamp.contains("社团活动记录"))
            assertFalse(stamp.contains("数字媒体示例班"))
            click(scenario, "camera_shutter")
            waitFor("captured image") { visibleAndEnabled(scenario, "camera_save") }
            assertEquals(before, mediaIds())
            val dims = largestBackPhoto()
            assertTrue(read(scenario, "camera_detail").contains("${dims.second} × ${dims.first} px"))
            scenario.recreate()
            waitFor("captured review survived recreation") { visibleAndEnabled(scenario, "camera_save") }
            shot("03-review")
            click(scenario, "camera_save")
            waitFor("explicit save receipt") { read(scenario, "camera_save") == "已保存" }
            val newIds = mediaIds() - before
            assertEquals(1, newIds.size)
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newIds.single())
            generated.add(uri)
            context.contentResolver.openInputStream(uri)!!.use { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                assertEquals(dims.second, bounds.outWidth)
                assertEquals(dims.first, bounds.outHeight)
            }
            context.contentResolver.openInputStream(uri)!!.use { stream ->
                assertNull("No hidden GPS metadata", ExifInterface(stream).latLong)
            }
            assertFalse(visibleAndEnabled(scenario, "camera_save"))
            shot("04-saved")
            click(scenario, "camera_retake")
            waitFor("camera released in review and returned for retake") { visibleAndEnabled(scenario, "camera_shutter") }
        }
    }

    @Test fun frontCameraCanCaptureAndLandscapeDoesNotStretchStamp() {
        val before = mediaIds()
        ActivityScenario.launch(WatermarkCameraActivity::class.java).use { scenario ->
            waitFor("rear light control") { visibleAndEnabled(scenario, "camera_light") }
            click(scenario, "camera_light")
            scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<Button>("camera_light").isSelected) }
            click(scenario, "camera_light")
            scenario.onActivity { assertFalse(it.window.decorView.findViewWithTag<Button>("camera_light").isSelected) }
            waitFor("two emulated cameras") { visibleAndEnabled(scenario, "camera_flip") }
            click(scenario, "camera_flip")
            waitFor("front camera ready") { visibleAndEnabled(scenario, "camera_shutter") }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor("landscape preview") {
                var ok = false
                scenario.onActivity {
                    val frame = it.window.decorView.findViewWithTag<View>("camera_stamp")
                    ok = frame.width > frame.height && it.window.decorView.findViewWithTag<View>("camera_shutter").isEnabled
                }
                ok
            }
            click(scenario, "camera_shutter")
            waitFor("front-camera result") { visibleAndEnabled(scenario, "camera_save") }
            val dims = Regex("""(\d+) × (\d+) px""").find(read(scenario, "camera_detail"))!!
            assertTrue(dims.groupValues[1].toInt() > dims.groupValues[2].toInt())
            assertEquals(before, mediaIds())
        }
    }

    @Test fun noLocationPermissionDoesNotBlockTakingPhotos() {
        assertFalse("Test the denied branch without injecting real location", allowed(Manifest.permission.ACCESS_COARSE_LOCATION))
        ActivityScenario.launch(WatermarkCameraActivity::class.java).use { scenario ->
            waitFor("ready without location permission") { visibleAndEnabled(scenario, "camera_shutter") }
            click(scenario, "camera_location")
            dialogButton("自动定位")
            waitFor("system location dialog denied") {
                val root = inst.uiAutomation.rootInActiveWindow ?: return@waitFor false
                val deny = root.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_deny_button")
                    .firstOrNull()
                deny?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            waitFor("back to capture after denial") {
                visibleAndEnabled(scenario, "camera_shutter") && read(scenario, "camera_location") == "定位未授权"
            }
            assertFalse(read(scenario, "camera_stamp").contains("自动定位"))
            click(scenario, "camera_shutter")
            waitFor("success without location") { visibleAndEnabled(scenario, "camera_save") }
        }
    }

    @Test fun dragWatermarkChangesAndPersistsRelativePosition() {
        ActivityScenario.launch(WatermarkCameraActivity::class.java).use { scenario ->
            waitFor("viewfinder focused") { visibleAndEnabled(scenario, "camera_shutter") }
            scenario.onActivity { activity ->
                val overlay = activity.window.decorView.findViewWithTag<View>("camera_stamp")
                val now = SystemClock.uptimeMillis()
                fun move(action: Int, x: Float, y: Float) {
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x*overlay.width, y*overlay.height, 0)
                    try { assertTrue(overlay.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                move(MotionEvent.ACTION_DOWN, .13f, .91f)
                move(MotionEvent.ACTION_MOVE, .52f, .35f)
                move(MotionEvent.ACTION_UP, .52f, .35f)
            }
            val options = CameraSettingsStore(context).read()
            assertEquals(Position.CUSTOM, options.position)
            assertTrue(options.anchorX > .5 && options.anchorY < .5)
        }
    }

    @Test fun stampAnchorsAndAccentUseIdenticalPhotoCoordinates() {
        val stamp = CameraSettings(note = "同一排版").stamp(java.time.ZonedDateTime.parse("2026-11-20T12:19:00+08:00"),
            CampusProfile(), null)
        val frame = CameraSize(1200, 1600)
        val drawing = CameraStampDrawing(frame, stamp)
        val bottom = drawing.bounds(stamp)
        val top = drawing.bounds(stamp.copy(position = Position.TOP_RIGHT))
        assertTrue(top.top < bottom.top)
        assertTrue(top.left > bottom.left)
        val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply { scale(.5f,.5f); drawing.draw(this, stamp) }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            assertTrue("School-brand accent drawn", pixels.count { it == Ui.teal } > 10)
            assertNotEquals(0, bitmap.getPixel((bottom.left / 2).toInt()+10, (bottom.bottom / 2).toInt()-10))
            assertEquals(0, bitmap.getPixel((top.right / 2).toInt()-10, (top.top / 2).toInt()+10))
        } finally { bitmap.recycle() }
    }

    @Test fun existingEditorOpensNewModeAndAlbumStillAvailable() {
        ActivityScenario.launch(WatermarkActivity::class.java).use { scenario ->
            waitFor("existing photo editor available") { visibleAndEnabled(scenario, "change_photo") }
            val monitor = inst.addMonitor(WatermarkCameraActivity::class.java.name, null, false)
            try {
                click(scenario, "open_watermark_camera")
                val camera = inst.waitForMonitorWithTimeout(monitor, 8_000) as? WatermarkCameraActivity
                    ?: throw AssertionError("Camera entry didn't open")
                DeviceTestUi.main { camera.window.decorView.findViewWithTag<Button>("camera_album").performClick() }
                waitFor("system photo picker from camera album") {
                    val root = inst.uiAutomation.rootInActiveWindow
                    root != null && root.packageName.toString() != context.packageName &&
                        root.packageName.toString() != "com.android.systemui"
                }
                inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            } finally { inst.removeMonitor(monitor) }
        }
    }

    private fun allowed(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun largestBackPhoto(): Pair<Int, Int> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val back = manager.cameraIdList.map(manager::getCameraCharacteristics)
            .first { it[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }
        val largest = back[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!.getOutputSizes(ImageFormat.JPEG)
            .maxBy { it.width.toLong() * it.height }
        return largest.width to largest.height
    }

    private fun mediaIds(): Set<Long> = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME), null, null, null)!!.use { cursor ->
        buildSet {
            while (cursor.moveToNext()) if (cursor.getString(1).startsWith("水印相机_")) add(cursor.getLong(0))
        }
    }

    private fun shot(name: String) {
        inst.waitForIdleSync()
        SystemClock.sleep(400) // let the system window animation settle before evidence capture
        val image = checkNotNull(inst.uiAutomation.takeScreenshot())
        try {
            val folder = checkNotNull(context.getExternalFilesDir("wm-camera-test"))
            File(folder, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun <T : Activity> click(scenario: ActivityScenario<T>, tag: String) {
        scenario.onActivity { assertTrue(it.window.decorView.findViewWithTag<View>(tag).performClick()) }
        inst.waitForIdleSync()
    }

    private fun <T : Activity> visibleAndEnabled(scenario: ActivityScenario<T>, tag: String): Boolean {
        var value = false
        scenario.onActivity {
            val root = it.window.decorView
            val view = root.findViewWithTag<View>(tag)
            value = root.hasWindowFocus() && view != null && view.isShown && view.isEnabled && view.getGlobalVisibleRect(Rect())
        }
        return value
    }

    private fun <T : Activity> read(scenario: ActivityScenario<T>, tag: String): String {
        var value = ""
        scenario.onActivity {
            val view = it.window.decorView.findViewWithTag<View>(tag)
            value = (if (view is TextView) view.text else view?.contentDescription)?.toString().orEmpty()
        }
        return value
    }

    private fun clickDialogTag(tag: String) {
        DeviceTestUi.main {
            val view = dialogView<Button>(tag)
            view.requestRectangleOnScreen(Rect(0,0,view.width,view.height),true)
            assertTrue(view.performClick())
        }
        inst.waitForIdleSync()
    }

    private fun dialogButton(text: String) {
        waitFor("dialog $text") {
            DeviceTestUi.main {
                globalViews().filterIsInstance<Button>().firstOrNull { it.isShown && it.text.toString() == text }
                    ?.performClick() ?: false
            }
        }
        inst.waitForIdleSync()
    }

    private inline fun <reified T : View> dialogView(tag: String) =
        globalViews().filterIsInstance<T>().first { it.tag == tag && it.isShown }

    private fun globalViews() = WindowInspector.getGlobalWindowViews().flatMap(::walk)

    private fun walk(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList()

    private fun waitFor(label: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 18_000
        do {
            if (predicate()) return
            SystemClock.sleep(120)
        } while (SystemClock.uptimeMillis() < end)
        fail("Timeout: $label")
    }
}
