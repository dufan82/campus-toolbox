package cn.campuslife.assistant

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.DatePicker
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cn.campuslife.assistant.planner.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Run only on a dedicated emulator. Fixture data is restored even after a failure. */
class PlannerFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun find(root: View, predicate: (View)->Boolean): View? {
        if (predicate(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i),predicate)?.let { return it }
        return null
    }
    private fun click(activity: Activity, text: String) = instrumentation.runOnMainSync {
        val view = find(activity.window.decorView) { it is TextView && it.text.toString()==text }
        assertNotNull(view); view!!.performClick()
    }
    private fun awaitUi(activity: Activity,text: String) {
        val stop=System.nanoTime()+8_000_000_000
        while(System.nanoTime()<stop) {
            var ready=false
            instrumentation.runOnMainSync { ready=find(activity.window.decorView) { it is TextView && it.text.toString()==text && it.isEnabled }!=null }
            if(ready) return
            Thread.sleep(40)
        }
        fail("Planner UI did not reach expected state")
    }
    private fun recreate(activity: Activity): Activity {
        instrumentation.runOnMainSync { activity.recreate() }
        val stop=System.nanoTime()+8_000_000_000
        while(System.nanoTime()<stop) {
            var recreated: Activity?=null
            instrumentation.runOnMainSync {
                recreated=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it is PlannerActivity && it!==activity }
            }
            recreated?.let { return it }
            Thread.sleep(40)
        }
        throw AssertionError("Planner Activity did not recreate")
    }
    private fun confirmDelete() {
        val stop=System.nanoTime()+5_000_000_000
        while(System.nanoTime()<stop) {
            val node=instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("删除")?.lastOrNull { it.isClickable }
            if(node!=null && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return
            Thread.sleep(40)
        }
        fail("Delete confirmation not found")
    }
    private fun input(activity: Activity, tag: String, value: String) {
        if(tag=="planner-date") {
            val date=LocalDate.parse(value)
            instrumentation.runOnMainSync { find(activity.window.decorView) { it.tag==tag }!!.performClick() }
            instrumentation.runOnMainSync {
                val picker=WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { find(it) { view -> view is DatePicker } } as DatePicker
                picker.updateDate(date.year,date.monthValue-1,date.dayOfMonth)
                WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOf { it.findViewById<View>(android.R.id.button1) }.performClick()
            }
        } else {
            var custom=false
            instrumentation.runOnMainSync { custom=find(activity.window.decorView) { it.tag==tag }!=null }
            if(!custom) {
                instrumentation.runOnMainSync { find(activity.window.decorView) { it.tag=="planner-preset" }!!.performClick() }
                instrumentation.runOnMainSync {
                    val item=WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOf { root ->
                        find(root) { it is TextView && it.text.toString()=="自定义" }
                    }
                    val list=generateSequence(item.parent) { it.parent }.filterIsInstance<ListView>().first()
                    val position=list.getPositionForView(item)
                    assertTrue(list.performItemClick(item,position,list.getItemIdAtPosition(position)))
                }
            }
            instrumentation.runOnMainSync { (find(activity.window.decorView) { it.tag==tag } as EditText).setText(value) }
        }
    }
    private fun edit(activity: Activity,id: String) = instrumentation.runOnMainSync {
        find(activity.window.decorView) { it.tag=="countdown-$id" }!!.performClick()
    }
    private fun awaitState(predicate: (PlannerState)->Boolean) {
        val stop = System.nanoTime()+5_000_000_000
        while(System.nanoTime()<stop) {
            if (predicate(PlannerStore(instrumentation.targetContext).read())) return
            Thread.sleep(40)
        }
        fail("Planner persistence did not reach expected state")
    }
    @Test fun legacyTodoTabAndDraftCannotReviveTodoUiOrChangeSavedRecords() {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish") || android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("Emulator"))
        val context = instrumentation.targetContext
        val file = File(context.filesDir,"planner-v1.json")
        val backup = if(file.exists()) file.readBytes() else null
        var activity: Activity? = null
        try {
            val legacy=PlannerState(todos=listOf(TodoItem(PlannerRules.newId(),"Legacy todo fixture")))
            PlannerStore(context).write(legacy)
            val bytes=file.readBytes()
            activity = instrumentation.startActivitySync(Intent(context,PlannerActivity::class.java).putExtra("tab",0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitUi(activity,"新增倒计时")
            activity=recreate(activity)
            awaitUi(activity,"新增倒计时")
            instrumentation.runOnMainSync {
                val root=requireNotNull(activity).window.decorView
                assertNull(find(root) { it is TextView && it.text.toString() in setOf("待办","新增待办","Legacy todo fixture") })
                val old=android.os.Bundle().apply {
                    putInt("planner.draft.kind",0); putString("planner.draft.id",PlannerRules.newId())
                    putString("planner.draft.title","Retired draft")
                }
                assertNull(PlannerDraft.restore(old))
            }
            assertEquals(legacy,PlannerStore(context).read())
            assertArrayEquals(bytes,file.readBytes())
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            if(backup==null) file.delete() else file.writeBytes(backup)
        }
    }
    @Test fun countdownCrudAndEditDraftSurviveRecreation() {
        assumeTrue(android.os.Build.VERSION.SDK_INT>=29 && android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val context=instrumentation.targetContext
        val file=File(context.filesDir,"planner-v1.json")
        val backup=if(file.exists()) file.readBytes() else null
        var activity: Activity?=null
        try {
            val legacy=TodoItem(PlannerRules.newId(),"Legacy todo fixture")
            PlannerStore(context).write(PlannerState(todos=listOf(legacy)))
            activity=instrumentation.startActivitySync(Intent(context,PlannerActivity::class.java).putExtra("tab",1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitUi(activity,"新增倒计时"); click(activity,"新增倒计时")
            input(activity,"planner-title","Fixture countdown"); input(activity,"planner-date","2030-01-31")
            click(activity,"保存"); awaitState { it.countdowns.size==1 }; awaitUi(activity,"新增倒计时")
            val id=PlannerStore(context).read().countdowns.single().id
            edit(activity,id); input(activity,"planner-title","Fixture countdown edited"); input(activity,"planner-date","2020-02-29")
            activity=recreate(activity); awaitUi(activity,"保存")
            instrumentation.runOnMainSync {
                assertTrue((find(requireNotNull(activity).window.decorView) { it.tag=="planner-date" } as TextView).text.contains("2020年2月29日"))
            }
            click(activity,"保存"); awaitState { it.countdowns.singleOrNull()?.title=="Fixture countdown edited" }; awaitUi(activity,"新增倒计时")
            assertEquals(id,PlannerStore(context).read().countdowns.single().id)
            instrumentation.runOnMainSync { requireNotNull(activity).finish() }
            activity=instrumentation.startActivitySync(Intent(context,PlannerActivity::class.java).putExtra("tab",1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitUi(activity,"新增倒计时")
            instrumentation.runOnMainSync { assertNotNull(find(requireNotNull(activity).window.decorView) { it is TextView && it.text.startsWith("已过 ") }) }
            edit(activity,id); click(activity,"删除这条记录"); confirmDelete(); awaitState { it.countdowns.isEmpty() }
            assertEquals(listOf(legacy),PlannerStore(context).read().todos)
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            if(backup==null) file.delete() else file.writeBytes(backup)
        }
    }
}
