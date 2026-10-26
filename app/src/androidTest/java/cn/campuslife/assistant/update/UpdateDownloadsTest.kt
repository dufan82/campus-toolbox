package cn.campuslife.assistant.update

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import cn.campuslife.assistant.BuildConfig

class UpdateDownloadsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val info = UpdatePackage(18, "1.7.1", "v1.7.1", 26, "campus-toolbox-1.7.1.apk", 3,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "合成说明", 123)
    private class Backend : UpdateDownloadBackend {
        var count = 0; val cancelled = mutableListOf<Long>(); var phase = UpdatePhase.DOWNLOADING
        var source = "https://github.com/dufan82/campus-toolbox/releases/download/v1.7.1/campus-toolbox-1.7.1.apk"
        override fun enqueue(info: UpdatePackage): Long { source = info.url; return (++count).toLong() }
        override fun query(id: Long) = DownloadStatus(phase, 1, 3, "", source)
        override fun open(id: Long) = ByteArrayInputStream("abc".toByteArray())
        override fun cancel(id: Long) { cancelled.add(id) }
    }
    @Test fun repeatedDownloadClickAndNewControllerReuseOneTaskUntilExplicitCancel() {
        val prefs = context.getSharedPreferences("update-download-test", 0); prefs.edit().clear().commit()
        val backend = Backend()
        val first = UpdateDownloads(context, backend, prefs)
        first.start(info); first.start(info)
        assertEquals(1, backend.count)
        val restored = UpdateDownloads(context, backend, prefs)
        assertEquals(1L, restored.state().downloaded)
        assertEquals(UpdatePhase.DOWNLOADING, restored.state().phase)
        restored.cancel()
        assertEquals(listOf(1L), backend.cancelled)
        assertEquals(UpdatePhase.NONE, first.state().phase)
        first.start(info); assertEquals(2, backend.count)
        first.cancel(); prefs.edit().clear().commit()
    }
    @Test fun matchingHashButInvalidApkIsNeverMarkedReadyAndRetryCreatesNewTask() {
        val prefs = context.getSharedPreferences("update-invalid-apk-test", 0); prefs.edit().clear().commit()
        val backend = Backend().apply { phase = UpdatePhase.DOWNLOADED }
        val downloads = UpdateDownloads(context, backend, prefs)
        downloads.start(info)
        assertThrows(Exception::class.java) { downloads.verifyDownloaded() }
        assertEquals(UpdatePhase.FAILED, downloads.state().phase)
        assertNull(downloads.state().file)
        assertEquals(listOf(1L), backend.cancelled)
        downloads.start(info); assertEquals(2, backend.count)
        downloads.cancel(); prefs.edit().clear().commit()
    }
    @Test fun interruptedPrivateCopyIsReusedAndRemovedInsteadOfLeakingAnotherPartialFile() {
        val prefs = context.getSharedPreferences("update-partial-test", 0); prefs.edit().clear().commit()
        val backend = Backend().apply { phase = UpdatePhase.DOWNLOADED }
        val downloads = UpdateDownloads(context, backend, prefs)
        val partial = File(context.filesDir, "updates/update_${info.versionCode}_${info.sha256}.part")
        partial.parentFile!!.mkdirs(); partial.writeText("interrupted copy")
        try {
            downloads.start(info)
            assertThrows(Exception::class.java) { downloads.verifyDownloaded() }
            assertFalse("Interrupted verification must not leak its private partial", partial.exists())
        } finally { partial.delete(); downloads.cancel(); prefs.edit().clear().commit() }
    }
    @Test fun completedUpgradeClearsOnlyItsOldDownloadAndPrivateFiles() {
        val prefs = context.getSharedPreferences("update-installed-test", 0); prefs.edit().clear().commit()
        val installed = info.copy(versionCode = BuildConfig.VERSION_CODE.toLong())
        val apk = File(context.filesDir, "updates/update_${installed.versionCode}_${installed.sha256}.apk")
        val unrelated = File(context.filesDir, "updates/not-an-update.txt")
        apk.parentFile!!.mkdirs(); apk.writeText("old verified package"); unrelated.writeText("keep")
        val backend = Backend().apply { source = installed.url }
        prefs.edit().putString("info", UpdateJson.encode(installed).toString()).putLong("id", 8).putBoolean("ready", true).commit()
        try {
            assertEquals(UpdatePhase.NONE, UpdateDownloads(context, backend, prefs).state().phase)
            assertEquals(listOf(8L), backend.cancelled)
            assertFalse(apk.exists()); assertTrue(unrelated.exists()); assertNull(prefs.getString("info", null))
        } finally { apk.delete(); unrelated.delete(); prefs.edit().clear().commit() }
    }
    @Test fun cancelRemovesOwnPrivateApkEvenIfReadyFlagWasNotCommitted() {
        val prefs = context.getSharedPreferences("update-orphan-test", 0); prefs.edit().clear().commit()
        val apk = File(context.filesDir, "updates/update_${info.versionCode}_${info.sha256}.apk")
        apk.parentFile!!.mkdirs(); apk.writeText("orphan after rename")
        prefs.edit().putString("info", UpdateJson.encode(info).toString()).putBoolean("ready", false).commit()
        try {
            UpdateDownloads(context, Backend(), prefs).cancel()
            assertFalse("Cancel must remove the exact owned APK", apk.exists())
        } finally { apk.delete(); prefs.edit().clear().commit() }
    }
    @Test fun restoredReadyFileWhichFailsRecheckCanBeDownloadedAgain() {
        val prefs = context.getSharedPreferences("update-ready-recheck-test", 0); prefs.edit().clear().commit()
        val apk = File(context.filesDir, "updates/update_${info.versionCode}_${info.sha256}.apk")
        apk.parentFile!!.mkdirs(); apk.writeText("abc")
        prefs.edit().putString("info", UpdateJson.encode(info).toString()).putBoolean("ready", true).commit()
        val backend = Backend()
        val downloads = UpdateDownloads(context, backend, prefs)
        try {
            assertThrows(Exception::class.java) { downloads.verifyDownloaded() }
            assertEquals(UpdatePhase.FAILED, downloads.state().phase)
            downloads.start(info); assertEquals(1, backend.count)
        } finally { downloads.cancel(); apk.delete(); prefs.edit().clear().commit() }
    }
}
