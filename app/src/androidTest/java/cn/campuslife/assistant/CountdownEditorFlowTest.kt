package cn.campuslife.assistant

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.DatePicker
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.TimePicker
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cn.campuslife.assistant.planner.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Isolated fixtures only; never run against a user's phone or overwrite their records. */
class CountdownEditorFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun find(view: View, match: (View) -> Boolean): View? {
        if(match(view)) return view
        if(view is ViewGroup) for(index in 0 until view.childCount) find(view.getChildAt(index),match)?.let { return it }
        return null
    }
    private fun waitFor(message: String, ready: () -> Boolean) {
        val until=System.nanoTime()+8_000_000_000L
        while(System.nanoTime()<until) { if(ready()) return; Thread.sleep(40) }
        fail(message)
    }
    private fun tag(activity: Activity, value: String): View = requireNotNull(find(activity.window.decorView) { it.tag==value }) { "Missing control $value" }
    private fun click(activity: Activity, value: String) = instrumentation.runOnMainSync {
        val control=find(activity.window.decorView) { it is TextView && it.text.toString()==value && it.isEnabled }
        assertNotNull("Missing action $value",control); control!!.performClick()
    }
    private fun dialogAction(label: String) = instrumentation.runOnMainSync {
        val action=WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull { root ->
            find(root) { it is TextView && it.text.toString()==label && it.isEnabled }
        }
        assertNotNull("Missing dialog action $label",action)
        val list=generateSequence(action!!.parent) { it.parent }.filterIsInstance<ListView>().firstOrNull()
        if(list!=null) {
            val position=list.getPositionForView(action)
            assertTrue(list.performItemClick(action,position,list.getItemIdAtPosition(position)))
        } else assertTrue(action.performClick())
    }
    private fun pickDate(activity: Activity, date: LocalDate) {
        instrumentation.runOnMainSync { tag(activity,"planner-date").performClick() }
        instrumentation.runOnMainSync {
            val picker=WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { find(it) { view -> view is DatePicker } } as? DatePicker
            assertNotNull("Date must open a picker instead of a text keyboard",picker)
            picker!!.updateDate(date.year,date.monthValue-1,date.dayOfMonth)
        }
        instrumentation.runOnMainSync {
            val positive=WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull { it.findViewById<View>(android.R.id.button1) }
            assertNotNull(positive); positive!!.performClick()
        }
    }
    private fun awaitUi(activity: Activity, action: String) = waitFor("Screen not ready: $action") {
        var result=false
        instrumentation.runOnMainSync { result=find(activity.window.decorView) { it is TextView && it.text.toString()==action && it.isEnabled }!=null }
        result
    }
    private fun recreate(activity: Activity): Activity {
        instrumentation.runOnMainSync { activity.recreate() }
        var next: Activity?=null
        waitFor("Draft did not survive recreation") {
            instrumentation.runOnMainSync { next=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull { it is PlannerActivity && it!==activity } }
            next!=null
        }
        return requireNotNull(next)
    }
    private fun fixture(initial: PlannerState, block: (Activity,PlannerStore) -> Activity) {
        assumeTrue(Build.VERSION.SDK_INT>=29 && Build.HARDWARE in setOf("ranchu","goldfish"))
        val context=instrumentation.targetContext
        val file=File(context.filesDir,"planner-v1.json")
        val backup=if(file.exists()) file.readBytes() else null
        var activity: Activity?=null
        try {
            val store=PlannerStore(context); store.write(initial)
            activity=instrumentation.startActivitySync(Intent(context,PlannerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitUi(activity,"新增倒计时")
            activity=block(activity,store)
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlannerActivity>().forEach { it.finish() }
                activity?.takeUnless { it.isFinishing }?.finish()
            }
            if(backup==null) file.delete() else file.writeBytes(backup)
        }
    }
    @Test fun presetAndCalendarCreateAnEventWithoutTypingAndSurviveRotation() = fixture(PlannerState()) { original,store ->
        var activity=original
        click(activity,"新增倒计时")
        instrumentation.runOnMainSync { tag(activity,"planner-preset").performClick() }
        dialogAction("体测")
        pickDate(activity,LocalDate.of(2032,2,29))
        activity=recreate(activity); awaitUi(activity,"保存")
        instrumentation.runOnMainSync {
            assertFalse("Date should not require manual typing",tag(activity,"planner-date") is EditText)
            assertTrue((tag(activity,"planner-date") as TextView).text.contains("2032年2月29日"))
        }
        click(activity,"保存")
        waitFor("Preset event not saved") { store.read().countdowns.singleOrNull()?.let { it.title=="体测" && it.date==LocalDate.of(2032,2,29) && !it.yearly }==true }
        awaitUi(activity,"新增倒计时")
        instrumentation.runOnMainSync {
            assertNull("List should not contain repeated edit/delete actions",find(activity.window.decorView) { it is TextView && it.text.toString() in setOf("编辑","删除") })
        }
        activity
    }
    @Test fun oldCustomTitleReturnsAfterTryingPresetAndCancelDoesNotWrite() {
        val item=CountdownItem(PlannerRules.newId(),"旧的自定义事项",LocalDate.of(2030,1,31))
        val legacy=TodoItem(PlannerRules.newId(),"Preserved retired todo")
        fixture(PlannerState(todos=listOf(legacy),countdowns=listOf(item))) { activity,store ->
            val before=File(instrumentation.targetContext.filesDir,"planner-v1.json").readBytes()
            instrumentation.runOnMainSync { tag(activity,"countdown-${item.id}").performClick() }
            awaitUi(activity,"保存")
            instrumentation.runOnMainSync { tag(activity,"planner-preset").performClick() }; dialogAction("生日")
            instrumentation.runOnMainSync { tag(activity,"planner-preset").performClick() }; dialogAction("自定义")
            instrumentation.runOnMainSync { assertEquals("旧的自定义事项",(tag(activity,"planner-title") as EditText).text.toString()) }
            pickDate(activity,LocalDate.of(2031,4,5)); click(activity,"取消")
            awaitUi(activity,"新增倒计时")
            assertArrayEquals(before,File(instrumentation.targetContext.filesDir,"planner-v1.json").readBytes())
            assertEquals(listOf(legacy),store.read().todos)
            activity
        }
    }
    @Test fun birthdayPresetRepeatsYearlyAndCustomTitleCanBeSaved() = fixture(PlannerState()) { activity,store ->
        click(activity,"新增倒计时")
        instrumentation.runOnMainSync { tag(activity,"planner-preset").performClick() }; dialogAction("生日")
        pickDate(activity,LocalDate.of(2030,6,12)); click(activity,"保存")
        waitFor("Birthday recurrence missing") { store.read().countdowns.singleOrNull()?.let { it.title=="生日" && it.yearly }==true }
        awaitUi(activity,"新增倒计时"); click(activity,"新增倒计时")
        instrumentation.runOnMainSync { tag(activity,"planner-preset").performClick() }; dialogAction("自定义")
        instrumentation.runOnMainSync { (tag(activity,"planner-title") as EditText).setText("社团报名") }
        pickDate(activity,LocalDate.of(2030,9,1)); click(activity,"保存")
        waitFor("Custom title missing") { store.read().countdowns.any { it.title=="社团报名" && !it.yearly } }
        activity
    }
    @Test fun existingReminderUsesTimePickerAndRetainsTimeAfterRotation() {
        val item=CountdownItem(PlannerRules.newId(),"考试",LocalDate.of(2032,6,1),reminder=true)
        fixture(PlannerState(countdowns=listOf(item))) { original,store ->
            var activity=original
            instrumentation.runOnMainSync { tag(activity,"countdown-${item.id}").performClick() }
            instrumentation.runOnMainSync { tag(activity,"planner-time").performClick() }
            instrumentation.runOnMainSync {
                val time=WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { find(it) { view -> view is TimePicker } } as? TimePicker
                assertNotNull("Reminder time must open a picker",time)
                time!!.hour=7; time.minute=45
                WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOf { it.findViewById<View>(android.R.id.button1) }.performClick()
            }
            activity=recreate(activity); awaitUi(activity,"保存")
            instrumentation.runOnMainSync { assertTrue((tag(activity,"planner-time") as TextView).text.contains("07:45")) }
            click(activity,"保存")
            waitFor("Reminder time did not persist") { store.read().countdowns.singleOrNull()?.let { it.id==item.id && it.reminderTime==java.time.LocalTime.of(7,45) && it.reminder }==true }
            activity
        }
    }
}
