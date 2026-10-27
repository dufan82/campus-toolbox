package cn.campuslife.assistant.update

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run against the frozen, actually installed code20 app, never against code21 itself. */
class PreparedUpdateSmokeTest {
    private fun previousApp() = InstrumentationRegistry.getInstrumentation().targetContext.also { context ->
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        assertEquals("Install the frozen v1.7.3 emulator app first", 20L, code)
    }

    @Test fun prepared174ApkPassesInstalled173VerifierBeforePublication() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("prepared174Check") == "true")
        val context = previousApp()
        val directory = context.getExternalFilesDir("verification")!!
        val metadata = File(directory, "prepared174-update.json").readText()
        val info = UpdateJson.decode(JSONObject(metadata), 123L, "Local pre-publication check", UpdateSource.CNB, "campus-toolbox/campus-toolbox")
        assertEquals(21L, info.versionCode)
        assertTrue(UpdateRules.available(info, 20L, Build.VERSION.SDK_INT))
        ApkVerifier(context).verify(File(directory, "prepared174.apk"), info)
    }

    @Test fun installed173DiscoversActualPublished174WithoutGithub() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCnb174Check") == "true")
        val context = previousApp()
        val prefs = context.getSharedPreferences("live-next-${UUID.randomUUID()}", 0)
        try {
            val repo = UpdateRepository(prefs, GitHubReleaseClient { throw AssertionError("Domestic release must not need GitHub") })
            val check = repo.check(manual = true)
            assertTrue(check.message, check.offer)
            val info = requireNotNull(check.update)
            assertEquals(21L, info.versionCode)
            assertEquals("v1.7.4", info.tag)
            assertEquals(UpdateSource.CNB, info.source)
            assertEquals("7a9888fe941921b63a0f8fadcccd8cb5e9ee514a443c3ca9982448bc1ee7b2a9", info.sha256)
        } finally { assertTrue(prefs.edit().clear().commit()) }
    }
}
