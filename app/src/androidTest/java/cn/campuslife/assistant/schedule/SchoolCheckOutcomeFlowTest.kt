package cn.campuslife.assistant.schedule

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Offline synthetic history only. Never opens school login or requests the school service. */
class SchoolCheckOutcomeFlowTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext

    @Test fun mixedResultSurvivesRecreationAndShowsAppliedCountsAndProtectedGroups()=withPreservedHistory {
        val outcome=SchoolCheckOutcome.completed(SchoolChanges(added=1,adjusted=2),1)
        val automatic=DailySyncPreferences(context)
        automatic.write(DailySyncState(enabled=false,lastAttempt=1,result=SyncResult.CONFLICT,outcome=outcome))
        assertEquals(outcome,automatic.read().outcome)
        SchoolCheckHistoryStore(context).write(SchoolCheckRecord(System.currentTimeMillis(),"已检查，课表已更新","更新后\n11月12日 · 测试课程 · 新教室",outcome))
        assertEquals(outcome,SchoolCheckHistoryStore(context).read()!!.outcome)
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use {scenario->
            await(scenario,"school_check_school","学校课表：发现差异")
            await(scenario,"school_check_local","本机课表：已更新：新增 1 门，调整 2 门，取消 0 门")
            await(scenario,"school_sync_result","已完成检查")
            scenario.onActivity {activity->
                assertTrue(activity.window.decorView.findViewWithTag<TextView>("school_check_note").text.contains("1组差异未应用"))
                assertNotNull(activity.window.decorView.findViewWithTag<View>("school_check_details"))
            }
            capture("schedule-mixed-result.png")
            scenario.recreate()
            await(scenario,"school_check_local","本机课表：已更新：新增 1 门，调整 2 门，取消 0 门")
        }
    }
    @Test fun unchangedPendingProtectedOnlyAndUncertainFailureAreDifferentAndNeverWriteSchedule()=withPreservedHistory {
        val cases=listOf(
            Triple(SchoolCheckOutcome.completed(SchoolChanges(),0),"已完成检查","本机课表：无需更新"),
            Triple(SchoolCheckOutcome.preview(SchoolChanges(adjusted=1),0,true),"已完成检查，等待确认","本机课表：待确认，尚未应用"),
            Triple(SchoolCheckOutcome.completed(SchoolChanges(),2),"已完成检查","本机课表：未应用差异，原课程已保留"),
            Triple(SchoolCheckOutcome.failed(false),"检查未完成","本机课表：更新结果待确认"))
        cases.forEach {(outcome,check,local)->
            SchoolCheckHistoryStore(context).write(SchoolCheckRecord(System.currentTimeMillis(),"测试检查记录","",outcome))
            ActivityScenario.launch(ScheduleTransferActivity::class.java).use {scenario->
                await(scenario,"school_sync_result",check);await(scenario,"school_check_local",local)
                scenario.onActivity {assertNull(it.window.decorView.findViewWithTag<View>("school_check_details"))}
            }
        }
    }
    @Test fun oldAutomaticConflictCanRecoverCountsWithoutNewOutcomeKey()=withPreservedHistory {
        context.getSharedPreferences("school-manual-check-v1",0).edit().clear().commit()
        val prefs=DailySyncPreferences(context)
        prefs.write(DailySyncState(enabled=false,lastAttempt=System.currentTimeMillis(),result=SyncResult.CONFLICT,
            summary="新增 0 门，调整 1 门，取消 0 门；2组冲突已保留"))
        assertNull(prefs.read().outcome)
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use {scenario->
            await(scenario,"school_check_local","本机课表：已更新：新增 0 门，调整 1 门，取消 0 门")
            scenario.recreate();await(scenario,"school_check_school","学校课表：发现差异")
        }
    }
    private fun withPreservedHistory(action:()->Unit) {
        val prefs=DailySyncPreferences(context);val before=prefs.read()
        val manual=context.getSharedPreferences("school-manual-check-v1",0)
        val old=manual.all
        val data=ScheduleStore(context).loadSnapshot();val backup=ScheduleStore(context).previousImport()
        try {
            prefs.write(DailySyncState(enabled=false))
            manual.edit().clear().commit();action()
            assertEquals(data,ScheduleStore(context).loadSnapshot())
            assertEquals(backup,ScheduleStore(context).previousImport())
        } finally {
            prefs.write(before)
            assertTrue(manual.edit().clear().apply {old.forEach {(k,v)->when(v){
                is String->putString(k,v);is Long->putLong(k,v);is Boolean->putBoolean(k,v);is Int->putInt(k,v)
            }}}.commit())
        }
    }
    private fun await(scenario:ActivityScenario<ScheduleTransferActivity>,tag:String,expected:String) {
        val end=SystemClock.uptimeMillis()+8000;var actual=""
        while(SystemClock.uptimeMillis()<end) {
            scenario.onActivity {actual=it.window.decorView.findViewWithTag<TextView>(tag)?.text?.toString().orEmpty()}
            if(actual==expected)return
            SystemClock.sleep(40)
        }
        assertEquals(tag,expected,actual)
    }
    private fun capture(name:String) {
        inst.waitForIdleSync();SystemClock.sleep(350)
        val bitmap=inst.uiAutomation.takeScreenshot() ?: error("No screenshot")
        val dir=File(context.getExternalFilesDir(null),"schedule-daily60-screens").apply {mkdirs()}
        File(dir,name).outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
    }
}
