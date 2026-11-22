package cn.campuslife.assistant.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Run alone on emulator-5580 with a synthetic adb emu geo fix, never on a real phone. */
@RunWith(AndroidJUnit4::class)
class CameraAutomaticLocationDeviceTest {
    @Test fun optInReceivesSystemLocationAndShowsApproximateText() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        val store = CameraSettingsStore(app)
        val previous = store.read()
        store.save(CameraSettings(automaticPlace = true))
        try {
            ActivityScenario.launch(WatermarkCameraActivity::class.java).use { scenario ->
                val end = SystemClock.uptimeMillis() + 40_000
                var observed = false
                while (SystemClock.uptimeMillis() < end && !observed) {
                    scenario.onActivity {
                        val root = it.window.decorView
                        val stamp = root.findViewWithTag<View>("camera_stamp").contentDescription?.toString().orEmpty()
                        val control = root.findViewWithTag<Button>("camera_location")
                        if (stamp.contains("自动定位 · 约 30.13°N，120.91°E")) {
                            assertEquals("定位 · 约 30.13°N，120.91°E", control.text.toString())
                            assertTrue(root.findViewWithTag<View>("camera_shutter").isEnabled)
                            observed = true
                        }
                    }
                    if (!observed) SystemClock.sleep(180)
                }
                if (!observed) fail("The camera did not receive the injected GPS fix")
            }
        } finally { store.save(previous) }
}
}
