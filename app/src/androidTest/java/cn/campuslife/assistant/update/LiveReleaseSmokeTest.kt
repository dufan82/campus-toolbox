package cn.campuslife.assistant.update

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit online check against the real release, on a dedicated emulator's installed code17. */
class LiveReleaseSmokeTest {
    @Test fun previousVersionCanDiscoverAndVerifyPublishedCompatibilityApk() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveReleaseCheck") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        assertEquals("This check must run in the previous app, not the new one", 17L, code)
        val info = requireNotNull(GitHubReleaseClient().fetch(null).update)
        assertEquals(18L, info.versionCode)
        assertEquals("v1.7.1", info.tag)
        assertTrue(UpdateRules.available(info, code, Build.VERSION.SDK_INT))
        val downloaded = File(context.getExternalFilesDir("verification"), "public-release171.apk")
        assertTrue("Push the independently downloaded public APK before running this test", downloaded.isFile)
        ApkVerifier(context).verify(downloaded, info)
        assertFalse(ApkVerifier(context).identity(downloaded).debuggable)
    }
}
