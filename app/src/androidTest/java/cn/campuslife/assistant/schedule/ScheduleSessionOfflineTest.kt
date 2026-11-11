package cn.campuslife.assistant.schedule

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Synthetic data only; these tests never open the school site. Run on a disposable device. */
class ScheduleSessionOfflineTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun timetable(name: String) = ScheduleData(Semester(LocalDate.now().with(DayOfWeek.MONDAY), 4)).add(Course(
        "00000000-0000-0000-0000-000000000111", name, "", "", 1, 1, 2, 1, 4, WeekPattern.EVERY, null))

    @Test fun schoolPreviewCancelThenConfirmUpdatesOnlyFutureAndRecordsTime() {
        val store=ScheduleStore(context);val before=store.loadSnapshot()
        val candidate=if(before.data.semester==null)timetable("离线学校课程") else before.data.copy(courses=before.data.courses.map {it.copy(classroom="离线更新教室")})
        context.getSharedPreferences("schedule-school-import-history",0).edit().clear().commit()
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use {scenario->
            await {enabled("登录学校平台")}
            fun preview()=scenario.onActivity {activity->
                val session=activity.javaClass.getDeclaredField("session").apply {isAccessible=true}.get(activity)
                session.javaClass.getDeclaredField("baselineSnapshot").apply {isAccessible=true}.set(session,before)
                activity.javaClass.getDeclaredMethod("preview",ScheduleData::class.java,String::class.java,List::class.java,ScheduleData::class.java,Boolean::class.javaPrimitiveType).apply {isAccessible=true}.invoke(activity,candidate,"离线学期",emptyList<String>(),before.data,true)
            }
            val plan=SchoolUpdateCheck.preview(before,candidate,LocalDate.now())
            preview();await {enabled("返回")};assertFalse(enabled("确认保存到本机"))
            tap("返回");await {enabled("登录学校平台")};assertEquals(before,store.loadSnapshot())
            if(plan.plan.data!=before.data){
                preview();await {enabled("更新课表")};tap("更新课表");await {enabled("确认更新")};tap("确认更新")
                await {store.load()==plan.plan.data};assertEquals(before.data,store.previousImport())
            }
        }
    }

    @Test fun normalExitKeepsWebDataAndExplicitLogoutClearsItWithoutTouchingSchedule() {
        val before = ScheduleStore(context).load()
        val cookie = CookieManager.getInstance()
        val cookieDone = CountDownLatch(1)
        instrumentation.runOnMainSync { cookie.setCookie("https://m.fjpit.com/", "offline_session=fixture; Max-Age=3600; Path=/; Secure") { cookieDone.countDown() } }
        assertTrue(cookieDone.await(5, TimeUnit.SECONDS))
        assertEquals("\"synthetic\"", webValue("localStorage.setItem('offline_fixture','synthetic'); return localStorage.getItem('offline_fixture');"))
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use { scenario ->
            await { enabled("登录学校平台") }
            scenario.onActivity { activity ->
                val session = activity.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(activity)
                val field = session.javaClass.getDeclaredField("authorization").apply { isAccessible = true }
                field.set(session, "Bearer synthetic_memory_only")
                activity.javaClass.getDeclaredMethod("home").apply { isAccessible = true }.invoke(activity)
                assertNull(field.get(session))
            }
            assertTrue(cookie.getCookie("https://m.fjpit.com/").orEmpty().contains("offline_session=fixture"))
            assertEquals("\"synthetic\"", webValue("return localStorage.getItem('offline_fixture');"))
            tap("退出学校登录"); await { enabled("清除并退出") }; tap("清除并退出")
            await { enabled("登录学校平台") && textContains("已清除本应用网页") }
            assertFalse(cookie.getCookie("https://m.fjpit.com/").orEmpty().contains("offline_session=fixture"))
            assertEquals("null", webValue("return localStorage.getItem('offline_fixture');"))
            assertEquals(before, ScheduleStore(context).load())
        }
    }
    @Test fun loginScreenRecreationRetainsSessionButLeavingClearsMemoryOnly() {
        org.junit.Assume.assumeTrue("Device must stay offline",
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        assertEquals("\"synthetic\"", webValue("localStorage.setItem('offline_lifecycle','synthetic'); return localStorage.getItem('offline_lifecycle');"))
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use { scenario ->
            await { enabled("登录学校平台") }
            tap("登录学校平台")
            await { enabled("导入当前学期") }
            scenario.onActivity { activity ->
                val state = activity.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(activity)
                state.javaClass.getDeclaredField("autoImportAttempted").apply { isAccessible = true }.setBoolean(state, true)
                state.javaClass.getDeclaredField("authorization").apply { isAccessible = true }.set(state, "Bearer synthetic_memory_token")
                nodes().filterIsInstance<WebView>().single().stopLoading()
            }
            scenario.recreate()
            await { enabled("导入当前学期") }
            scenario.onActivity { activity ->
                nodes().filterIsInstance<WebView>().single().stopLoading()
                val state = activity.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(activity)
                val authorization = state.javaClass.getDeclaredField("authorization").apply { isAccessible = true }
                assertEquals("Bearer synthetic_memory_token", authorization.get(state))
                @Suppress("DEPRECATION") activity.onBackPressed()
                assertNull(authorization.get(state))
            }
            await { enabled("登录学校平台") }
            assertEquals("\"synthetic\"", webValue("return localStorage.getItem('offline_lifecycle');"))
        }
    }
    @Test fun oldReminderDataDoesNotAddNoisyTimetablePromptOrStartNetworkWork() {
        val prefs=context.getSharedPreferences("schedule-school-import-history",0)
        prefs.edit().putLong("last-success-ms",System.currentTimeMillis()-7L*24*60*60*1000).commit()
        val before=ScheduleStore(context).loadSnapshot()
        ActivityScenario.launch(ScheduleActivity::class.java).use {
            await {enabled("管理")};assertFalse(textContains("距上次学校导入已满7天"));assertEquals(before,ScheduleStore(context).loadSnapshot())
        }
    }
    private fun webValue(script: String): String {
        val done = CountDownLatch(1); val result = AtomicReference<String>(); var view: WebView? = null
        instrumentation.runOnMainSync {
            view = WebView(context).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String?) {
                        v.evaluateJavascript("(function(){ $script })()") { result.set(it); done.countDown() }
                    }
                }
                loadDataWithBaseURL("https://m.fjpit.com/", "<html><body>offline fixture</body></html>", "text/html", "UTF-8", null)
            }
        }
        try { assertTrue(done.await(8, TimeUnit.SECONDS)); return result.get() }
        finally { instrumentation.runOnMainSync { view?.destroy() } }
    }
    private fun nodes(): List<View> = WindowInspector.getGlobalWindowViews().flatMap { root ->
        fun walk(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList()
        walk(root)
    }
    private fun enabled(label: String): Boolean {
        var found = false; instrumentation.runOnMainSync { found = nodes().any { it is Button && it.text.toString() == label && it.isEnabled && it.isShown } }; return found
    }
    private fun textContains(value: String): Boolean {
        var found = false; instrumentation.runOnMainSync { found = nodes().any { it is TextView && it.text.contains(value) } }; return found
    }
    private fun tap(label: String) = instrumentation.runOnMainSync {
        val button = nodes().filterIsInstance<Button>().firstOrNull { it.text.toString() == label && it.isEnabled && it.isShown }
        assertNotNull("Missing: $label", button); assertTrue(button!!.performClick())
    }
    private fun await(check: () -> Boolean) {
        val limit = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < limit) { if (check()) return; SystemClock.sleep(40) }
        assertTrue("Timed out", check())
    }
}
