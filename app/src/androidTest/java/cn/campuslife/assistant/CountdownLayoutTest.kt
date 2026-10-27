package cn.campuslife.assistant

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.planner.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class CountdownLayoutTest {
    private fun find(view: View, predicate: (View) -> Boolean): View? {
        if(predicate(view)) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) find(view.getChildAt(index),predicate)?.let { return it }
        return null
    }
    @Test fun listAndEditorRemainUsableInBothOrientations() {
        assumeTrue(Build.HARDWARE in setOf("ranchu","goldfish"))
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val files=listOf("",".bak",".new").map { File(context.filesDir,"planner-v1.json$it") }
        val backup=files.associateWith { if(it.isFile) it.readBytes() else null }
        val now=LocalDate.now()
        val folder=File(context.getExternalFilesDir("verification"),"countdown172-layout").apply { mkdirs() }
        fun capture(name: String) {
            if(InstrumentationRegistry.getArguments().getString("capture172")!="true") return
            instrumentation.waitForIdleSync()
            // Idle callbacks can precede the compositor presenting the new view hierarchy.
            val drawn=java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync {
                val window=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<PlannerActivity>().single().window.decorView
                window.postOnAnimation { window.postOnAnimation { drawn.countDown() } }
            }
            assertTrue(drawn.await(3,java.util.concurrent.TimeUnit.SECONDS))
            android.os.SystemClock.sleep(120)
            val screenshot=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(folder,name).outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,it)) } }
            finally { screenshot.recycle() }
        }
        try {
            PlannerStore(context).write(PlannerState(countdowns=listOf(
                CountdownItem("90000000-0000-4000-8000-000000000011","体测",now.plusDays(12)),
                CountdownItem("90000000-0000-4000-8000-000000000012","生日",now.plusDays(25),yearly=true),
                CountdownItem("90000000-0000-4000-8000-000000000013","和朋友一起完成校园摄影计划的纪念日",now.minusDays(21)))))
            for(landscape in listOf(false,true)) {
                ActivityScenario.launch<PlannerActivity>(Intent(context,PlannerActivity::class.java)).use { scenario ->
                    scenario.onActivity { it.requestedOrientation=if(landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                    var ready=false
                    val until=System.nanoTime()+8_000_000_000L
                    while(!ready && System.nanoTime()<until) {
                        instrumentation.waitForIdleSync()
                        scenario.onActivity {
                            ready=it.resources.configuration.orientation==(if(landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT) &&
                                find(it.window.decorView) { view -> view is Button && view.text.toString()=="新增倒计时" && view.isEnabled }!=null
                        }
                        if(!ready) Thread.sleep(40)
                    }
                    assertTrue("Countdown screen did not settle",ready)
                    val orientation=if(landscape) "landscape" else "portrait"
                    var scale=""
                    scenario.onActivity { activity ->
                        scale=activity.resources.configuration.fontScale.toString()
                        val add=requireNotNull(find(activity.window.decorView) { it is Button && it.text.toString()=="新增倒计时" })
                        val rect=Rect(); assertTrue(add.getGlobalVisibleRect(rect))
                        assertEquals("Add button must stay above system bars",add.height,rect.height())
                        assertEquals(add.width,rect.width())
                    }
                    capture("list-$orientation-font$scale.png")
                    scenario.onActivity { activity -> find(activity.window.decorView) { it is Button && it.text.toString()=="新增倒计时" }!!.performClick() }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        for(tag in listOf("planner-preset","planner-date")) {
                            val control=requireNotNull(find(activity.window.decorView) { it.tag==tag })
                            assertTrue(control is Button)
                            assertTrue(control.height>=48*activity.resources.displayMetrics.density)
                            assertTrue(control.width<=activity.window.decorView.width)
                        }
                    }
                    capture("editor-$orientation-font$scale.png")
                    scenario.onActivity { activity ->
                        val save=requireNotNull(find(activity.window.decorView) { it is Button && it.text.toString()=="保存" })
                        val scroll=generateSequence(save.parent) { it.parent }.filterIsInstance<ScrollView>().first()
                        scroll.fullScroll(View.FOCUS_DOWN)
                    }
                    var actionsVisible=false
                    val scrollUntil=System.nanoTime()+4_000_000_000L
                    while(!actionsVisible && System.nanoTime()<scrollUntil) {
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { activity ->
                            actionsVisible=listOf("保存","取消").all { label ->
                                val button=find(activity.window.decorView) { it is Button && it.text.toString()==label } ?: return@all false
                                val rect=Rect(); button.getGlobalVisibleRect(rect) && rect.width()==button.width && rect.height()==button.height
                            }
                        }
                        if(!actionsVisible) Thread.sleep(40)
                    }
                    scenario.onActivity { activity ->
                        for(label in listOf("保存","取消")) {
                            val button=requireNotNull(find(activity.window.decorView) { it is Button && it.text.toString()==label })
                            val rect=Rect(); assertTrue("$label must be reachable by scrolling ($orientation/font$scale)",button.getGlobalVisibleRect(rect))
                            assertEquals(button.height,rect.height()); assertEquals(button.width,rect.width())
                        }
                    }
                    capture("actions-$orientation-font$scale.png")
                }
            }
        } finally {
            backup.forEach { (file,bytes) -> if(bytes==null) file.delete() else file.writeBytes(bytes) }
        }
    }
}
