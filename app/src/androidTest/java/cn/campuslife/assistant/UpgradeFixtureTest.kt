package cn.campuslife.assistant

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.express.ExpressPreferences
import cn.campuslife.watermark.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Release-only fixture. Run explicitly on the dedicated emulator, never on a real phone. */
@RunWith(AndroidJUnit4::class)
class UpgradeFixtureTest {
    @Test fun explicitOldAndNewApkUpgradePhase() {
        val phase=InstrumentationRegistry.getArguments().getString("upgradePhase")
        assumeTrue(phase=="seed" || phase=="verify")
        assumeTrue(Build.HARDWARE in setOf("ranchu","goldfish"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=LocalStore(context);val express=ExpressPreferences(context)
        if(phase=="seed") {
            // Only APIs already present in 1.0.0 are used in this branch.
            store.saveProfile(CampusProfile(mapOf(FieldKey.SCHOOL to "旧字段升级测试",FieldKey.CLASS_NAME to "升级验证班级",FieldKey.NAME to "升级验证同学",FieldKey.SEAT_NUMBER to "05")))
            store.saveDetails(ActivityDetails(mapOf(FieldKey.EVENT_NAME to "旧活动字段保留测试",FieldKey.CAPTURED_AT to "2026-10-23")))
            // Keep the fixture callable inside the previous APK despite added optional model fields.
            val defaultConfig=EditorConfig::class.java.getConstructor().newInstance()
            store.saveConfig(defaultConfig)
            store.saveTemplates(listOf(WatermarkTemplate("upgrade-fixture","升级验证模板",defaultConfig)))
            express.write("link.pdd_code","https://example.com/upgrade-fixture")
        } else {
            assertEquals("升级验证同学",store.profile().values[FieldKey.NAME])
            assertEquals("旧字段升级测试",store.profile().values[FieldKey.SCHOOL])
            assertEquals("旧活动字段保留测试",store.details().values[FieldKey.EVENT_NAME])
            assertEquals("升级验证模板",store.templates().single { it.id=="upgrade-fixture" }.name)
            assertEquals("https://example.com/upgrade-fixture",express.read("link.pdd_code"))
            assertFalse(FieldKey.SCHOOL in store.config().orderedFields())
            assertFalse(FieldKey.EVENT_NAME in store.config().orderedFields())
            assertTrue(formattedFields(store.profile(),store.details(),store.config()).none { "旧字段" in it || "旧活动" in it })
            assertEquals(Position.BOTTOM_RIGHT,store.config().style.position)
            assertTrue(store.config().contentOnly)
            assertEquals(listOf("升级验证班级","升级验证同学","05","2026-10-23"),formattedFields(store.profile(),store.details(),store.config()))
        }
    }
}
