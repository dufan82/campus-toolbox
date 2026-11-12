package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test

class SchoolCheckOutcomeTest {
    @Test fun automaticPartialApplicationIsNotConfusedWithNoUpdate() {
        val info=SchoolCheckOutcome.completed(SchoolChanges(1,2,0),3)
        val ui=SchoolCheckOutcomeText.from(info)
        assertEquals("已完成检查",ui.check)
        assertTrue(ui.school.contains("发现差异"))
        assertTrue(ui.local.contains("已更新"));assertTrue(ui.local.contains("调整 2"))
        assertTrue(ui.note.contains("3"));assertTrue(ui.note.contains("未应用"))
    }
    @Test fun protectedOnlyDifferencesAreExplicitlyNotApplied() {
        val ui=SchoolCheckOutcomeText.from(SchoolCheckOutcome.completed(SchoolChanges(),2))
        assertEquals("发现差异",ui.school)
        assertTrue(ui.local.contains("未应用"));assertFalse(ui.local.contains("已更新"))
    }
    @Test fun previewDoesNotClaimTheScheduleWasSaved() {
        val ui=SchoolCheckOutcomeText.from(SchoolCheckOutcome.preview(SchoolChanges(0,2,0),0,true))
        assertTrue(ui.check.contains("已完成"));assertTrue(ui.local.contains("待确认"));assertTrue(ui.local.contains("尚未应用"))
    }
    @Test fun unchangedCompletedCheckDoesNotSuggestAnUnfinishedTask() {
        val ui=SchoolCheckOutcomeText.from(SchoolCheckOutcome.completed(SchoolChanges(),0))
        assertEquals("未发现变化",ui.school);assertEquals("无需更新",ui.local)
    }
    @Test fun uncertainFailureNeverClaimsTheFileWasNotChanged() {
        val ui=SchoolCheckOutcomeText.from(SchoolCheckOutcome.failed(untouched=false))
        assertTrue(ui.local.contains("待确认"));assertEquals("未能确认",ui.school)
        assertEquals("未执行更新",SchoolCheckOutcomeText.from(SchoolCheckOutcome.failed(untouched=true)).local)
    }
    @Test fun outcomeWireFormatRoundTripsAndRejectsCorruption() {
        val value=SchoolCheckOutcome.completed(SchoolChanges(2,3,1),4)
        assertEquals(value,SchoolCheckOutcome.decode(value.encode()))
        assertNull(SchoolCheckOutcome.decode(""));assertNull(SchoolCheckOutcome.decode("1|COMPLETE|y|y|-1,2,3|4"))
        assertNull(SchoolCheckOutcome.decode("1|FUTURE|y|y|1,2,3|4"))
    }
    @Test fun legacyConflictOnlyUsesTheKnownNumericSummaryNotVagueText() {
        val old=DailySyncState(enabled=true,lastAttempt=100,lastSuccess=100,result=SyncResult.CONFLICT,summary="新增 1 门，调整 2 门，取消 0 门；3组冲突已保留")
        val record=SchoolCheckHistory.latest(old,null)!!
        assertTrue(SchoolCheckOutcomeText.from(record.outcome!!).local.contains("已更新"))
        val vague=SchoolCheckHistory.latest(old.copy(summary="部分课程已保留"),null)!!
        assertTrue(SchoolCheckOutcomeText.from(vague.outcome!!).local.contains("待确认"))
    }
    @Test fun disablingAutomaticCheckKeepsLastApplicationResultVisible() {
        val state=DailySyncState(enabled=false,lastAttempt=100,result=SyncResult.UPDATED,outcome=SchoolCheckOutcome.completed(SchoolChanges(0,1,0),0))
        val record=SchoolCheckHistory.latest(state,null)!!
        assertTrue(SchoolCheckOutcomeText.from(record.outcome!!).local.contains("调整 1"))
    }
}
