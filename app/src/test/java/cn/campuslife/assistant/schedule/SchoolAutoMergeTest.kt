package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SchoolAutoMergeTest {
    private val start = LocalDate.of(2026, 9, 7)
    private val today = start.plusWeeks(2)
    private val a = Course("00000000-0000-0000-0000-000000000001", "Math", "Teacher", "101", 1, 1, 2, 1, 6, WeekPattern.EVERY, null)
    private val b = a.copy(id="00000000-0000-0000-0000-000000000002", name="Art", weekday=2)
    private fun data(vararg c: Course) = ScheduleData(Semester(start,6), courses=c.toList())
    private fun plan(local: ScheduleData, incoming: ScheduleData, registry: SchoolRegistry = SchoolRegistry(local.courses.associateBy { it.id })) =
        SchoolAutoMerge.plan(ScheduleSnapshot(local, registry), incoming, today)
    @Test fun unchangedDoesNotSplitHistoryOrRewriteIds() {
        val local=data(a,b); assertEquals(local,plan(local,local).data)
        assertEquals(0,plan(local,local).changes.total)
    }
    @Test fun newAndCancelledAreFutureOnly() {
        val p=plan(data(a),data(b)); assertEquals(1,p.changes.added);assertEquals(1,p.changes.cancelled)
        assertEquals(2,p.data.courses.first { it.name=="Math" }.endWeek)
        assertEquals(3,p.data.courses.first { it.name=="Art" }.startWeek)
    }
    @Test fun classroomTeacherTimeAndWeeksAreChangesWithoutDuplicates() {
        listOf(a.copy(classroom="202"),a.copy(teacher="New"),a.copy(weekday=3,startPeriod=3,endPeriod=4),a.copy(endWeek=4)).forEach { changed ->
            val p=plan(data(a,b),data(changed,b));assertEquals(1,p.changes.adjusted)
            assertEquals(b,p.data.courses.first { it.id==b.id })
            val second=plan(p.data,data(changed,b),SchoolRegistry(p.managed))
            assertEquals(p.data,second.data);assertEquals(0,second.changes.total)
        }
    }
    @Test fun manualAddedEditedAndDeletedStayProtectedWhileOtherCourseUpdates() {
        val edit=a.copy(weekday=4,teacher="Mine",placeId="local-place")
        val registry=SchoolRegistry(mapOf(b.id to b),setOf(a.id),mapOf(a.id to a))
        for(local in listOf(data(edit,b),data(b))) {
            val p=plan(local,data(a.copy(classroom="Moved"),b.copy(teacher="Changed")),registry)
            assertEquals(1,p.changes.adjusted);assertTrue(p.conflicts>0)
            assertEquals(local.courses.filter { it.name==a.name },p.data.courses.filter { it.name==a.name })
        }
        val p=plan(data(a,b),data(a.copy(weekday=4),b.copy(teacher="Changed")),SchoolRegistry(mapOf(b.id to b)))
        assertTrue(a in p.data.courses);assertEquals(1,p.changes.adjusted)
    }
    @Test fun unknownLegacyTombstoneDoesNotBlockKnownUnrelatedUpdateOrResurrectCourse() {
        val p=plan(data(b),data(a,b.copy(teacher="Changed")),SchoolRegistry(mapOf(b.id to b),setOf(a.id)))
        assertFalse(p.data.courses.any { it.name==a.name });assertEquals(1,p.changes.adjusted)
    }
    @Test fun conflictingMoveKeepsExistingSchoolCourseRatherThanDeletingIt() {
        val p=plan(data(a,b),data(a,b.copy(weekday=1)),SchoolRegistry(mapOf(b.id to b)))
        assertEquals(data(a,b),p.data);assertTrue(p.conflicts>0)
    }
    @Test fun semesterOrPeriodsMismatchRejectsWholePlan() {
        assertThrows(IllegalArgumentException::class.java) { plan(data(a),data(a).copy(semester=Semester(start.plusWeeks(1),6))) }
        assertThrows(IllegalArgumentException::class.java) { plan(data(a),data(a).copy(periods=data(a).periods.dropLast(1))) }
    }
    @Test fun protectedDeletionCannotBeRecreatedByRenamingAndMoving() {
        val p=plan(data(b),data(a.copy(name="Renamed",weekday=4),b.copy(classroom="202")),
            SchoolRegistry(mapOf(b.id to b),setOf(a.id),mapOf(a.id to a)))
        assertFalse(p.data.courses.any { it.name=="Renamed" });assertEquals(1,p.changes.adjusted)
    }
    @Test fun unknownOriginAndLocalPlaceAssociationAreNeverOverwritten() {
        val local=data(a.copy(placeId="local-place"),b)
        val p=plan(local,data(a.copy(classroom="202",weekday=4),b.copy(teacher="Changed")),SchoolRegistry(mapOf(b.id to b)))
        assertEquals("local-place",p.data.courses.first {it.name==a.name}.placeId)
        assertEquals(1,p.changes.adjusted)
    }
    @Test fun historyAndOddEvenRulesRemainExactWhenCourseIsCancelled() {
        val odd=a.copy(pattern=WeekPattern.ODD);val p=plan(data(odd),data(),SchoolRegistry(mapOf(odd.id to odd)))
        assertEquals(1,p.changes.cancelled);assertEquals(1,p.data.courses.single().endWeek)
        assertFalse(p.data.courses.any {it.occurs(3)})
    }
}
