package cn.campuslife.assistant

import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.map.*
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test

/** Run on the dedicated emulator with an explicitly signed non-debuggable test build. */
class CompatibilityReleaseTest {
    @Test fun localMapDraftRemainsVisibleAndEditableWithoutAndroidDebugging() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val prefs = context.getSharedPreferences("campus_coordinate_draft", 0)
        val before = prefs.getString("public_coordinates_v1", null)
        val profile = context.getSharedPreferences("campus_local_v1", 0).all
        try {
            val data = CampusCoordinateData(GeoPoint(25.550861,119.752883), listOf(
                CampusCoordinate("custom-release", GeoPoint(25.5526,119.751), "manual-map-review", true)),
                listOf(CampusLandmark("custom-release", "兼容测试地点", MapCategory.SERVICE, emptyList())), setOf("dorm-2"))
            assertTrue(prefs.edit().putString("public_coordinates_v1", CampusCoordinateStore.encode(data)).commit())
            assertEquals(data, CampusCoordinateStore(context).load())
            val changed = data.copy(hiddenIds = setOf("dorm-3"))
            assertTrue(CampusCoordinateStore(context).save(changed))
            assertEquals(changed, CampusCoordinateStore(context).load())
            assertEquals(profile, context.getSharedPreferences("campus_local_v1", 0).all)
            assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        } finally {
            prefs.edit().apply { if (before == null) remove("public_coordinates_v1") else putString("public_coordinates_v1", before) }.commit()
        }
    }
}
