package cn.campuslife.assistant.schedule
import org.junit.Assert.*
import org.junit.Test
class DailySyncPresentationTest {
    @Test fun checkingWithEmptySummaryDoesNotClaimNeverRun() {
        val state=DailySyncState(enabled=true,lastAttempt=1234,result=SyncResult.CHECKING)
        assertEquals("正在检查…",DailySyncPresentation.summary(state))
    }
    @Test fun resultsHaveShortAccurateFallbacksWithoutRawStoredText() {
        val expected=mapOf(SyncResult.NEEDS_LOGIN to "需要登录学校平台",SyncResult.NO_CHANGE to "课表没有变化",SyncResult.UPDATED to "课表已更新",SyncResult.NETWORK_ERROR to "检查失败，请稍后重试",SyncResult.INTERRUPTED to "检查已中断",SyncResult.INVALID_DATA to "课表未更新，请重新检查")
        expected.forEach {(result,text)->assertEquals(text,DailySyncPresentation.summary(DailySyncState(enabled=true,lastAttempt=1234,result=result)))}
    }
    @Test fun disabledAndNeverRunAreDifferentFromIncompleteAttempt() {
        assertEquals("自动检查已关闭",DailySyncPresentation.summary(DailySyncState()))
        assertEquals("等待首次检查",DailySyncPresentation.summary(DailySyncState(enabled=true)))
        assertEquals("上次检查未完成",DailySyncPresentation.summary(DailySyncState(enabled=true,lastAttempt=1234)))
    }
}
