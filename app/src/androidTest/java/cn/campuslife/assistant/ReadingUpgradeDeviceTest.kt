package cn.campuslife.assistant

import android.os.Build
import android.webkit.CookieManager
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.home.HomeLayoutStore
import cn.campuslife.assistant.schedule.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/** Explicit old-version cover install on the disposable offline emulator; no real user records. */
class ReadingUpgradeDeviceTest {
    @Test fun coverInstallKeepsExistingBusinessBytesLoginAndOldLayoutWhileAppendingStory() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val phase=InstrumentationRegistry.getArguments().getString("compatibilityUpgradePhase")
        assumeTrue(phase in setOf("seed","verify"));assumeTrue(Build.HARDWARE in setOf("ranchu","goldfish"))
        val app=inst.targetContext
        CompatibilityUpgradeTest().coverInstallRetainsProfileScheduleCountdownAndPersonalMapBytes()
        val files=listOf("shared_prefs/home_layout_v1.xml","shared_prefs/school-daily-sync-v1.xml","no_backup/school-sync-credential-v1")
        val snapshot=File(app.filesDir,"reading-upgrade-checksums.json")
        fun hashes()=JSONObject().apply {files.forEach {path->
            val file=File(app.applicationInfo.dataDir,path);assertTrue(path,file.isFile)
            put(path,MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)})
        }}
        if(phase=="seed") {
            assertTrue(app.getSharedPreferences("home_layout_v1",0).edit().putString("layout","""{"version":1,"order":["planner","map","schedule","watermark"],"hidden":["map"]}""").commit())
            assertTrue(app.getSharedPreferences("school-daily-sync-v1",0).edit().clear().putBoolean("enabled",false).putLong("success",123456).putString("result","NO_CHANGE").commit())
            SchoolSyncVault(app).write(SchoolSyncCredential("Bearer reading-upgrade-offline-token",Semester(LocalDate.of(2026,9,7),20)))
            inst.runOnMainSync {CookieManager.getInstance().setCookie("https://m.fjpit.com","reading_fixture_session=offline-only; Secure; Path=/");CookieManager.getInstance().flush()}
            snapshot.writeText(hashes().toString())
        } else {
            val before=JSONObject(snapshot.readText());val after=hashes();files.forEach {assertEquals(it,before.getString(it),after.getString(it))}
            val layout=HomeLayoutStore(app).load()
            assertEquals(listOf("planner","map","schedule","watermark","story"),layout.order);assertEquals(setOf("map"),layout.hidden)
            assertEquals("",DailySyncPreferences(app).read().details)
            assertEquals("Bearer reading-upgrade-offline-token",SchoolSyncVault(app).read()?.authorization)
            inst.runOnMainSync {assertTrue(CookieManager.getInstance().getCookie("https://m.fjpit.com").contains("reading_fixture_session=offline-only"))}
        }
    }
}
