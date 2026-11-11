package cn.campuslife.assistant.schedule
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
class SchoolCheckHistoryTest {
 @Test fun absentHistoryIsNotClaimedAsChecked(){assertNull(SchoolCheckHistory.latest(DailySyncState(),null))}
 @Test fun latestManualAndAutomaticAttemptsAreOrdered(){val manual=SchoolCheckRecord(200,"已检查，课表没有变化","");assertEquals(manual,SchoolCheckHistory.latest(DailySyncState(enabled=true,lastAttempt=100,result=SyncResult.CHECKING),manual));assertEquals("正在检查…",SchoolCheckHistory.latest(DailySyncState(enabled=true,lastAttempt=300,result=SyncResult.CHECKING),manual)!!.result)}
 @Test fun detailsIncludeActualClassroomTeacherAndDateChangesWithinLimit(){val d=LocalDate.of(2026,11,9);val c=Course("00000000-0000-0000-0000-000000000741","设计课","教师","201",2,1,2,1,10,WeekPattern.EVERY,null);val old=ScheduleData(Semester(d,10),courses=listOf(c));val text=SchoolCheckHistory.details(old,old.copy(courses=listOf(c.copy(classroom="301"))),d);assertTrue(text.contains("301"));assertTrue(text.contains("201"));assertTrue(text.contains("教师"));assertTrue(text.contains("11月10日"));assertTrue(text.length<=4000)}
 @Test fun untouchedTimetableHasNoInventedChanges(){val d=ScheduleData(Semester(LocalDate.of(2026,11,9),10));assertEquals("",SchoolCheckHistory.details(d,d,LocalDate.of(2026,11,10)))}
}
