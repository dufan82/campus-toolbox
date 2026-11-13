package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic only: no school client, credentials, disk or Android runtime. */
class SchoolConflictRegressionTest {
    private val monday = LocalDate.of(2026, 9, 7)
    private val today = monday.plusWeeks(2)
    private val courses = (1..8).map { i -> Course(
        "00000000-0000-0000-0000-${i.toString().padStart(12, '0')}",
        "合成课程$i", "教师$i", "旧教室$i", (i-1)%7+1, if(i==8)3 else 1, if(i==8)4 else 2,
        1, 6, WeekPattern.EVERY, null) }
    private fun data(cs:List<Course>) = ScheduleData(Semester(monday,6),courses=cs)

    private inner class Repo(var local:ScheduleSnapshot, val school:ScheduleData):DailySyncRepository {
        var status=DailySyncState(enabled=true)
        var writes=0;var notices=0
        override fun state()=status
        override fun update(generation:Long,transform:(DailySyncState)->DailySyncState){status=transform(status)}
        override fun credential()=SchoolSyncCredential("synthetic-offline-only",school.semester!!)
        override fun clearCredential(generation:Long){}
        override fun snapshot()=local
        override fun fetch(credential:SchoolSyncCredential,cancelled:AtomicBoolean)=SchoolFetch(school)
        override fun apply(expected:ScheduleSnapshot,school:ScheduleData,today:LocalDate,generation:Long):SchoolAutoPlan {
            check(local==expected)
            return SchoolAutoMerge.plan(expected,school,today).also {p->
                if(p.data!=local.data){writes++;local=ScheduleSnapshot(p.data,local.registry.copy(managed=p.managed))}
            }
        }
        override fun notify(changes:SchoolChanges){notices++}
        fun run()=DailySyncEngine(this).run(today.atTime(7,5).atZone(ZoneId.of("Asia/Shanghai")))
    }

    @Test fun eightIdenticalUnknownGroupsAreNoChangeNotEightConflictsAndNotClaimed() {
        val snapshot=ScheduleSnapshot(data(courses),SchoolRegistry())
        val p=SchoolAutoMerge.plan(snapshot,data(courses.map {it.copy(id=java.util.UUID.randomUUID().toString())}),today)
        assertEquals("identical content is not eight blocked differences",0,p.conflicts)
        assertEquals(0,p.changes.total);assertEquals(snapshot.data,p.data);assertTrue(p.managed.isEmpty())
        val repo=Repo(snapshot,data(courses));assertEquals(SyncResult.NO_CHANGE,repo.run())
        assertEquals(0,repo.writes);assertEquals(0,repo.notices)
        assertEquals(false,repo.status.outcome!!.differences)
    }

    @Test fun identicalProtectedAndPlaceLinkedCoursesAreNotDifferencesOrReclaimed() {
        val linked=courses.map {it.copy(placeId="local-place")}
        val registry=SchoolRegistry(protectedIds=courses.map {it.id}.toSet(),protectedCourses=courses.associateBy {it.id})
        val p=SchoolAutoMerge.plan(ScheduleSnapshot(data(linked),registry),data(courses),today)
        assertEquals(0,p.conflicts);assertEquals(data(linked),p.data);assertTrue(p.managed.isEmpty())
    }

    @Test fun eightRealUnknownDifferencesHaveLocalSchoolAndReasonEvenWhenNothingIsApplied() {
        val repo=Repo(ScheduleSnapshot(data(courses),SchoolRegistry()),data(courses.map {it.copy(classroom="学校教室"+it.name)}))
        assertEquals(SyncResult.CONFLICT,repo.run());assertEquals(0,repo.writes)
        assertEquals(8,repo.status.outcome!!.protectedGroups)
        courses.forEach { c ->
            assertTrue("missing course ${c.name}: ${repo.status.details}",repo.status.details.contains(c.name))
            assertTrue(repo.status.details.contains(c.classroom))
            assertTrue(repo.status.details.contains("学校教室"+c.name))
        }
        assertTrue(repo.status.details.contains("本地"));assertTrue(repo.status.details.contains("学校"))
        assertTrue(repo.status.details.contains("来源未确认"));assertTrue(repo.status.details.contains("未应用"))
    }

