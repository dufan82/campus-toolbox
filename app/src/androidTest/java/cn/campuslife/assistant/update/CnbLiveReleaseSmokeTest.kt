package cn.campuslife.assistant.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.BuildConfig
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit online verification of v1.7.3, never part of the offline/default CI acceptance. */
class CnbLiveReleaseSmokeTest {
    private fun liveContext(): Context {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCnb173Check") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals("campus-toolbox/campus-toolbox", BuildConfig.CNB_REPOSITORY)
        assertEquals(20, BuildConfig.VERSION_CODE)
        return InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test fun publishedMetadataRangeProbeAndAnonymousApkPassRealClientChecks() {
        val context = liveContext()
        val client = CnbReleaseClient()
        val info = requireNotNull(client.fetch(null).update)
        assertEquals(UpdateSource.CNB, info.source)
        assertEquals(BuildConfig.CNB_REPOSITORY, info.repository)
        assertEquals("v1.7.3", info.tag)
        assertEquals(20L, info.versionCode)
        assertEquals("746c1529090543101770d9564536784748c7052f29805e84e1f304aab47ea364", info.sha256)
        assertEquals("asset.cnb.cool", URI(client.resolveApk(info)).host)

        val apk = File(context.getExternalFilesDir("verification"), "cnb-release173.apk")
        assertTrue("Push the independently downloaded public APK first", apk.isFile)
        assertEquals(info.size, apk.length())
        apk.inputStream().use { UpdateRules.copyVerified(it, object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }, info) }
        val identity = ApkVerifier(context).identity(apk)
        val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = installed.signingInfo!!.apkContentsSigners.map {
            UpdateRules.digest(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
        }.toSet()
        // This is a code19 comparison, not a claim that a previous release was installed/upgraded.
        assertTrue(UpdateRules.compatible(info, identity, 19L, Build.VERSION.SDK_INT, signers))
        assertFalse(UpdateRules.available(info, 20L, Build.VERSION.SDK_INT))
        assertFalse(identity.debuggable)
    }

    @Test fun repositoryReadsDomesticReleaseWithoutGithubAndDoesNotOfferItsOwnVersion() {
        val context = liveContext()
        val prefs = context.getSharedPreferences("live-cnb173-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        var githubRequests = 0
        try {
            val repository = UpdateRepository(prefs, GitHubReleaseClient {
                githubRequests++
                throw AssertionError("The verified domestic release must not need GitHub")
            })
            val check = repository.check(manual = true)
            val cached = requireNotNull(repository.cached())
            assertEquals(UpdateSource.CNB, cached.source)
            assertEquals("v1.7.3", cached.tag)
            assertEquals("当前已是最新版本", check.message)
            assertFalse(check.offer)
            assertNull(check.update)
            assertEquals(0, githubRequests)
        } finally {
            assertTrue(prefs.edit().clear().commit())
        }
    }
}
