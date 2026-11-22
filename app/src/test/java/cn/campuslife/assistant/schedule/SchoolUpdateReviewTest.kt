package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SchoolUpdateReviewTest {
    private val date=LocalDate.of(2026,11,13)
    private val c=Course("00000000-0000-0000-0000-000000000901","C4D三维设计","教师","文科楼303",5,7,10,1,6,WeekPattern.EVERY,null)
    private val local=ScheduleData(Semester(date.minusDays(4).minusWeeks(2),6),courses=listOf(c))
    private val school=local.copy(courses=listOf(c.copy(id="00000000-0000-0000-0000-000000000902",classroom="文科楼205")))
    @Test fun legacyChangeCanBePreviewedAndOfferedWithoutClaimingOrSavingIt() {
        val snapshot=ScheduleSnapshot(local,SchoolRegistry())
        val review=SchoolUpdateReview.plan(snapshot,school,date)
        assertEquals(setOf(c.name),review.legacyToConfirm)
        assertEquals(1,review.preview.plan.changes.adjusted)
        assertEquals(0,review.preview.plan.conflicts)
        assertEquals(local,snapshot.data);assertTrue(snapshot.registry.managed.isEmpty())
        assertEquals(local,SchoolAutoMerge.plan(snapshot,school,date).data)
    }
    @Test fun beginnerSummaryNamesTheChangeAndSeparatesPendingFromSaved() {
        val p=SchoolUpdateReview.plan(ScheduleSnapshot(local,SchoolRegistry()),school,date).preview.plan
        val pending=SchoolUpdateReview.summary(p,date,false)
        assertTrue(pending.contains("C4D三维设计 · 待更新"))
        assertTrue(pending.contains("教室：文科楼303 → 文科楼205"))
        assertTrue(pending.contains("周五 · 第7–10节"))
        assertFalse(pending.contains("已更新"));assertFalse(pending.contains("202501"))
        val saved=SchoolUpdateReview.summary(p,date,true)
        assertTrue(saved.contains("C4D三维设计 · 已更新"))
        assertTrue(saved.contains("文科楼303 → 文科楼205"))
    }
    @Test fun explicitKnownEditStillHasNoUpdateProposalAndSaysWhy() {
        val snap=ScheduleSnapshot(local,SchoolRegistry(protectedIds=setOf(c.id),protectedCourses=mapOf(c.id to c)))
        val r=SchoolUpdateReview.plan(snap,school,date)
        assertTrue(r.legacyToConfirm.isEmpty());assertEquals(local,r.preview.plan.data)
        val text=SchoolUpdateReview.summary(r.preview.plan,date,false)
        assertTrue(text.contains("未更新"));assertTrue(text.contains("手动修改"))
        assertFalse(text.contains("已更新"))
    }
    @Test fun noChangeDoesNotOfferOwnershipOrInventChanges() {
        val p=SchoolUpdateReview.plan(ScheduleSnapshot(local,SchoolRegistry()),local,date)
        assertTrue(p.legacyToConfirm.isEmpty());assertEquals(0,p.preview.plan.changes.total)
        assertEquals("",SchoolUpdateReview.summary(p.preview.plan,date,false))
    }
    @Test fun teachingTimeAndTeacherChangesAlsoHaveExplicitBeforeAndAfter() {
        val remote=school.copy(courses=school.courses.map {it.copy(teacher="新教师",weekday=4)})
        val p=SchoolUpdateReview.plan(ScheduleSnapshot(local,SchoolRegistry()),remote,date).preview.plan
        val text=SchoolUpdateReview.summary(p,date,false)
        assertTrue(text.contains("教师：教师 → 新教师"))
        assertTrue(text.contains("周五"));assertTrue(text.contains("周四"));assertTrue(text.contains("→"))
    }
    @Test fun partialUpdateNeverLabelsProtectedChangesAsApplied() {
        val protected=c.copy(id="00000000-0000-0000-0000-000000000903",name="手动课程",weekday=2)
        val data=local.copy(courses=listOf(c,protected))
        val snapshot=ScheduleSnapshot(data,SchoolRegistry(managed=mapOf(c.id to c),protectedIds=setOf(protected.id),protectedCourses=mapOf(protected.id to protected)))
        val remote=data.copy(courses=data.courses.map {it.copy(classroom="205")})
        val review=SchoolUpdateReview.plan(snapshot,remote,date)
        assertEquals(1,review.preview.plan.changes.adjusted);assertEquals(1,review.preview.plan.conflicts)
        val summary=SchoolUpdateReview.summary(review.preview.plan,date,true)
        assertTrue(summary.contains("C4D三维设计 · 已更新"));assertTrue(summary.contains("手动课程 · 未更新"))
        assertFalse(summary.contains("手动课程 · 已更新"))
    }
    @Test fun multipleSessionsKeepRoomAndTimeAssociationsRatherThanHidingASwap() {
        val second=c.copy(id="00000000-0000-0000-0000-000000000904",classroom="205",weekday=4)
        val data=local.copy(courses=listOf(c,second))
        val remote=data.copy(courses=listOf(c.copy(classroom="205"),second.copy(classroom="文科楼303")))
        val p=SchoolUpdateReview.plan(ScheduleSnapshot(data,SchoolRegistry(managed=data.courses.associateBy {it.id})),remote,date).preview.plan
        val text=SchoolUpdateReview.summary(p,date,false)
        assertTrue(text.contains("原安排："));assertTrue(text.contains("新安排："))
        assertTrue(text.contains("周五 · 第7–10节 · 文科楼303"))
        assertTrue(text.contains("周五 · 第7–10节 · 205"))
    }
}
