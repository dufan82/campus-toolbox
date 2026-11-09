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

/** Explicit two-stage cover-install fixture on a disposable, offline emulator, never a user's phone. */
class BrandUpgradeTest {
    @Test fun rebrandCoverInstallPreservesDataLoginAndKeystore() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val phase=InstrumentationRegistry.getArguments().getString("compatibilityUpgradePhase")
        assumeTrue(phase in setOf("seed","verify"));assumeTrue(Build.HARDWARE in setOf("ranchu","goldfish"))
        val context=inst.targetContext
        assertEquals("cn.campuslife.assistant",context.packageName)
        assertEquals(if(phase=="seed") 28L else BuildConfig.VERSION_CODE.toLong(),context.packageManager.getPackageInfo(context.packageName,0).longVersionCode)
        CompatibilityUpgradeTest().coverInstallRetainsProfileScheduleCountdownAndPersonalMapBytes()
        val files=listOf("shared_prefs/home_layout_v1.xml","shared_prefs/school-daily-sync-v1.xml","no_backup/school-sync-credential-v1")
        val snapshot=File(context.filesDir,"brand-upgrade-extra.json")
        fun hashes()=JSONObject().apply {files.forEach {path->
            val f=File(context.applicationInfo.dataDir,path);assertTrue(path,f.isFile)
            put(path,MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString(""){"%02x".format(it.toInt()and 255)})
        }}
        if(phase=="seed") {
            assertEquals("校园生活助手",context.packageManager.getApplicationLabel(context.applicationInfo).toString())
            val home=HomeLayoutStore(context);home.save(home.load().move("map",1).hide("planner",true))
            DailySyncPreferences(context).write(DailySyncState(enabled=false,lastSuccess=123456L))
            SchoolSyncVault(context).write(SchoolSyncCredential("Bearer brand-upgrade-offline-token",Semester(LocalDate.of(2026,9,7),20)))
            inst.runOnMainSync {CookieManager.getInstance().setCookie("https://m.fjpit.com","brand_fixture_session=offline-only; Secure; Path=/");CookieManager.getInstance().flush()}
            snapshot.writeText(hashes().toString())
        } else {
            assertEquals("校伴",context.packageManager.getApplicationLabel(context.applicationInfo).toString())
            val expected=JSONObject(snapshot.readText());val actual=hashes();files.forEach {assertEquals(it,expected.getString(it),actual.getString(it))}
            assertEquals("Bearer brand-upgrade-offline-token",SchoolSyncVault(context).read()?.authorization)
            assertTrue("planner" in HomeLayoutStore(context).load().hidden)
            inst.runOnMainSync {assertTrue(CookieManager.getInstance().getCookie("https://m.fjpit.com").contains("brand_fixture_session=offline-only"))}
        }
    }
}