    @Test fun unchangedUnknownDoesNotHideSafeManagedAdjustmentAndTrueProtectedConflict() {
        val known=courses[0];val unknown=courses[1];val protected=courses[2]
        val registry=SchoolRegistry(mapOf(known.id to known),setOf(protected.id),mapOf(protected.id to protected))
        val local=data(listOf(known,unknown,protected.copy(teacher="我的教师")))
        val school=data(listOf(known.copy(classroom="学校新教室"),unknown,protected.copy(classroom="学校受保护教室")))
        val repo=Repo(ScheduleSnapshot(local,registry),school)
        assertEquals(SyncResult.CONFLICT,repo.run());assertEquals(1,repo.writes);assertEquals(1,repo.notices)
        assertEquals(SchoolCheckOutcome.completed(SchoolChanges(adjusted=1),1),repo.status.outcome)
        assertTrue(repo.local.data.courses.contains(unknown))
        assertEquals("我的教师",repo.local.data.courses.single {it.name==protected.name}.teacher)
        assertEquals(2,repo.local.data.courses.single {it.id==known.id}.endWeek)
        assertTrue(repo.status.details.contains("学校新教室"));assertTrue(repo.status.details.contains("我的教师"))
        assertTrue(repo.status.details.contains("学校受保护教室"));assertTrue(repo.status.details.contains("手动修改"))
    }

    @Test fun deletedCourseRemainsDeletedButShowsSchoolCourseAndDeletionReason() {
        val deleted=courses[0];val registry=SchoolRegistry(protectedIds=setOf(deleted.id),protectedCourses=mapOf(deleted.id to deleted))
        val repo=Repo(ScheduleSnapshot(data(emptyList()),registry),data(listOf(deleted)))
        assertEquals(SyncResult.CONFLICT,repo.run());assertEquals(0,repo.writes)
        assertTrue(repo.local.data.courses.isEmpty());assertTrue(repo.status.details.contains(deleted.name))
        assertTrue(repo.status.details.contains("删除保护"));assertTrue(repo.status.details.contains("本地：无"))
    }

    @Test fun actualMultisetDifferenceMustNotBeHiddenByEquivalentSingleUnknownRecord() {
        val c=courses[0];val school=data(listOf(c,c.copy(id=courses[1].id)))
        val p=SchoolAutoMerge.plan(ScheduleSnapshot(data(listOf(c)),SchoolRegistry()),school,today)
        assertEquals(1,p.conflicts);assertEquals(data(listOf(c)),p.data)
    }

    @Test fun unknownEqualityAcrossSplitRowsIgnoresIdsButNotDatesOrTeachers() {
        val c=courses[0]
        val snapshot=ScheduleSnapshot(data(listOf(c)),SchoolRegistry())
        val split=data(listOf(c.copy(endWeek=2),c.copy(id=courses[1].id,startWeek=3)))
        assertEquals(0,SchoolAutoMerge.plan(snapshot,split,today).conflicts)
        for(changed in listOf(c.copy(teacher="学校教师"),c.copy(weekday=2),c.copy(startPeriod=3,endPeriod=4),
            c.copy(endWeek=5),c.copy(pattern=WeekPattern.ODD))) {
            val p=SchoolAutoMerge.plan(snapshot,data(listOf(changed)),today)
            assertEquals(1,p.conflicts);assertEquals(snapshot.data,p.data)
            assertTrue(p.managed.isEmpty());assertEquals(1,p.differences.size)
        }
    }

    @Test fun unrelatedSafeNewCourseIsFutureOnlyWhileUnknownContentStaysUnclaimed() {
        val unknown=courses[0];val new=courses[1]
        val p=SchoolAutoMerge.plan(ScheduleSnapshot(data(listOf(unknown)),SchoolRegistry()),data(listOf(unknown,new)),today)
        assertEquals(SchoolChanges(added=1),p.changes);assertEquals(0,p.conflicts)
        assertTrue(p.data.courses.contains(unknown));assertFalse(unknown.id in p.managed)
        assertEquals(3,p.managed.values.single().startWeek)
    }

