package cn.campuslife.assistant

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.express.ExpressPreferences
import cn.campuslife.assistant.map.*
import cn.campuslife.assistant.planner.*
import cn.campuslife.assistant.schedule.*
import cn.campuslife.watermark.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.security.MessageDigest

/** Explicit seed/verify stages only, never runs against a user's phone or their real data. */
class CompatibilityUpgradeTest {
    @Test fun coverInstallRetainsProfileScheduleCountdownAndPersonalMapBytes() {
        val phase = InstrumentationRegistry.getArguments().getString("compatibilityUpgradePhase")
        assumeTrue(phase == "seed" || phase == "verify")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = File(context.filesDir, "compatibility171-checksums.json")
        val targets = listOf("files/schedule-v1.json", "files/planner-v1.json", "shared_prefs/campus_local_v1.xml",
            "shared_prefs/express_entries_v1.xml", "shared_prefs/campus_coordinate_draft.xml")
        fun hashes() = JSONObject().apply {
            targets.forEach { path ->
                val file = File(context.applicationInfo.dataDir, path)
                assertTrue("Missing fixture: $path", file.isFile)
                put(path, MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) })
            }
        }
        if (phase == "seed") {
            val store = LocalStore(context)
            store.saveProfile(CampusProfile(mapOf(FieldKey.CLASS_NAME to "兼容测试班", FieldKey.NAME to "升级测试同学")))
            store.saveConfig(EditorConfig()); store.saveTemplates(listOf(WatermarkTemplate("compatibility171", "升级测试模板", EditorConfig())))
            ExpressPreferences(context).write("link.pdd_code", "https://example.com/compatibility171")
            val schedule = ScheduleStore(context)
            schedule.commit(schedule.load(), ScheduleData(Semester(LocalDate.of(2026,9,7),20), courses=listOf(
                Course("11111111-2222-3333-4444-555555555555", "兼容测试课程", "测试教师", "实训1楼", 1,1,2,1,20,WeekPattern.EVERY,null))))
            PlannerStore(context).write(PlannerState(countdowns=listOf(
                CountdownItem("11111111-2222-3333-4444-555555555556", "兼容测试日期", LocalDate.of(2027,1,1)))))
            val map = CampusCoordinateStore(context)
            val data = map.load().withCustomPlace(CampusLandmark("custom-compatibility171", "兼容测试地点", MapCategory.SERVICE))
                .withPosition("custom-compatibility171", GeoPoint(25.5526,119.751)).withHiddenIds(setOf("dorm-2"))
            assertTrue(map.save(data))
            snapshot.writeText(hashes().toString())
        } else {
            val expected = JSONObject(snapshot.readText()); val actual = hashes()
            targets.forEach { assertEquals("Upgrade changed $it", expected.getString(it), actual.getString(it)) }
            assertEquals("升级测试同学", LocalStore(context).profile().values[FieldKey.NAME])
            assertEquals("兼容测试课程", ScheduleStore(context).load().courses.single().name)
            assertEquals("兼容测试日期", PlannerStore(context).read().countdowns.single().title)
            assertEquals(GeoPoint(25.5526,119.751), CampusCoordinateStore(context).load().points["custom-compatibility171"])
            assertTrue("dorm-2" in CampusCoordinateStore(context).load().hiddenIds)
        }
    }
}
