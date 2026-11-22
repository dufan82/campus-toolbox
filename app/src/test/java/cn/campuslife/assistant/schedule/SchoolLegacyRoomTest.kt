package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SchoolLegacyRoomTest {
    private val monday=LocalDate.of(2026,11,9)
    private val today=monday.plusDays(4)
    private val course=Course("00000000-0000-0000-0000-000000000901","C4D三维设计","教师","文科楼303",5,7,10,1,6,WeekPattern.EVERY,null)
    private val local=ScheduleData(Semester(monday,6),courses=listOf(course))
    private val school=local.copy(courses=listOf(course.copy(id="00000000-0000-0000-0000-000000000902",classroom="文科楼205")))
    @Test fun oldUnknownRoomNeedsExplicitConfirmationThenUpdatesCurrentAndFutureOnly() {
        val snap=ScheduleSnapshot(local,SchoolRegistry())
        val blocked=SchoolUpdateCheck.preview(snap,school,today)
        assertEquals(local,blocked.plan.data);assertEquals(1,blocked.plan.conflicts)
        assertEquals(setOf(course.name),blocked.confirmableLegacy)
        val agreed=SchoolUpdateCheck.preview(snap,school,today,setOf(course.name))
        assertEquals(1,agreed.plan.changes.adjusted);assertEquals(0,agreed.plan.conflicts)
        assertTrue(agreed.plan.data.courses.any {it.classroom=="文科楼205" && it.occurs(1)})
        assertTrue(agreed.plan.managed.values.all {it.classroom=="文科楼205"})
        val later=SchoolUpdateCheck.preview(snap,school,monday.plusWeeks(2),setOf(course.name))
        assertTrue(later.plan.data.courses.any {it.classroom=="文科楼303" && it.endWeek==2})
        assertTrue(later.plan.data.courses.any {it.classroom=="文科楼205" && it.startWeek==3})
    }
    @Test fun confirmationCannotUnlockEditedDeletedOrPlaceLinkedCourses() {
        val edited=ScheduleSnapshot(local,SchoolRegistry(protectedIds=setOf(course.id),protectedCourses=mapOf(course.id to course)))
        val deleted=edited.copy(data=local.copy(courses=emptyList()))
        val linked=ScheduleSnapshot(local.copy(courses=listOf(course.copy(placeId="campus-place-1"))),SchoolRegistry())
        for(snap in listOf(edited,deleted,linked)) {
            assertTrue(SchoolUpdateCheck.preview(snap,school,today).confirmableLegacy.isEmpty())
            assertThrows(IllegalArgumentException::class.java){SchoolUpdateCheck.preview(snap,school,today,setOf(course.name))}
        }
    }
    @Test fun unchangedCoursesCannotBeSilentlyClaimedAndDefaultAutomaticProtectionRemains() {
        val snap=ScheduleSnapshot(local,SchoolRegistry())
        assertTrue(SchoolUpdateCheck.preview(snap,local,today).confirmableLegacy.isEmpty())
        assertThrows(IllegalArgumentException::class.java){SchoolUpdateCheck.preview(snap,local,today,setOf(course.name))}
        assertEquals(local,SchoolAutoMerge.plan(snap,school,today).data)
    }
    @Test fun checkRecordShowsTheActualReturnedRoomEvenWhenTheApiItselfIsStale() {
        val different=SchoolCheckHistory.readEvidence(local,school,today)
        assertTrue(different.contains("文科楼205"));assertTrue(different.contains("文科楼303"));assertTrue(different.contains("2026-11-13"))
        val same=SchoolCheckHistory.readEvidence(local,local,today)
        assertTrue(same.contains("文科楼303"));assertFalse(same.contains("205"));assertFalse(same.contains("审核通过"))
    }
    @Test fun confirmedSourceCannotResolveARealConflictWithAnotherProtectedCourse() {
        val hand=course.copy(id="00000000-0000-0000-0000-000000000903",name="手动安排")
        val snap=ScheduleSnapshot(local.copy(courses=local.courses+hand),SchoolRegistry())
        assertTrue(SchoolUpdateCheck.preview(snap,school,today).confirmableLegacy.isEmpty())
        assertThrows(IllegalArgumentException::class.java){SchoolUpdateCheck.preview(snap,school,today,setOf(course.name))}
    }
}
