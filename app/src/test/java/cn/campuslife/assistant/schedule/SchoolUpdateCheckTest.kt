package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SchoolUpdateCheckTest {
    private val monday=LocalDate.of(2026,11,9)
    private val course=Course("00000000-0000-0000-0000-000000000601","设计课","教师","201",2,1,2,1,12,WeekPattern.EVERY,null)
    private fun data()=ScheduleData(Semester(monday,12),courses=listOf(course))
    @Test fun sameDatedSchoolCoursesAreNoOpEvenWhenRemoteIdsChange() {
        val local=data();val result=SchoolUpdateCheck.preview(ScheduleSnapshot(local,SchoolRegistry(mapOf(course.id to course))),local.copy(courses=listOf(course.copy(id="00000000-0000-0000-0000-000000000602"))),monday.plusWeeks(2))
        assertEquals(local,result.plan.data);assertFalse(result.initialImport);assertEquals(0,result.plan.changes.total)
    }
    @Test fun userModifiedAndDeletedRecordsAreNeverReplacedOrResurrected() {
        val local=data().copy(courses=listOf(course.copy(classroom="手动教室")))
        val snapshot=ScheduleSnapshot(local,SchoolRegistry(protectedIds=setOf(course.id),protectedCourses=mapOf(course.id to course)))
        val result=SchoolUpdateCheck.preview(snapshot,data(),monday)
        assertEquals(local,result.plan.data);assertEquals(1,result.plan.conflicts)
        val deleted=snapshot.copy(data=local.copy(courses=emptyList()))
        assertTrue(SchoolUpdateCheck.preview(deleted,data(),monday).plan.data.courses.isEmpty())
    }
    @Test fun firstImportOnlyCreatesFutureCourses() {
        val result=SchoolUpdateCheck.preview(ScheduleSnapshot(ScheduleData(),SchoolRegistry()),data(),monday.plusWeeks(3))
        assertTrue(result.initialImport);assertEquals(monday,data().semester!!.startMonday)
        assertTrue(result.plan.data.courses.all {it.startWeek>=4})
    }
    @Test fun existingUnknownOriginCoursesRemainProtected() {
        val local=data();val result=SchoolUpdateCheck.preview(ScheduleSnapshot(local,SchoolRegistry()),local.copy(courses=listOf(course.copy(classroom="新教室"))),monday)
        assertEquals(local,result.plan.data);assertEquals(1,result.plan.conflicts)
    }
    @Test fun aDifferentSemesterCannotReplaceExistingLocalData() {
        assertThrows(IllegalArgumentException::class.java){SchoolUpdateCheck.preview(ScheduleSnapshot(data(),SchoolRegistry()),data().copy(semester=Semester(monday.plusWeeks(20),12)),monday)}
    }
    @Test fun initialSetupCannotOverwriteCustomPeriodSettingsOrProtectionHistory() {
        val custom=ScheduleData(periods=listOf(SchedulePeriod("自定义")))
        assertThrows(IllegalArgumentException::class.java){SchoolUpdateCheck.preview(ScheduleSnapshot(custom,SchoolRegistry()),data(),monday)}
        assertThrows(IllegalArgumentException::class.java){SchoolUpdateCheck.preview(ScheduleSnapshot(ScheduleData(),SchoolRegistry(protectedIds=setOf(course.id))),data(),monday)}
    }
}
