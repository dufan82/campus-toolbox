package cn.campuslife.assistant.schedule

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@SdkSuppress(minSdkVersion = 29)
class ScheduleTransferFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun timetable(name: String) = ScheduleData(Semester(LocalDate.of(2026, 9, 7), 20)).add(Course(
        "00000000-0000-0000-0000-000000000222", name, "老师", "教室", 2, 3, 4, 1, 20, WeekPattern.EVERY, null))

    @Test fun currentSemesterImportIsActionableBeforeAuthorizationAndExplainsMissingLogin() {
        val before = ScheduleStore(context).load()
        launchTransfer().use { scenario ->
            await { enabled("登录学校平台") }; tap("登录学校平台")
            await { hasWebView() }
            scenario.onActivity { nodes().filterIsInstance<android.webkit.WebView>().single().stopLoading() }
            await { enabled("导入当前学期") }
            assertTrue("Import must not be a permanently disabled button", enabled("导入当前学期"))
            instrumentation.runOnMainSync {
                val button = nodes().filterIsInstance<Button>().first { it.text == "导入当前学期" }
                val bounds = android.graphics.Rect()
                assertTrue(button.getGlobalVisibleRect(bounds))
                assertEquals("Import button must not be clipped", button.height, bounds.height())
            }
            capture("school-current-import-login.png")
            tap("导入当前学期")
            await { textContains("尚未识别到登录状态") }
            assertEquals(before, ScheduleStore(context).load())
        }
    }

    @Test fun loginWithoutAnyTimetableRequestAutomaticallyStartsImportOnceAndAllowsRetry() {
        org.junit.Assume.assumeTrue("Synthetic login must never be sent to the school",
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        val before = ScheduleStore(context).load()
        launchTransfer().use { scenario ->
            await { enabled("登录学校平台") }; tap("登录学校平台")
            await { hasWebView() }
            await { textContains("学校页面暂时无法打开") }
            scenario.onActivity {
                val view = nodes().filterIsInstance<android.webkit.WebView>().single()
                loadSchoolFixture(view)
            }
            try { await { textContains("当前学期读取失败") && enabled("导入当前学期") } }
            catch (error: AssertionError) {
                scenario.onActivity { activity ->
                    val state = activity.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(activity)
                    val attempted = state.javaClass.getDeclaredField("autoImportAttempted").apply { isAccessible = true }.getBoolean(state)
                    fail("Automatic import attempted=$attempted: " + nodes().filterIsInstance<TextView>().joinToString(" | ") { it.text.toString() })
                }
                throw error
            }
            assertEquals(before, ScheduleStore(context).load())
            scenario.recreate()
            await { enabled("导入当前学期") }
            SystemClock.sleep(1800) // Cross one scheduled observation tick: failure must not auto-loop.
            assertTrue(textContains("当前学期读取失败"))
            val loaded = CountDownLatch(1)
            scenario.onActivity { loadSchoolFixture(nodes().filterIsInstance<android.webkit.WebView>().single(), loaded) }
            assertTrue("Logged-in school fixture did not finish", loaded.await(5, TimeUnit.SECONDS))
            tap("导入当前学期")
            await { textContains("当前学期读取失败") && enabled("导入当前学期") }
            assertEquals(before, ScheduleStore(context).load())
        }
    }

    private fun loadSchoolFixture(view: android.webkit.WebView, loaded: CountDownLatch? = null) {
        view.stopLoading()
        val html = """<html><body><script>
            localStorage.setItem('user',JSON.stringify({tokenInfo:{token:'synthetic_no_network_token'},userInfo:{token:'',realname:'Synthetic'}}));
            </script>Offline logged-in fixture without XHR</body></html>"""
        val client = view.webViewClient
        view.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldInterceptRequest(web: android.webkit.WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                if (request.url.toString() == "https://m.fjpit.com/current-term-fixture") {
                    return android.webkit.WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
                }
                return client.shouldInterceptRequest(web, request)
            }
            override fun onPageFinished(web: android.webkit.WebView, url: String?) {
                client.onPageFinished(web, url)
                if (url == "https://m.fjpit.com/current-term-fixture") loaded?.countDown()
            }
            override fun onPageStarted(web: android.webkit.WebView, url: String?, icon: android.graphics.Bitmap?) = client.onPageStarted(web, url, icon)
            override fun onReceivedError(web: android.webkit.WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) = client.onReceivedError(web, request, error)
        }
        view.loadUrl("https://m.fjpit.com/current-term-fixture")
    }

    @Test fun manualRetryAfterWebLogoutDoesNotReusePreviousAccountAuthorization() {
        org.junit.Assume.assumeTrue("Synthetic authorization must stay offline",
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        val before = ScheduleStore(context).load()
        launchTransfer().use { scenario ->
            await { enabled("登录学校平台") }; tap("登录学校平台")
            await { enabled("导入当前学期") }
            scenario.onActivity { activity ->
                val state = activity.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(activity)
                state.javaClass.getDeclaredField("authorization").apply { isAccessible = true }.set(state, "Bearer synthetic_logged_out_account")
                state.javaClass.getDeclaredField("autoImportAttempted").apply { isAccessible = true }.setBoolean(state, true)
                nodes().filterIsInstance<android.webkit.WebView>().single().stopLoading()
            }
            tap("导入当前学期")
            await { textContains("尚未识别到登录状态") && enabled("导入当前学期") }
            assertEquals(before, ScheduleStore(context).load())
        }
    }

    @Test fun restorePreviewCancelAndConfirmPersistAcrossRecreationWithoutOpeningSchoolPage() {
        val local = File(context.filesDir, ScheduleStore.FILE_NAME)
        val undo = File(context.filesDir, ScheduleStore.IMPORT_UNDO_FILE)
        val original = local.takeIf { it.exists() }?.readBytes()
        val previous = undo.takeIf { it.exists() }?.readBytes()
        val backup = File(context.cacheDir, "schedule-flow-fixture.json")
        try {
            local.delete(); undo.delete()
            val store = ScheduleStore(context)
            store.commit(store.load(), timetable("原来课程"))
            backup.outputStream().use { ScheduleBackup.write(it, timetable("离线新课程")) }
            launchTransfer().use { scenario ->
                await { enabled("登录学校平台") }
                assertFalse(hasWebView())
                capture("school-transfer-home.png")
                scenario.onActivity { deliverRestore(it, backup) }
                await { enabled("确认保存到本机") }
                assertEquals("原来课程", store.load().courses.single().name)
                capture("school-transfer-preview.png")
                scenario.recreate()
                await { enabled("确认保存到本机") }
                tap("取消，不修改原课表")
                await { enabled("登录学校平台") }
                assertEquals("原来课程", store.load().courses.single().name)
                scenario.onActivity { deliverRestore(it, backup) }
                await { enabled("确认保存到本机") }
                tap("确认保存到本机"); await { enabled("确认替换") }; tap("确认替换")
                await { store.load().courses.single().name == "离线新课程" }
                assertEquals("原来课程", store.previousImport()!!.courses.single().name)
                scenario.recreate()
                await { enabled("恢复上次替换前的课表") }
                assertFalse(hasWebView())
                tap("恢复上次替换前的课表"); await { enabled("确认保存到本机") }
                tap("确认保存到本机"); await { enabled("确认替换") }; tap("确认替换")
                await { store.load().courses.single().name == "原来课程" }
            }
        } finally {
            if (original == null) local.delete() else local.writeBytes(original)
            if (previous == null) undo.delete() else undo.writeBytes(previous)
            backup.delete()
        }
    }

    @Test fun corruptBackupShowsRecoverableErrorAndKeepsExistingData() {
        val file = File(context.cacheDir, "schedule-bad-fixture.json")
        val store = ScheduleStore(context); val before = store.load()
        try {
            file.writeText("broken")
            launchTransfer().use { scenario ->
                await { enabled("从备份恢复") }
                scenario.onActivity { deliverRestore(it, file) }
                await { textContains("备份无法读取") }
                assertEquals(before, store.load())
                assertTrue(enabled("从备份恢复"))
                assertFalse(hasWebView())
            }
        } finally { file.delete() }
    }
    @Test fun restoreResultWhileInitialDiskReadIsBlockedIsNotSilentlyDropped() {
        val store = ScheduleStore(context)
        val lock = requireNotNull(ScheduleStore::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(store))
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = Thread { synchronized(lock) { held.countDown(); release.await(15, TimeUnit.SECONDS) } }
        val backup = File(context.cacheDir, "schedule-queued-fixture.json")
        backup.outputStream().use { ScheduleBackup.write(it, timetable("排队恢复")) }
        blocker.start(); assertTrue(held.await(2, TimeUnit.SECONDS))
        try {
            launchTransfer().use { scenario ->
                scenario.onActivity { deliverRestore(it, backup) }
                release.countDown()
                await { enabled("确认保存到本机") }
                assertTrue(textContains("排队恢复"))
                tap("取消，不修改原课表")
            }
        } finally { release.countDown(); blocker.join(2000); backup.delete() }
    }
    @Test fun schoolWebViewBlocksThirdPartyResourcesWithoutGrantingNativeAccess() {
        launchTransfer().use { scenario ->
            await { enabled("登录学校平台") }; tap("登录学校平台")
            await { hasWebView() }
            scenario.onActivity {
                val view = nodes().filterIsInstance<android.webkit.WebView>().single()
                view.stopLoading()
                val request = object : android.webkit.WebResourceRequest {
                    override fun getUrl() = Uri.parse("https://untrusted.invalid/script.js")
                    override fun isForMainFrame() = false
                    override fun isRedirect() = false
                    override fun hasGesture() = false
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders() = emptyMap<String, String>()
                }
                val response = view.webViewClient.shouldInterceptRequest(view, request)
                assertNotNull("Third-party resources must be blocked", response)
                assertEquals(403, response!!.statusCode)
            }
        }
    }
    private fun deliverRestore(activity: ScheduleTransferActivity, file: File) {
        ScheduleTransferActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(activity, 202, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val root = WindowInspector.getGlobalWindowViews().first { it.hasWindowFocus() && it.isShown }
            root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            root.invalidate()
        }
        assertTrue("Screenshot frame was not committed", committed.await(4, TimeUnit.SECONDS))
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        try { File(context.getExternalFilesDir(null), name).outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun launchTransfer(): ActivityScenario<ScheduleTransferActivity> {
        val scenario = ActivityScenario.launch(ScheduleTransferActivity::class.java)
        val orientation = InstrumentationRegistry.getArguments().getString("transferOrientation")
        if (orientation != null) {
            scenario.onActivity { it.requestedOrientation = if (orientation == "landscape")
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await {
                var correct = false
                scenario.onActivity { correct = if (orientation == "landscape") it.window.decorView.width > it.window.decorView.height
                    else it.window.decorView.height > it.window.decorView.width }
                correct
            }
        }
        return scenario
    }
    private fun nodes(): List<View> = WindowInspector.getGlobalWindowViews().flatMap { root ->
        fun walk(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { walk(view.getChildAt(it)) } else emptyList()
        walk(root)
    }
    private fun enabled(label: String): Boolean {
        var found = false
        instrumentation.runOnMainSync { found = nodes().any { it is Button && it.text.toString() == label && it.isEnabled && it.isShown } }
        return found
    }
    private fun textContains(value: String): Boolean {
        var found = false
        instrumentation.runOnMainSync { found = nodes().any { it is TextView && it.text.contains(value) } }
        return found
    }
    private fun hasWebView(): Boolean {
        var found = false
        instrumentation.runOnMainSync { found = nodes().any { it is android.webkit.WebView } }
        return found
    }
    private fun tap(label: String) = instrumentation.runOnMainSync {
        val button = nodes().filterIsInstance<Button>().firstOrNull { it.text.toString() == label && it.isEnabled && it.isShown }
        assertNotNull("Button missing: $label", button); assertTrue(button!!.performClick())
    }
    private fun await(check: () -> Boolean) {
        val limit = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < limit) { if (check()) return; SystemClock.sleep(40) }
        assertTrue("Timed out waiting for timetable transfer", check())
    }
}
