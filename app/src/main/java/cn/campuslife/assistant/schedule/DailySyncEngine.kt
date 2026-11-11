package cn.campuslife.assistant.schedule

import java.io.IOException
import java.io.InterruptedIOException
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

class SchoolAuthenticationException : IOException("School authentication expired")
enum class SyncResult { READY, CHECKING, NEEDS_LOGIN, NO_CHANGE, UPDATED, CONFLICT, NETWORK_ERROR, INVALID_DATA, INTERRUPTED, SKIPPED }
data class DailySyncState(val enabled:Boolean=false,val generation:Long=0,val lastAttempt:Long=0,
    val lastSuccess:Long=0,val lastAttemptDay:String="",val result:SyncResult=SyncResult.READY,val summary:String="",val details:String="")
/** Contains only the explicitly authorised timetable token, never passwords or WebView cookies. */
class SchoolSyncCredential(val authorization:String,val semester:Semester) {
    override fun toString() = "SchoolSyncCredential(redacted)"
}
data class SchoolFetch(val data:ScheduleData,val complete:Boolean=true)
interface DailySyncRepository {
    fun state():DailySyncState
    fun update(generation:Long,transform:(DailySyncState)->DailySyncState)
    fun credential():SchoolSyncCredential?
    fun clearCredential(generation:Long)
    fun snapshot():ScheduleSnapshot
    fun fetch(credential:SchoolSyncCredential,cancelled:AtomicBoolean):SchoolFetch
    fun apply(expected:ScheduleSnapshot,school:ScheduleData,today:LocalDate,generation:Long):SchoolAutoPlan
    fun notify(changes:SchoolChanges)
}
object DailySyncTiming {
    fun next(now:ZonedDateTime):ZonedDateTime {
        val seven=now.toLocalDate().atTime(7,0).atZone(now.zone)
        return if(seven.isAfter(now))seven else now.toLocalDate().plusDays(1).atTime(7,0).atZone(now.zone)
    }
    fun due(state:DailySyncState,now:ZonedDateTime):ZonedDateTime =
        if(now.hour>=7 && state.lastAttemptDay!=now.toLocalDate().toString())now else next(now)
}
/** Process-wide single flight plus a durable local-day attempt key. The store performs the final CAS. */
class DailySyncEngine(private val repo:DailySyncRepository) {
    fun run(now:ZonedDateTime,cancelled:AtomicBoolean=AtomicBoolean()):SyncResult {
        if(!gate.tryLock())return SyncResult.SKIPPED
        try {
            val initial=repo.state();val generation=initial.generation
            if(!initial.enabled || cancelled.get() || initial.result==SyncResult.NEEDS_LOGIN || initial.lastAttemptDay==now.toLocalDate().toString())return SyncResult.SKIPPED
            fun active()=repo.state().let {it.enabled && it.generation==generation} && !cancelled.get()
            fun checkActive(){if(!active())throw InterruptedIOException("Sync stopped")}
            repo.update(generation){it.copy(lastAttempt=now.toInstant().toEpochMilli(),lastAttemptDay=now.toLocalDate().toString(),result=SyncResult.CHECKING,summary="",details="")}
            var result:SyncResult
            try {
                checkActive()
                val credential=repo.credential() ?: throw SchoolAuthenticationException()
                val snapshot=repo.snapshot()
                require(snapshot.data.semester==credential.semester){"课表学期已变化，请重新确认学校导入"}
                checkActive()
                val fetched=repo.fetch(credential,cancelled)
                checkActive()
                require(fetched.complete){"学校课表未完整取得"}
                fetched.data.checked()
                require(fetched.data.semester==credential.semester){"学校当前学期与本地不一致"}
                val plan=repo.apply(snapshot,fetched.data,now.toLocalDate(),generation)
                result=when {plan.conflicts>0->SyncResult.CONFLICT;plan.changes.total>0->SyncResult.UPDATED;else->SyncResult.NO_CHANGE}
                repo.update(generation){it.copy(lastSuccess=now.toInstant().toEpochMilli(),result=result,details=SchoolCheckHistory.details(snapshot.data,plan.data,now.toLocalDate()),
                    summary=if(plan.conflicts>0)"${plan.changes.summary()}；${plan.conflicts}组冲突已保留" else if(plan.changes.total>0)plan.changes.summary() else "课表没有变化")}
                if(plan.changes.total>0 && active())runCatching {repo.notify(plan.changes)}
            }catch(_:SchoolAuthenticationException){
                repo.clearCredential(generation);result=SyncResult.NEEDS_LOGIN
                repo.update(generation){it.copy(result=result,summary="需要重新登录学校平台并确认导入，原课表已保留")}
            }catch(_:InterruptedIOException){
                result=if(cancelled.get())SyncResult.INTERRUPTED else SyncResult.NETWORK_ERROR
                repo.update(generation){it.copy(result=result,summary="检查中断或网络超时，原课表保留",
                    lastAttemptDay=if(cancelled.get())"" else it.lastAttemptDay)}
            }catch(_:IOException){
                result=SyncResult.NETWORK_ERROR
                repo.update(generation){it.copy(result=result,summary="网络或平台暂时不可用，原课表保留")}
            }catch(_:Exception){
                result=SyncResult.INVALID_DATA
                repo.update(generation){it.copy(result=result,summary="数据不完整、学期/作息不一致或本地已变化，未应用更新")}
            }
            return result
        }finally{gate.unlock()}
    }
    companion object {private val gate=ReentrantLock()}
}
