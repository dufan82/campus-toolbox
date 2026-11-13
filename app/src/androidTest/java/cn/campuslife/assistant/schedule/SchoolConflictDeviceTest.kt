package cn.campuslife.assistant.schedule

import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/** Offline synthetic preview/storage regression. Run only on the designated disposable emulator. */
class SchoolConflictDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val today get()=LocalDate.now()
    private fun data():ScheduleData {
        val start=today.with(DayOfWeek.MONDAY).minusWeeks(2)
        return ScheduleData(Semester(start,6),courses=(1..8).map { i->
            Course("00000000-0000-0000-0000-${i.toString().padStart(12,'0')}","合成课程$i","教师$i","旧教室$i",
                (i-1)%7+1,if(i==8)3 else 1,if(i==8)4 else 2,1,6,WeekPattern.EVERY,null)
        })
    }

    @Test fun identicalEightUnknownGroupsShowNoChangeWithoutWritingOrClaiming()=preserved {
        val store=ScheduleStore(context);val local=data()
        store.replaceWithBackup(store.load(),local)
        val before=store.loadSnapshot();val bytes=scheduleBytes()
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use {scenario->
            preview(scenario,before,local)
            awaitText(scenario,"schedule.preview.summary") {it=="课表没有变化"}
            assertEquals(false,SchoolCheckHistoryStore(context).read()!!.outcome!!.differences)
            assertEquals(before,store.loadSnapshot());assertTrue(store.loadSnapshot().registry.managed.isEmpty())
            assertBytes(bytes)
            scenario.recreate();awaitText(scenario,"schedule.preview.summary") {it=="课表没有变化"}
            assertBytes(bytes)
        }
    }

    @Test fun realEightConflictsShowBothSidesReasonsAndSurviveHistoryAndRecreation()=preserved {
        val store=ScheduleStore(context);val local=data()
        store.replaceWithBackup(store.load(),local)
        val before=store.loadSnapshot();val bytes=scheduleBytes()
        val school=local.copy(courses=local.courses.map {it.copy(classroom="学校新教室"+it.name)})
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use {scenario->
            preview(scenario,before,school)
            awaitText(scenario,"schedule.diff.details") {it.contains("来源未确认")}
            fun checkDetails()=scenario.onActivity {a->
                val value=a.window.decorView.findViewWithTag<TextView>("schedule.diff.details").text.toString()
                local.courses.forEach {c->assertTrue(value.contains(c.name));assertTrue(value.contains(c.classroom));assertTrue(value.contains("学校新教室"+c.name))}
                assertTrue(value.contains("未应用"));assertTrue(value.contains("本地："));assertTrue(value.contains("学校："))
            }
            checkDetails();scenario.recreate()
            awaitText(scenario,"schedule.diff.details") {it.contains("来源未确认")};checkDetails()
            val record=SchoolCheckHistoryStore(context).read()!!
            assertEquals(8,record.outcome!!.protectedGroups)
            assertTrue(record.details.contains("合成课程8"));assertTrue(record.details.contains("学校新教室合成课程8"))
            assertEquals(before,store.loadSnapshot());assertBytes(bytes)
            scenario.onActivity {a->a.javaClass.getDeclaredMethod("home").apply {isAccessible=true}.invoke(a)}
            awaitText(scenario,"school_check_school") {it=="学校课表：发现差异"}
            scenario.onActivity {a->
                assertNull(a.window.decorView.findViewWithTag<View>("school_check_recheck"))
                assertTrue(a.window.decorView.findViewWithTag<View>("school_check_details").performClick())
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    @Test fun safeIncrementalCommitKeepsHistoryDeletionEditAndUnknownProtectionBackupAndCas()=preserved {
        val store=ScheduleStore(context);val original=data().copy(courses=data().courses.take(3))
        store.replaceWithBackup(store.load(),original,schoolImport=true)
        val known=original.courses[0];val edited=original.courses[1];val deleted=original.courses[2]
        val unknown=data().courses[3]
        val local=original.copy(courses=listOf(known,edited.copy(teacher="我的教师"),unknown))
        store.commit(original,local)
        val before=store.loadSnapshot()
        val school=original.copy(courses=listOf(known.copy(classroom="学校安全新教室"),edited.copy(classroom="学校受保护教室"),deleted,unknown))
        val p=store.applyAutomatic(before,school,today)
        assertEquals(SchoolChanges(adjusted=1),p.changes);assertEquals(2,p.conflicts)
        assertEquals(local,store.previousImport())
        assertEquals(edited.copy(teacher="我的教师"),p.data.courses.single {it.id==edited.id})
        assertFalse(p.data.courses.any {it.id==deleted.id});assertTrue(p.data.courses.contains(unknown))
        assertFalse(unknown.id in p.managed)
        assertEquals(if(known.weekday<today.dayOfWeek.value)3 else 2,p.data.courses.single {it.id==known.id}.endWeek)
        val after=store.loadSnapshot();val bytes=scheduleBytes()
        store.applyAutomatic(after,school,today);assertBytes(bytes)
        assertThrows(ScheduleConflictException::class.java) {store.applyAutomatic(before,school,today)}
        assertThrows(IllegalArgumentException::class.java) {store.applyAutomatic(after,school,today.minusDays(1))}
        assertBytes(bytes)
    }

    @Test fun automaticDetailsPersistAndOpenInDailySettings()=preserved {
        val local=data();val p=SchoolAutoMerge.plan(ScheduleSnapshot(local,SchoolRegistry()),
            local.copy(courses=local.courses.map {it.copy(teacher="学校教师")}),today)
        val details=SchoolCheckHistory.details(p,today,true)
        val prefs=DailySyncPreferences(context)
        prefs.write(DailySyncState(enabled=false,lastAttempt=System.currentTimeMillis(),result=SyncResult.CONFLICT,
            details=details,outcome=SchoolCheckOutcome.completed(p.changes,p.conflicts)))
        assertEquals(details,DailySyncPreferences(context).read().details)
        ActivityScenario.launch(DailySyncSettingsActivity::class.java).use {scenario->
            val end=SystemClock.uptimeMillis()+8000;var clicked=false
            while(SystemClock.uptimeMillis()<end && !clicked) {
                scenario.onActivity {a->clicked=a.window.decorView.findViewWithTag<View>("school_check_details")?.performClick()==true}
                if(!clicked)SystemClock.sleep(40)
            }
            assertTrue("daily settings details action",clicked)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    @Test fun oldEightConflictCacheExplicitlyRequiresRecheckAndKeepsOriginalCounts()=preserved {
        val record=SchoolCheckRecord(System.currentTimeMillis(),"已检查，受保护课程已保留","",
            SchoolCheckOutcome.completed(SchoolChanges(),8))
        SchoolCheckHistoryStore(context).write(record)
        val bytes=scheduleBytes()
        ActivityScenario.launch(ScheduleTransferActivity::class.java).use {scenario->
            awaitText(scenario,"school_check_recheck") {it.contains("旧结果未重新核验")}
            scenario.recreate();awaitText(scenario,"school_check_recheck") {it.contains("请重新检查")}
            assertEquals(record,SchoolCheckHistoryStore(context).read());assertBytes(bytes)
        }
    }

    private fun preview(scenario:ActivityScenario<ScheduleTransferActivity>,snapshot:ScheduleSnapshot,school:ScheduleData) {
        awaitText(scenario,"school_check_school") {it.isNotBlank()}
        scenario.onActivity {a->
            val session=a.javaClass.getDeclaredField("session").apply {isAccessible=true}.get(a)
            session.javaClass.getDeclaredField("baselineSnapshot").apply {isAccessible=true}.set(session,snapshot)
            a.javaClass.getDeclaredMethod("preview",ScheduleData::class.java,String::class.java,List::class.java,
                ScheduleData::class.java,Boolean::class.javaPrimitiveType).apply {isAccessible=true}
                .invoke(a,school,"合成离线课表",emptyList<String>(),snapshot.data,true)
        }
    }
    private fun awaitText(scenario:ActivityScenario<ScheduleTransferActivity>,tag:String,accept:(String)->Boolean) {
        val end=SystemClock.uptimeMillis()+8000;var actual=""
        while(SystemClock.uptimeMillis()<end) {
            scenario.onActivity {a->actual=a.window.decorView.findViewWithTag<TextView>(tag)?.text?.toString().orEmpty()}
            if(accept(actual))return
            SystemClock.sleep(40)
        }
        fail("Timed out: $tag, actual=$actual")
    }
    private fun scheduleBytes()=listOf(ScheduleStore.FILE_NAME,ScheduleStore.IMPORT_UNDO_FILE).associateWith {n->
        File(context.filesDir,n).let {if(it.exists())it.readBytes() else null}
    }
    private fun assertBytes(expected:Map<String,ByteArray?>)=expected.forEach {(n,b)->assertArrayEquals(n,b,scheduleBytes()[n])}
    private fun preserved(action:()->Unit) {
        val names=listOf(ScheduleStore.FILE_NAME,ScheduleStore.IMPORT_UNDO_FILE).flatMap {listOf(it,"$it.bak","$it.new")}
        val files=names.associateWith {n->File(context.filesDir,n).let {if(it.exists())it.readBytes() else null}}
        val preferences=listOf("school-manual-check-v1","school-daily-sync-v1").associateWith {n->context.getSharedPreferences(n,0).all}
        try {
            DailySyncPreferences(context).write(DailySyncState(enabled=false))
            context.getSharedPreferences("school-manual-check-v1",0).edit().clear().commit()
            // Seed a known UI state without any real login or network request.
            SchoolCheckHistoryStore(context).write(SchoolCheckRecord(1,"已检查，课表没有变化","",SchoolCheckOutcome.completed(SchoolChanges(),0)))
            action()
        } finally {
            files.forEach {(n,b)->File(context.filesDir,n).let {if(b==null)it.delete() else it.writeBytes(b)}}
            preferences.forEach {(n,all)->check(context.getSharedPreferences(n,0).edit().clear().apply {all.forEach {(k,v)->
                when(v){is String->putString(k,v);is Long->putLong(k,v);is Boolean->putBoolean(k,v);is Int->putInt(k,v);is Float->putFloat(k,v)}
            }}.commit())}
        }
    }
}
