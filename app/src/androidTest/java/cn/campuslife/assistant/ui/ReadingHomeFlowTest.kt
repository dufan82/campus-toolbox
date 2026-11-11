package cn.campuslife.assistant.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Dedicated offline emulator. Never clicks express routes, login or any school URL. */
@SdkSuppress(minSdkVersion=29)
class ReadingHomeFlowTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))} else emptyList()
    private fun nodes():List<View> = WindowInspector.getGlobalWindowViews().filter {it.isShown}.flatMap(::all)
    private fun onMain(check:()->Boolean):Boolean {var value=false;inst.runOnMainSync {value=check()};return value}
    private fun await(check:()->Boolean) {val end=SystemClock.uptimeMillis()+8000;while(SystemClock.uptimeMillis()<end){if(onMain(check))return;SystemClock.sleep(40)};assertTrue("Timed out",onMain(check))}
    private fun tagged(tag:String):View=nodes().first {it.tag==tag}
    private fun tap(tag:String)=inst.runOnMainSync {assertTrue(tagged(tag).performClick())}
    private fun label(tag:String):String {var result="";inst.runOnMainSync {result=(tagged(tag) as TextView).text.toString()};return result}
    private fun capture(name:String) {
        inst.waitForIdleSync();SystemClock.sleep(400)
        val dir=File(context.getExternalFilesDir(null),"reading-screens").apply {mkdirs()}
        val bmp=inst.uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(dir,"$name.png").outputStream().use {assertTrue(bmp.compress(Bitmap.CompressFormat.PNG,100,it))};bmp.recycle()
    }
    @Test fun compactPoemHeaderReplacesBrandButKeepsExpressFixed() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            val phase=InstrumentationRegistry.getArguments().getString("readingPhase")
            if(phase=="before") {capture("home-before")} else {await {nodes().any {it.tag=="home_poem_text" && (it as TextView).text.isNotBlank()}};capture("home-after")}
            scenario.onActivity {activity->
                val root=activity.window.decorView
                assertNotNull("Compact poem header",root.findViewWithTag<View>("home_poem"))
                assertNull(root.findViewWithTag<View>("brand_name"));assertNull(root.findViewWithTag<View>("brand_tagline"))
                val quote=root.findViewWithTag<TextView>("home_poem_text")
                val credit=root.findViewWithTag<TextView>("home_poem_credit")
                assertEquals(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,14.5f,context.resources.displayMetrics),quote.textSize,.2f)
                assertEquals(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,10.5f,context.resources.displayMetrics),credit.textSize,.2f)
                val quoteLocation=IntArray(2);val creditLocation=IntArray(2)
                quote.getLocationOnScreen(quoteLocation);credit.getLocationOnScreen(creditLocation)
                assertTrue(creditLocation[1]>=quoteLocation[1]+quote.height)
                assertEquals((credit.parent as View).width,credit.width)
                assertEquals(Gravity.END,credit.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
                assertFalse(quote.text.contains('“'));assertFalse(quote.text.contains('”'))
                assertTrue(all(root).filterIsInstance<TextView>().none {it.text.toString() in listOf("不用注册，也能使用本地工具","每日一句","今日诗句")})
                val shortcuts=all(root).filter {it.tag?.toString()?.startsWith("shortcut_")==true}
                assertEquals(3,shortcuts.size)
                shortcuts.forEach {view->val rect=Rect();assertTrue(view.getGlobalVisibleRect(rect));assertEquals(view.height,rect.height());assertTrue(view.isClickable)}
            }
        }
    }
    @Test fun bottomSheetCopiesChangesAndSharesStateWithRecreatedHome() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            await {nodes().any {it.tag=="home_poem_text" && (it as TextView).text.isNotBlank()}}
            val before=label("home_poem_text")
            tap("home_poem_content");await {nodes().any {it.tag=="poem_next"}}
            assertEquals(before,label("poem_sheet_text"));capture("poem-sheet")
            tap("poem_next");await {(tagged("home_poem_text") as TextView).text.toString()!=before}
            val next=label("home_poem_text")
            assertEquals(next,label("poem_sheet_text"));assertEquals(label("home_poem_credit"),label("poem_sheet_credit"))
            tap("poem_copy")
            inst.runOnMainSync {assertEquals(next+"\n"+(tagged("home_poem_credit") as TextView).text,(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString())}
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            await {nodes().none {it.tag=="poem_next"}}
            scenario.recreate();await {nodes().any {it.tag=="home_poem_text" && (it as TextView).text.toString()==next}}
            assertEquals(next,label("home_poem_text"))
        }
    }
    @Test fun reopeningSheetDuringInFlightChangeKeepsDisplayedAndCopiedPoemConsistent() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            await {nodes().any {it.tag=="home_poem_text" && (it as TextView).text.isNotBlank()}}
            tap("home_poem_content");await {nodes().any {it.tag=="poem_next"}}
            val before=label("poem_sheet_text")
            val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
            val worker=MainActivity::class.java.getDeclaredField("homePreviewIo").apply {isAccessible=true}.get(null) as java.util.concurrent.ExecutorService
            worker.execute {entered.countDown();release.await(8,java.util.concurrent.TimeUnit.SECONDS)}
            assertTrue(entered.await(4,java.util.concurrent.TimeUnit.SECONDS))
            try {
                tap("poem_next")
                scenario.onActivity {activity->
                    (MainActivity::class.java.getDeclaredField("poemDialog").apply {isAccessible=true}.get(activity) as android.app.Dialog).dismiss()
                    activity.window.decorView.findViewWithTag<View>("home_poem_content").performClick()
                }
                assertEquals(before,label("poem_sheet_text"));release.countDown()
                await {(tagged("home_poem_text") as TextView).text.toString()!=before}
                val next=label("home_poem_text");assertEquals(next,label("poem_sheet_text"))
                tap("poem_copy")
                inst.runOnMainSync {assertEquals(next+"\n"+(tagged("poem_sheet_credit") as TextView).text,(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString())}
            } finally {release.countDown()}
        }
    }

}