    @Test fun movedManagedCourseReportsTheSpecificProtectedBlockingCourse() {
        val known=courses[0];val blocker=courses[1]
        val p=SchoolAutoMerge.plan(ScheduleSnapshot(data(listOf(known,blocker)),SchoolRegistry(mapOf(known.id to known))),
            data(listOf(known.copy(weekday=blocker.weekday),blocker)),today)
        assertEquals(1,p.conflicts);assertEquals(SchoolChanges(),p.changes)
        val evidence=SchoolCheckHistory.details(p,today,applied=false)
        assertTrue(evidence.contains("时间冲突"));assertTrue(evidence.contains("冲突的本地课程：${blocker.name}"))
        assertEquals(listOf(blocker),p.differences.single().blockers)
    }

    @Test fun legacyDeletionCannotResurrectRenamedCourseButCanUpdateKnownCourse() {
        val c=courses[0];val renamed=courses[1].copy(name="可能改名的新课")
        val snapshot=ScheduleSnapshot(data(listOf(c)),SchoolRegistry(mapOf(c.id to c),setOf(courses[2].id)))
        val p=SchoolAutoMerge.plan(snapshot,data(listOf(c.copy(classroom="安全新教室"),renamed)),today)
        assertEquals(SchoolChanges(adjusted=1),p.changes);assertEquals(1,p.conflicts)
        assertFalse(p.data.courses.any {it.name==renamed.name})
        assertTrue(SchoolCheckHistory.details(p,today,true).contains("无法确认新名称"))
    }

    @Test fun previewAndAppliedEvidenceRemainDifferentAndBoundedStorageIsExplicit() {
        val c=courses[0];val changed=c.copy(classroom="新教室")
        val p=SchoolAutoMerge.plan(ScheduleSnapshot(data(listOf(c)),SchoolRegistry(mapOf(c.id to c))),data(listOf(changed)),today)
        assertTrue(SchoolCheckHistory.details(p,today,false).contains("待确认，尚未应用"))
        assertTrue(SchoolCheckHistory.details(p,today,true).contains("已应用（仅今天起）"))
        assertTrue(SchoolCheckHistory.details(p,today,true).contains("第3,4,5,6周"))
        val limited=SchoolCheckHistory.boundedDetails("课".repeat(70000))
        assertTrue(limited.length<=SchoolCheckHistory.MAX_DETAILS);assertTrue(limited.contains("部分课程未展示"))
    }

    @Test fun blockedOnlyPreviewIsCompleteNotWaitingForAnImpossibleConfirmation() {
        val outcome=SchoolCheckOutcome.preview(SchoolChanges(),8,true)
        assertEquals(SchoolCheckPhase.COMPLETE,outcome.phase)
        assertEquals(false,outcome.applied);assertEquals(true,outcome.differences)
        assertTrue(SchoolCheckOutcomeText.from(outcome).note.contains("来源"))
    }

    @Test fun oldCountOnlyCacheRequestsRecheckWithoutRecalculatingOrClearingOldCounts() {
        val old=SchoolCheckOutcome.completed(SchoolChanges(),8)
        for(details in listOf("","新增 0 门，调整 0 门，取消 0 门；8组冲突已保留","更新后\n合成已应用课程")) {
            assertTrue(SchoolCheckHistory.needsConflictRecheck(old,details))
        }
        assertEquals(8,old.protectedGroups);assertEquals(true,old.differences)
        assertFalse(SchoolCheckHistory.needsConflictRecheck(SchoolCheckOutcome.completed(SchoolChanges(),0),""))
        val p=SchoolAutoMerge.plan(ScheduleSnapshot(data(courses),SchoolRegistry()),data(courses.map {it.copy(teacher="学校教师")}),today)
        assertFalse(SchoolCheckHistory.needsConflictRecheck(old,SchoolCheckHistory.details(p,today,true)))
    }
}
