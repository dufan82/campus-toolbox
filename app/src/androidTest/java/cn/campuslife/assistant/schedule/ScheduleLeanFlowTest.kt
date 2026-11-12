package cn.campuslife.assistant.schedule

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Navigation and read-only checks. Never opens school login or sends any network request. */
@SdkSuppress(minSdkVersion = 29)
class ScheduleLeanFlowTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext

    @Test fun managementHasExactlyTwoActionsAndDoesNotWrite() {
        val before = ScheduleStore(context).loadSnapshot()
        ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
            await { enabled("管理") }; tap("管理")
            await { text("课程表管理") }
            inst.runOnMainSync {
                val list = nodes().filterIsInstance<ListView>().single()
                assertEquals(listOf("检查更新", "备份与恢复"), (0 until list.adapter.count).map { list.adapter.getItem(it).toString() })
            }
            capture("management")
            tap("取消")
            scenario.recreate(); await { enabled("管理") }
            assertEquals(before, ScheduleStore(context).loadSnapshot())
        }
    }
    @Test fun addLivesInHeaderAndRemainsAccessibleAfterRecreation() {
        val before = ScheduleStore(context).loadSnapshot()
        ActivityScenario.launch(ScheduleActivity::class.java).use { scenario ->
            await { enabled("管理") }
            inst.runOnMainSync {
                val add = nodes().singleOrNull { it.tag == "schedule.add" }
                assertNotNull("Header + must exist", add)
                assertEquals("新增课程", add!!.contentDescription.toString())
                assertTrue(add.isClickable && add.isEnabled)
                assertTrue(add.minimumWidth >= (48 * context.resources.displayMetrics.density).toInt())
            }
            scenario.recreate(); await { nodesOnMain().any { it.tag == "schedule.add" } }
            assertEquals(before, ScheduleStore(context).loadSnapshot())
        }
    }
    @Test fun checkPageHasSecondaryDailySwitchButNoBackupOrReplacement() {
        val before = ScheduleStore(context).loadSnapshot()
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use { scenario ->
            await { enabled("登录学校平台") }
            assertTrue(text("检查更新"))
            assertTrue(nodesOnMain().any { it is Switch && it.tag == "school_sync_switch" })
            assertFalse(text("导出课表备份")); assertFalse(text("整表替换"))
            assertFalse(nodesOnMain().any { it is android.webkit.WebView })
            scenario.recreate(); await { enabled("登录学校平台") }
            assertEquals(before, ScheduleStore(context).loadSnapshot())
        }
    }
    @Test fun backupPageIsIndependentAndNeverCreatesWebview() {
        val before = ScheduleStore(context).loadSnapshot()
        val intent=Intent(context,ScheduleTransferActivity::class.java).putExtra("schedule.backup",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ScheduleTransferActivity>(intent).use { scenario ->
            await { enabled("从备份恢复") }
            assertTrue(text("备份与恢复")); assertTrue(text("导出课表备份")); assertTrue(text("恢复上次更新前课表"))
            assertFalse(text("登录学校平台")); assertFalse(nodesOnMain().any { it is android.webkit.WebView })
            capture("backup")
            scenario.recreate(); await { enabled("从备份恢复") }
            assertTrue(text("备份与恢复")); assertEquals(before,ScheduleStore(context).loadSnapshot())
        }
    }
    @Test fun myPageOpensLowFrequencySettingsWithoutChangingData() {
        val before=ScheduleStore(context).loadSnapshot()
        ActivityScenario.launch(cn.campuslife.assistant.ui.SettingsActivity::class.java).use {
            await {nodesOnMain().any {it.tag=="settings_schedule"}}
            inst.runOnMainSync {assertTrue(nodes().single {it.tag=="settings_schedule"}.performClick())}
            await {nodesOnMain().filterIsInstance<ListView>().any {list -> list.isShown && (0 until list.adapter.count).map {list.adapter.getItem(it).toString()}==listOf("学期设置","节次设置","全部课程")}}
            inst.runOnMainSync {
                val list=nodes().filterIsInstance<ListView>().single()
                assertEquals(listOf("学期设置","节次设置","全部课程"),(0 until list.adapter.count).map {list.adapter.getItem(it).toString()})
            }
            capture("settings");tap("取消");assertEquals(before,ScheduleStore(context).loadSnapshot())
            inst.runOnMainSync { (nodes().firstOrNull {it.tag=="schedule.manage"}?.context as? android.app.Activity)?.finish() }
        }
    }
    @Test fun checkingWithNoSummaryShowsRealStateAndRefreshesAfterCompletion() {
        val prefs=DailySyncPreferences(context);val previous=prefs.read()
        try {
            prefs.write(DailySyncState(enabled=true,lastAttempt=System.currentTimeMillis(),result=SyncResult.CHECKING))
            ActivityScenario.launch(ScheduleTransferActivity::class.java).use {scenario->
                await {text("正在检查…")};assertFalse(text("尚未执行自动检查"))
                prefs.write(prefs.read().copy(result=SyncResult.NO_CHANGE,lastSuccess=System.currentTimeMillis()))
                await {text("已完成检查") && text("学校课表：未发现变化") && text("本机课表：无需更新")};capture("check")
                scenario.recreate();await {text("已完成检查") && text("学校课表：未发现变化") && text("本机课表：无需更新")}
            }
        } finally {prefs.write(previous)}
    }
    private fun capture(name:String) {
        inst.waitForIdleSync()
        val done=java.util.concurrent.CountDownLatch(1)
        inst.runOnMainSync {
            val root=WindowInspector.getGlobalWindowViews().first {it.hasWindowFocus() && it.isShown}
            root.viewTreeObserver.registerFrameCommitCallback {done.countDown()};root.invalidate()
        }
        assertTrue(done.await(4,java.util.concurrent.TimeUnit.SECONDS))
        SystemClock.sleep(350) // Include the system dialog enter transition in the screenshot.
        val bitmap=inst.uiAutomation.takeScreenshot() ?: error("No screenshot")
        val output=java.io.File(context.getExternalFilesDir(null),"lean-screens").apply {mkdirs()}
        java.io.File(output,"$name.png").outputStream().use {assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
    }
    private fun nodes(): List<View> = WindowInspector.getGlobalWindowViews().flatMap { root ->
        fun walk(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList()
        walk(root)
    }
    private fun nodesOnMain():List<View> { var result=emptyList<View>();inst.runOnMainSync {result=nodes()};return result }
    private fun text(s:String)=nodesOnMain().any {it is TextView && it.text.toString()==s}
    private fun enabled(s:String)=nodesOnMain().any {it is Button && it.text.toString()==s && it.isEnabled}
    private fun tap(s:String)=inst.runOnMainSync {assertTrue(nodes().filterIsInstance<Button>().first {it.text.toString()==s}.performClick())}
    private fun await(check:()->Boolean) {val end=SystemClock.uptimeMillis()+10000;while(SystemClock.uptimeMillis()<end){if(check())return;SystemClock.sleep(40)};assertTrue("Timed out",check())}
}
