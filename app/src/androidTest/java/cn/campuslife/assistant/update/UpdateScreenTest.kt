package cn.campuslife.assistant.update

import android.os.SystemClock
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.ScrollView
import android.widget.ProgressBar
import android.app.DownloadManager
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@SdkSuppress(minSdkVersion = 29)
class UpdateScreenTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun settingsCanCheckWithoutHidingExpressAndNoReleaseDoesNotClaimLatest() = isolated(null) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val shortcuts = descendants(root).filter { it.tag?.toString()?.startsWith("shortcut_") == true }
                assertEquals(3, shortcuts.size); assertTrue(shortcuts.all { it.isShown && it.isEnabled })
                descendants(root).first { it.contentDescription == "设置" }.performClick()
            }
            await { views().filterIsInstance<ListView>().any { list -> (0 until list.adapter.count).any { list.adapter.getItem(it).toString() == "检查更新" } } }
            instrumentation.runOnMainSync {
                val list = views().filterIsInstance<ListView>().last()
                val position = (0 until list.adapter.count).first { list.adapter.getItem(it).toString() == "检查更新" }
                list.performItemClick(list.getChildAt(position), position, list.getItemIdAtPosition(position))
            }
            await { views().filterIsInstance<TextView>().any { it.text.toString() == "应用更新" } }
            await { views().filterIsInstance<TextView>().any { it.text.toString() == "暂未发布正式版本" } }
            assertFalse(views().filterIsInstance<TextView>().any { it.text.toString() == "当前已是最新版本" })
            if (InstrumentationRegistry.getArguments().getString("captureUpdate") == "true") {
                instrumentation.waitForIdleSync(); SystemClock.sleep(120)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                val output = File(context.getExternalFilesDir("verification"), "v170-update.png")
                output.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
            }
        }
    }
    @Test fun ignoredUpdateDoesNotReappearAutomaticallyAfterActivityRecreation() = isolated(UpdatePackage(18, "1.7.1", "v1.7.1", 26, "campus-toolbox-1.7.1.apk", 3,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "合成更新说明", 123)) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { views().any { it.tag == "app_update_banner" && it.isShown } }
            instrumentation.runOnMainSync { views().first { it.tag == "app_update_banner" }.performClick() }
            await { views().filterIsInstance<Button>().any { it.text.toString() == "稍后再说" } }
            instrumentation.runOnMainSync { views().filterIsInstance<Button>().first { it.text.toString() == "稍后再说" }.performClick() }
            await { context.getSharedPreferences("updates-v1", 0).getLong("ignored", 0) == 18L }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            assertFalse(views().any { it.tag == "app_update_banner" && it.isShown })
        }
    }
    @Test fun installRecheckFailureIsVisibleInsteadOfOldReadyMessage() = isolated(null) {
        org.junit.Assume.assumeTrue("Grant REQUEST_INSTALL_PACKAGES on the dedicated test device before this installer case; reset it after the runner exits", context.packageManager.canRequestPackageInstalls())
        val info = UpdatePackage(18, "1.7.1", "v1.7.1", 26, "campus-toolbox-1.7.1.apk", 3,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "合成说明", 123)
        val downloads = context.getSharedPreferences("update-download-v1", 0)
        val apk = File(context.filesDir, "updates/update_${info.versionCode}_${info.sha256}.apk")
        apk.parentFile!!.mkdirs(); apk.writeText("abc")
        assertTrue(downloads.edit().clear().putString("info", UpdateJson.encode(info).toString()).putBoolean("ready", true).commit())
        try {
            ActivityScenario.launch(UpdateActivity::class.java).use {
                await { views().filterIsInstance<Button>().any { it.text.toString() == "安装更新" && it.isEnabled } }
                instrumentation.runOnMainSync { views().filterIsInstance<Button>().first { it.text.toString() == "安装更新" }.performClick() }
                await { views().filterIsInstance<TextView>().any { it.tag == "update_status" && it.text.toString().contains("无法读取安装包") } }
                assertTrue(views().filterIsInstance<Button>().any { it.text.toString() == "重新下载" })
            }
        } finally { downloads.edit().clear().commit(); apk.delete() }
    }
    @Test fun longNotesKeepScrollPositionAcrossDownloadPollingAndCancelRemainsUsable() {
        org.junit.Assume.assumeTrue("Synthetic release downloads must stay offline", android.provider.Settings.Global.getInt(context.contentResolver, "airplane_mode_on", 0) == 1)
        val info = fixture("更新说明\n".repeat(300))
        isolated(info) {
            val downloads = UpdateDownloads(context)
            try {
                downloads.start(info)
                ActivityScenario.launch(UpdateActivity::class.java).use {
                    await { views().any { it is ProgressBar && it.isShown } }
                    var before = 0
                    instrumentation.runOnMainSync { views().filterIsInstance<ScrollView>().last().apply { scrollTo(0, 100000); before = scrollY } }
                    assertTrue(before > 0)
                    SystemClock.sleep(2300)
                    instrumentation.runOnMainSync { assertEquals("Polling must not reset scroll", before, views().filterIsInstance<ScrollView>().last().scrollY) }
                    await { views().filterIsInstance<Button>().any { it.text.toString() == "取消本次更新" && it.isEnabled } }
                    instrumentation.runOnMainSync { views().filterIsInstance<Button>().first { it.text.toString() == "取消本次更新" }.performClick() }
                    await { context.getSharedPreferences("update-download-v1", 0).getLong("id", 0) == 0L }
                }
            } finally { downloads.cancel(); context.getSharedPreferences("update-download-v1", 0).edit().clear().commit() }
        }
    }
    @Test fun installPermissionCanBeDeclinedWithoutLeavingUpdatePage() = isolated(null) {
        org.junit.Assume.assumeTrue("Exercise this branch without the unknown-app install allowance", !context.packageManager.canRequestPackageInstalls())
        val info = fixture("合成说明")
        val prefs = context.getSharedPreferences("update-download-v1", 0)
        val apk = File(context.filesDir, "updates/update_${info.versionCode}_${info.sha256}.apk")
        apk.parentFile!!.mkdirs(); apk.writeText("abc")
        assertTrue(prefs.edit().clear().putString("info", UpdateJson.encode(info).toString()).putBoolean("ready", true).commit())
        try {
            ActivityScenario.launch(UpdateActivity::class.java).use { scenario ->
                await { views().filterIsInstance<Button>().any { it.text.toString() == "安装更新" && it.isEnabled } }
                instrumentation.runOnMainSync { views().filterIsInstance<Button>().first { it.text.toString() == "安装更新" }.performClick() }
                await { views().filterIsInstance<TextView>().any { it.text.toString() == "允许安装更新" } }
                instrumentation.runOnMainSync { views().filterIsInstance<Button>().first { it.text.toString() == "取消" }.performClick() }
                scenario.onActivity { assertFalse(it.isFinishing) }
                assertTrue(prefs.getBoolean("ready", false)); assertTrue(apk.exists())
            }
        } finally { prefs.edit().clear().commit(); apk.delete() }
    }
    @Test fun privateDirectoryFailureOffersRetryVerificationWithoutDownloadingAgain() = isolated(null) {
        val info = fixture("合成说明")
        val manager = context.getSystemService(DownloadManager::class.java)
        val source = File(context.getExternalFilesDir("verification"), "completed-update-test.apk").apply { parentFile!!.mkdirs(); writeText("abc") }
        val id = manager.addCompletedDownload("Update fixture", "Local completed fixture", false, "application/vnd.android.package-archive", source.absolutePath, 3, true, Uri.parse(info.url), null)
        val prefs = context.getSharedPreferences("update-download-v1", 0)
        val folder = File(context.filesDir, "updates")
        val backup = File(context.filesDir, "updates-test-backup-${java.util.UUID.randomUUID()}")
        val hadFolder = folder.exists()
        if (hadFolder) assertTrue(folder.renameTo(backup))
        assertTrue(folder.createNewFile())
        assertTrue(prefs.edit().clear().putString("info", UpdateJson.encode(info).toString()).putLong("id", id).commit())
        try {
            ActivityScenario.launch(UpdateActivity::class.java).use {
                await { views().filterIsInstance<TextView>().any { it.tag == "update_status" && it.text.toString().contains("无法创建更新目录") } }
                await { views().filterIsInstance<Button>().any { it.text.toString() == "重试校验" && it.isEnabled } }
                assertTrue(folder.delete()); if (hadFolder) assertTrue(backup.renameTo(folder))
                instrumentation.runOnMainSync { views().filterIsInstance<Button>().first { it.text.toString() == "重试校验" }.performClick() }
                await { views().filterIsInstance<Button>().any { it.text.toString() == "重新下载" && it.isEnabled } }
            }
        } finally {
            if (folder.isFile) folder.delete()
            if (backup.exists()) assertTrue(backup.renameTo(folder))
            manager.remove(id); prefs.edit().clear().commit(); source.delete()
        }
    }
    private fun fixture(notes: String) = UpdatePackage(18, "1.7.1", "v1.7.1", 26, "campus-toolbox-1.7.1.apk", 3,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", notes, 123)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun views(): List<View> {
        if (Looper.myLooper() == Looper.getMainLooper()) return WindowInspector.getGlobalWindowViews().flatMap(::descendants)
        var result: List<View> = emptyList()
        instrumentation.runOnMainSync { result = WindowInspector.getGlobalWindowViews().flatMap(::descendants) }
        return result
    }
    private fun await(check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) { if (check()) return; SystemClock.sleep(40) }
        assertTrue("Update UI did not settle", check())
    }
    private fun isolated(info: UpdatePackage?, block: () -> Unit) {
        val prefs = context.getSharedPreferences("updates-v1", 0)
        val original = prefs.all
        try {
            val edit = prefs.edit().clear().putLong("attempt", System.currentTimeMillis()).putString("kind", if (info == null) "missing" else "release")
                .putString("message", if (info == null) "暂未发布正式版本" else "发现新版本")
            info?.let { edit.putString("info", UpdateJson.encode(it).toString()) }; assertTrue(edit.commit())
            block()
        } finally {
            val edit = prefs.edit().clear()
            original.forEach { (key, value) -> when (value) { is Long -> edit.putLong(key, value); is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value); is Int -> edit.putInt(key, value) } }
            edit.commit()
        }
    }
}
