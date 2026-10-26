package cn.campuslife.assistant.update

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ApkVerificationTest {
    @Test fun installedDebugArtifactIsRecognizedButNeverOfferedAsFormalUpdate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apk = File(context.applicationInfo.sourceDir)
        val identity = ApkVerifier(context).identity(apk)
        assertEquals(context.packageName, identity.packageName)
        assertTrue(identity.signers.isNotEmpty())
        assertTrue(identity.debuggable)
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input -> val buffer = ByteArray(32768); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        val info = UpdatePackage(identity.versionCode, "1.7.0", "v1.7.0", identity.minSdk, "campus-toolbox-1.7.0.apk", apk.length(), UpdateRules.digest(digest.digest()), "合成测试", 1)
        assertThrows(Exception::class.java) { ApkVerifier(context).verify(apk, info) }
    }
}
