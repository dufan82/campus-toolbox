package cn.campuslife.assistant

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.home.HomeLayout
import cn.campuslife.assistant.home.HomeLayoutStore
import cn.campuslife.assistant.home.ToolManagementActivity
import cn.campuslife.assistant.schedule.*
import cn.campuslife.assistant.testing.TestFilesSnapshot
import cn.campuslife.assistant.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HomeDragAndSyncDeviceTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val app=inst.targetContext

    @Test fun longPressAndDragOnHomeReordersWithHiddenAndFixedToolsUnchanged() {
        val store=HomeLayoutStore(app);val saved=store.load()
        val initial=HomeLayout.defaults().hide("daily60",true)
        try {
            store.save(initial)
            ActivityScenario.launch(MainActivity::class.java).use { screen ->
                val source=point(screen,"open_campus_map")
                val dest=point(screen,"home_tool_schedule")
                drag(source,dest)
                waitFor("persisted move") { store.load().order.take(2)==listOf("schedule","map") }
                screen.recreate()
                val map=point(screen,"open_campus_map")
                val schedule=point(screen,"home_tool_schedule")
                assertTrue(map.centerX()>schedule.centerX())
                assertEquals(initial.hidden,store.load().hidden)
                screen.onActivity { a ->
                    listOf("pdd_packages","pdd_code","douyin_orders").forEach { id ->
                        val view=a.window.decorView.findViewWithTag<View>("shortcut_"+id)
                        assertNotNull(id,view)
                        assertFalse(view.isLongClickable)
                        assertTrue(view.isClickable)
                    }
                }
            }
        } finally { store.save(saved) }
    }

    @Test fun managementCardsCanAlsoBeDragged() {
        val store=HomeLayoutStore(app);val saved=store.load()
        try {
            store.save(HomeLayout.defaults().hide("map",true))
            val intent=android.content.Intent(app,ToolManagementActivity::class.java).putExtra("manage",true)
            ActivityScenario.launch<ToolManagementActivity>(intent).use { screen ->
                drag(point(screen,"tool_drag_map"),point(screen,"tool_drag_schedule"),onTitle=true)
                waitFor("management drag saved") { store.load().order.take(2)==listOf("schedule","map") }
                assertEquals(setOf("map"),store.load().hidden)
            }
        } finally { store.save(saved) }
    }

    @Test fun completedAutomaticSyncRefreshesAnAlreadyOpenScheduleWithoutATap() {
        val prefs=DailySyncPreferences(app);val oldPrefs=prefs.read()
        val files=TestFilesSnapshot.capture(app.filesDir,listOf(ScheduleStore.FILE_NAME,ScheduleStore.FILE_NAME+".bak",
            ScheduleStore.IMPORT_UNDO_FILE,ScheduleStore.IMPORT_UNDO_FILE+".bak"))
        val store=ScheduleStore(app);val today=LocalDate.now()
        val course=Course("00000000-0000-0000-0000-000000000018","Fixture studio","Teacher","303",
            today.dayOfWeek.value,1,2,1,6,WeekPattern.EVERY,null)
        val old=ScheduleData(Semester(today.with(DayOfWeek.MONDAY),6),courses=listOf(course))
        val school=old.copy(courses=listOf(course.copy(classroom="205")))
        try {
            prefs.write(DailySyncState())
            store.replaceWithBackup(store.load(),old,true)
            ActivityScenario.launch(ScheduleActivity::class.java).use { screen ->
                waitFor("303 visible") { hasRoom(screen,"303") }
                DailySchoolSync.worker.submit {}.get(5,TimeUnit.SECONDS)
                prefs.write(DailySyncState(enabled=true))
                val source=object:DailySyncRepository by DailySchoolSync.repository(app) {
                    override fun credential()=SchoolSyncCredential("Bearer fixture",old.semester!!)
                    override fun fetch(credential:SchoolSyncCredential,cancelled:AtomicBoolean)=SchoolFetch(school)
                    override fun notify(changes:SchoolChanges)=Unit
                }
                assertEquals(SyncResult.UPDATED,DailySyncEngine(source).run(ZonedDateTime.now()))
                waitFor("205 visible without leaving or checking manually") { hasRoom(screen,"205") }
                assertFalse(hasRoom(screen,"303"))
                assertEquals("205",store.load().courses.single().classroom)
                assertEquals(old,store.previousImport())
            }
        } finally {
            prefs.write(oldPrefs)
            files.restore()
        }
    }

    private fun hasRoom(screen:ActivityScenario<ScheduleActivity>,room:String):Boolean {
        var found=false
        screen.onActivity { a -> found=walk(a.window.decorView).any { v ->
            v is TextView && room in v.text.toString() || v.contentDescription?.contains("教室："+room)==true
        } }
        return found
    }

    private fun walk(v:View):Sequence<View> = sequence {
        yield(v)
        if(v is ViewGroup) for(i in 0 until v.childCount)yieldAll(walk(v.getChildAt(i)))
    }

    private fun <T:Activity> point(screen:ActivityScenario<T>,tag:String):Rect {
        val rect=Rect()
        waitFor("visible card: "+tag) {
            var visible=false
            screen.onActivity { a ->
                visible=a.window.decorView.hasWindowFocus() &&
                    a.window.decorView.findViewWithTag<View>(tag)?.getGlobalVisibleRect(rect)==true
            }
            visible
        }
        return rect
    }

    private fun drag(source:Rect,target:Rect,onTitle:Boolean=false) {
        val x=if(onTitle)source.left+source.width()/5f else source.exactCenterX()
        val y=source.exactCenterY()
        val endX=if(onTitle)target.left+target.width()/5f else target.exactCenterX()
        val endY=target.exactCenterY()
        val down=SystemClock.uptimeMillis()
        fun send(action:Int,px:Float,py:Float) {
            val ev=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,px,py,0)
            ev.source=InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(inst.uiAutomation.injectInputEvent(ev,true)) } finally { ev.recycle() }
        }
        send(MotionEvent.ACTION_DOWN,x,y)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+220L)
        for(step in 1..10) {
            send(MotionEvent.ACTION_MOVE,x+(endX-x)*step/10,y+(endY-y)*step/10)
            SystemClock.sleep(30L)
        }
        send(MotionEvent.ACTION_UP,endX,endY)
    }

    private fun waitFor(label:String,predicate:()->Boolean) {
        val end=SystemClock.uptimeMillis()+8000
        while(SystemClock.uptimeMillis()<end) {
            if(predicate())return
            SystemClock.sleep(60)
        }
        fail(label)
    }
}
