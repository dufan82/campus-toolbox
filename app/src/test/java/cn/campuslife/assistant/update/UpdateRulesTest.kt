package cn.campuslife.assistant.update

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UpdateRulesTest {
    private fun release() = UpdatePackage(18, "1.7.1", "v1.7.1", 26, "campus-toolbox-1.7.1.apk", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "更新说明", 123)

    @Test fun updateUsesIntegerCodeRatherThanDisplayNameAndHonorsDeviceFloor() {
        val update = release().copy(versionName = "1.0", tag = "v1.0")
        assertTrue(UpdateRules.available(update, 17, 26))
        assertFalse(UpdateRules.available(update, 18, 26))
        assertFalse(UpdateRules.available(update.copy(minSdk = 35), 17, 34))
    }

    @Test fun onlyExactOfficialReleaseAssetPathsAreAccepted() {
        val info = release()
        assertEquals("https://github.com/dufan82/campus-toolbox/releases/download/v1.7.1/campus-toolbox-1.7.1.apk", info.url)
        listOf(info.url.replace("github.com", "github.com.evil.test"), info.url + "?download=1", info.url.replace("https:", "http:"), info.url.replace("dufan82", "someone"), info.url.replace("v1.7.1/", "v9.9.9/")).forEach { url ->
            assertFalse(UpdateRules.assetUrl(url, info.tag, info.assetName))
        }
        assertTrue(UpdateRules.assetUrl(info.url, info.tag, info.assetName))
    }

    @Test fun incompleteOrUnsafeMetadataCannotBecomeInstallable() {
        val info = release()
        assertTrue(UpdateRules.valid(info))
        listOf(info.copy(versionCode = 0), info.copy(size = 0), info.copy(size = 600L * 1024 * 1024), info.copy(sha256 = "ab"), info.copy(assetName = "../a.apk"), info.copy(tag = "../main"), info.copy(versionName = "bad\nname"), info.copy(releaseId = 0)).forEach { assertFalse(UpdateRules.valid(it)) }
    }

    @Test fun automaticCacheManualDebounceAndServerBackoffHaveSeparateRules() {
        val now = 200_000_000L
        assertFalse(UpdateRules.shouldCheck(now, now - 60_000, 0, false))
        assertTrue(UpdateRules.shouldCheck(now, now - 86_400_001, 0, false))
        assertTrue(UpdateRules.shouldCheck(now, now - 60_001, 0, true))
        assertFalse(UpdateRules.shouldCheck(now, now - 1000, 0, true))
        assertFalse(UpdateRules.shouldCheck(now, 0, now + 1000, true))
        assertTrue(UpdateRules.shouldCheck(now, now + 1000, 0, false))
    }

    @Test fun shaAndSizeAreVerifiedWhileCopyingAndOversizedInputsStop() {
        val info = release()
        val output = ByteArrayOutputStream()
        UpdateRules.copyVerified(ByteArrayInputStream("abc".toByteArray()), output, info)
        assertEquals("abc", output.toString("UTF-8"))
        for (bad in listOf("abd", "abcd", "ab")) {
            assertThrows(Exception::class.java) { UpdateRules.copyVerified(ByteArrayInputStream(bad.toByteArray()), ByteArrayOutputStream(), info) }
        }
    }

    @Test fun identityCheckRejectsWrongPackageOlderCodeDifferentSignerAndDebugApk() {
        val info = release()
        val good = ApkIdentity("cn.campuslife.assistant", 18, 26, setOf("cert-a"), false)
        assertTrue(UpdateRules.compatible(info, good, 17, 35, setOf("cert-a")))
        listOf(good.copy(packageName = "other"), good.copy(versionCode = 17), good.copy(signers = setOf("cert-b")), good.copy(signers = setOf("cert-a", "cert-b")), good.copy(debuggable = true), good.copy(minSdk = 36)).forEach {
            assertFalse(UpdateRules.compatible(info, it, 17, 35, setOf("cert-a")))
        }
    }
}
