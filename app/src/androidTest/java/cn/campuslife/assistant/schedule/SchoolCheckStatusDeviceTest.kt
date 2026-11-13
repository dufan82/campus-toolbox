package cn.campuslife.assistant.schedule

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** UI history only: no school credentials, login, production endpoints or timetable changes. */
@SdkSuppress(minSdkVersion=29)
class SchoolCheckStatusDeviceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=inst.targetContext
    private fun nodes():List<View> {
        fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))}else emptyList()
        return WindowInspector.getGlobalWindowViews().filter {it.isShown}.flatMap(::all)
    }
    private fun await(text:String) {val end=SystemClock.uptimeMillis()+8000;while(SystemClock.uptimeMillis()<end){var found=false;inst.runOnMainSync {found=nodes().filterIsInstance<TextView>().any {it.text.toString()==text}};if(found)return;SystemClock.sleep(40)};fail("Missing $text")}
    @Test fun uncheckedNoChangeAndActualUpdateContentsRemainAfterRecreationWithoutWritingSchedule() {
        val sync=DailySyncPreferences(app);val previous=sync.read()
        val prefs=app.getSharedPreferences("school-manual-check-v1",0);val saved=prefs.all
        val before=ScheduleStore(app).loadSnapshot()
        try {
            sync.write(DailySyncState());assertTrue(prefs.edit().clear().commit())
            ActivityScenario.launch(ScheduleTransferActivity::class.java).use {scenario->
                await("尚未检查")
                SchoolCheckHistoryStore(app).write(SchoolCheckRecord(System.currentTimeMillis(),"已检查，课表没有变化",""))
                scenario.recreate();await("已完成检查")
                await("学校课表：未发现变化");await("本机课表：无需更新")
                inst.runOnMainSync {assertFalse(nodes().any {it.tag=="school_check_details"})}
            }
            SchoolCheckHistoryStore(app).write(SchoolCheckRecord(System.currentTimeMillis(),"已检查，课表已更新","更新后\n11月12日 · 设计课 · 301 · 陈老师 · 第1–2节\n\n更新前\n11月12日 · 设计课 · 201 · 王老师 · 第1–2节"))
            ActivityScenario.launch(ScheduleTransferActivity::class.java).use {scenario->
                await("已完成检查");await("本机课表：已更新")
                inst.runOnMainSync {assertTrue(nodes().single {it.tag=="school_check_details"}.performClick())}
                await("更新后\n11月12日 · 设计课 · 301 · 陈老师 · 第1–2节\n\n更新前\n11月12日 · 设计课 · 201 · 王老师 · 第1–2节")
                scenario.recreate();await("已完成检查");await("本机课表：已更新")
            }
            val failure=java.io.IOException("Offline fixture")
            try {SchoolCheckHistoryStore(app).recordingFailure("检查未完成，课表读取失败") {throw failure};fail("Expected original error")}
            catch(expected:java.io.IOException) {assertSame(failure,expected)}
            ActivityScenario.launch(ScheduleTransferActivity::class.java).use {await("检查未完成，课表读取失败")}
            assertEquals("",SchoolCheckHistoryStore(app).read()!!.details)
            assertEquals(before,ScheduleStore(app).loadSnapshot())
            // Pre-upgrade preferences have no details key.
            val raw=app.getSharedPreferences("school-daily-sync-v1",0)
            assertTrue(raw.edit().remove("details").commit());assertEquals("",sync.read().details)
        } finally {
            sync.write(previous)
            val edit=prefs.edit().clear()
            saved.forEach {(key,value)->when(value){is String->edit.putString(key,value);is Long->edit.putLong(key,value)}}
            assertTrue(edit.commit())
        }
    }
}
