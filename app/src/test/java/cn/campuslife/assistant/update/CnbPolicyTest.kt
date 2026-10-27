package cn.campuslife.assistant.update

import org.junit.Assert.*
import org.junit.Test

class CnbPolicyTest {
    private val url = "https://cnb.cool/fixture/project/-/releases/download/v1.7.2/update.json"

    @Test fun onlyConfiguredRepositoryAndExactUnsignedReleasePathAreAllowed() {
        assertTrue(UpdateRules.cnbAssetUrl(url, "fixture/project", "v1.7.2", "update.json"))
        for (bad in listOf(url.replace("https:", "http:"), url.replace("cnb.cool", "cnb.cool.evil.test"),
            url.replace("fixture", "other"), url.replace("v1.7.2", "v1.7.3"), "$url?token=123", "$url#x",
            url.replace("cnb.cool/", "user@cnb.cool/"), url.replace("cnb.cool/", "cnb.cool:8443/"), url.replace("update.json", "%75pdate.json"))) {
            assertFalse(bad, UpdateRules.cnbAssetUrl(bad, "fixture/project", "v1.7.2", "update.json"))
        }
    }

    @Test fun emptyOrRemoteRepositoryCannotEnableCnb() {
        for (bad in listOf("", "https://cnb.cool/fixture/project", "/fixture/project", "fixture/project/extra", "../project", "fixture/..", "fixture/project?x=1", "fixture/project\n"))
            assertFalse(bad, UpdateRules.cnbRepository(bad))
        assertTrue(UpdateRules.cnbRepository("codewhale.net/codewhale"))
    }

    @Test fun signedQueriesAreAllowedOnlyAtExactOfficialAttachmentHost() {
        assertTrue(UpdateRules.attachmentRedirect("https://asset.cnb.cool/path/file?signature=dynamic&expires=123", UpdateSource.CNB))
        for (bad in listOf("http://asset.cnb.cool/file", "https://asset.cnb.cool.evil.test/file", "https://evil.test/file",
            "https://user@asset.cnb.cool/file", "https://asset.cnb.cool:8443/file", "https://asset.cnb.cool/file#x",
            "https://cnb.cool/fixture/project?signature=123", "https://api.cnb.cool/file", "https://objects.githubusercontent.com/file"))
            assertFalse(bad, UpdateRules.attachmentRedirect(bad, UpdateSource.CNB))
        assertFalse(UpdateRules.attachmentRedirect("https://asset.cnb.cool/file", UpdateSource.GITHUB))
    }

    @Test fun remoteMetadataCannotSelectACnbRepositoryAsTrustAnchor() {
        val info = UpdatePackage(19, "1.7.2", "v1.7.2", 26, "app.apk", 3, "a".repeat(64), "fixture", 1, UpdateSource.CNB, "unconfigured/remote")
        assertTrue(UpdateRules.valid(info))
        assertFalse(UpdateRules.trusted(info))
        assertFalse(UpdateRules.available(info, 18, 35))
        assertFalse(UpdateRules.downloadUrl(info))
    }
}
