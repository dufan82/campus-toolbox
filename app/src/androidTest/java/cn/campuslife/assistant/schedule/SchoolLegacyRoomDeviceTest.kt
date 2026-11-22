package cn.campuslife.assistant.schedule

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import android.graphics.Bitmap

@SdkSuppress(minSdkVersion=29)
class SchoolLegacyRoomDeviceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=inst.targetContext
    private val today get()=LocalDate.now()
    private fun data():ScheduleData {
        val start=today.with(DayOfWeek.MONDAY).minusWeeks(2)
        val c=Course("00000000-0000-0000-0000-000000000991","C4D三维设计","合成教师","文科楼303",5,7,10,1,6,WeekPattern.EVERY,null)
        return ScheduleData(Semester(start,6),courses=listOf(c))
    }
    private fun school(local:ScheduleData)=local.copy(courses=local.courses.map {it.copy(id="00000000-0000-0000-0000-000000000992",classroom="文科楼205")})
    private fun isolated():Context {
        val dir=Files.createTempDirectory(app.cacheDir.toPath(),"room-cas-").toFile()
        return object:ContextWrapper(app){override fun getApplicationContext():Context=this;override fun getFilesDir()=dir}
    }
    @Test fun confirmedStorePersists205AndFutureAutomaticChecksCanFollowWithoutLosingHistory() {
        val context=isolated()
        try {
            val store=ScheduleStore(context);val local=data();val remote=school(local)
            store.replaceWithBackup(store.load(),local);val before=store.loadSnapshot()
            store.applyConfirmedSchool(before,remote,setOf("C4D三维设计"),today)
            val reopened=ScheduleStore(context);val saved=reopened.loadSnapshot()
            assertTrue(saved.data.courses.any {it.startWeek==3 && it.classroom=="文科楼205"})
            assertTrue(saved.data.courses.any {it.endWeek==2 && it.classroom=="文科楼303"})
            assertEquals(local,reopened.previousImport())
            assertTrue(saved.registry.managed.values.all {it.classroom=="文科楼205"})
            val next=remote.copy(courses=remote.courses.map {it.copy(classroom="文科楼206")})
            reopened.applyAutomatic(saved,next,today)
            assertTrue(reopened.load().courses.any {it.startWeek==3 && it.classroom=="文科楼206"})
        }finally {context.filesDir.deleteRecursively()}
    }
    @Test fun concurrentEditRejectsConfirmationWithoutReplacingScheduleOrUndo() {
        val context=isolated()
        try {
            val store=ScheduleStore(context);val local=data();store.replaceWithBackup(store.load(),local)
            val before=store.loadSnapshot();store.commit(local,local.copy(courses=local.courses.map {it.copy(teacher="并发修改")}))
            val bytes=File(context.filesDir,ScheduleStore.FILE_NAME).readBytes()
            val undo=File(context.filesDir,ScheduleStore.IMPORT_UNDO_FILE).readBytes()
            assertThrows(ScheduleConflictException::class.java){store.applyConfirmedSchool(before,school(local),setOf("C4D三维设计"),today)}
            assertArrayEquals(bytes,File(context.filesDir,ScheduleStore.FILE_NAME).readBytes())
            assertArrayEquals(undo,File(context.filesDir,ScheduleStore.IMPORT_UNDO_FILE).readBytes())
        }finally {context.filesDir.deleteRecursively()}
    }
    private fun nodes():List<View> {
        fun collect(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {collect(v.getChildAt(it))}else emptyList()
        return WindowInspector.getGlobalWindowViews().filter {it.isShown}.flatMap(::collect)
    }
    private fun click(text:String) {inst.runOnMainSync {assertTrue(nodes().filterIsInstance<TextView>().first {it.text.toString()==text}.performClick())}}
    private fun capture(name:String) {
        inst.waitForIdleSync();SystemClock.sleep(220)
        val bitmap=checkNotNull(inst.uiAutomation.takeScreenshot())
        try {val dir=File(app.getExternalFilesDir(null),"school-room-mismatch").apply {mkdirs()}
            File(dir,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        }finally {bitmap.recycle()}
    }
    private fun await(s:ActivityScenario<ScheduleTransferActivity>,tag:String,accept:(String)->Boolean) {
        val end=SystemClock.uptimeMillis()+8000
        while(SystemClock.uptimeMillis()<end){var t="";s.onActivity {a->t=a.window.decorView.findViewWithTag<TextView>(tag)?.text?.toString().orEmpty()};if(accept(t))return;SystemClock.sleep(40)}
        fail("Missing $tag")
    }
    @Test fun uiRequiresSourceConfirmationAndFinalSaveThenActuallyShowsApplied205()=preserved {
        val store=ScheduleStore(app);val local=data();store.replaceWithBackup(store.load(),local);val before=store.loadSnapshot()
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use {s->
            await(s,"school_check_school"){it.isNotBlank()}
            s.onActivity {a->
                val session=a.javaClass.getDeclaredField("session").apply {isAccessible=true}.get(a)
                session.javaClass.getDeclaredField("baselineSnapshot").apply {isAccessible=true}.set(session,before)
                a.javaClass.getDeclaredMethod("preview",ScheduleData::class.java,String::class.java,List::class.java,ScheduleData::class.java,Boolean::class.javaPrimitiveType)
                    .apply {isAccessible=true}.invoke(a,school(local),"离线合成课表",emptyList<String>(),local,true)
            }
            await(s,"schedule.diff.details"){it.contains("文科楼205") && it.contains("文科楼303")}
            capture("01-actual-difference")
            assertEquals(before,store.loadSnapshot())
            await(s,"schedule.diff.school"){it.contains("调整 1 门")}
            s.onActivity {a->val button=a.window.decorView.findViewWithTag<TextView>("schedule.apply.changes");assertNotNull(button);assertTrue(button.isShown);val rect=android.graphics.Rect();assertTrue(button.getGlobalVisibleRect(rect));assertEquals(button.height,rect.height())}
            click("更新这1门课")
            inst.runOnMainSync {assertTrue(nodes().filterIsInstance<TextView>().any {it.text.toString().contains("请确认它们原本来自学校")})}
            capture("02-confirmed-not-saved")
            click("取消");assertEquals(before,store.loadSnapshot())
            s.recreate();await(s,"schedule.diff.school"){it.contains("调整 1 门")}
            assertEquals(before,store.loadSnapshot())
            click("更新这1门课");click("确认更新")
            await(s,"school_check_local"){it.contains("已更新")}
            await(s,"school_check_visible_changes"){it.contains("已更新") && it.contains("文科楼303 → 文科楼205")}
            capture("03-applied")
            s.recreate();await(s,"school_check_visible_changes"){it.contains("已更新") && it.contains("205")}
            assertTrue(SchoolCheckHistoryStore(app).read()!!.summary.contains("文科楼303 → 文科楼205"))
            assertTrue(store.load().courses.any {it.startWeek==3 && it.classroom=="文科楼205"})
            assertEquals(local,store.previousImport())
        }
    }
    private fun preserved(action:()->Unit) {
        val names=listOf(ScheduleStore.FILE_NAME,ScheduleStore.IMPORT_UNDO_FILE).flatMap {listOf(it,"$it.bak","$it.new")}
        val files=names.associateWith {n->File(app.filesDir,n).let {if(it.exists())it.readBytes()else null}}
        val prefs=listOf("school-manual-check-v1","school-daily-sync-v1").associateWith {app.getSharedPreferences(it,0).all}
        try {DailySyncPreferences(app).write(DailySyncState());SchoolCheckHistoryStore(app).write(SchoolCheckRecord(System.currentTimeMillis(),"已检查，课表没有变化",""));action()}
        finally {
            files.forEach {(n,b)->File(app.filesDir,n).let {if(b==null)it.delete()else it.writeBytes(b)}}
            prefs.forEach {(n,values)->check(app.getSharedPreferences(n,0).edit().clear().apply {values.forEach {(k,v)->when(v){is String->putString(k,v);is Long->putLong(k,v);is Boolean->putBoolean(k,v);is Int->putInt(k,v)}}}.commit())}
        }
    }
}
