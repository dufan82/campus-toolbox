package cn.campuslife.assistant.schedule

import android.app.job.*
import android.content.*
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

object DailySyncScheduler {
    const val JOB_ID=20700
    const val TASK_NAME="school-timetable-daily-0700-v1"
    fun reconcile(context:Context,reset:Boolean=false,now:ZonedDateTime=ZonedDateTime.now())=synchronized(DailySchoolSync.lock) {
        val app=context.applicationContext;val jobs=app.getSystemService(JobScheduler::class.java)
        val prefs=DailySyncPreferences(app)
        var state=prefs.read()
        if(state.enabled && state.result==SyncResult.CHECKING && DailySchoolSync.active==null) {
            state=state.copy(result=SyncResult.INTERRUPTED,lastAttemptDay="",summary="上次检查被系统中断，等待网络恢复后补查")
            prefs.write(state)
        }
        if(!state.enabled || state.result==SyncResult.NEEDS_LOGIN){jobs.cancel(JOB_ID);return@synchronized}
        val previous=jobs.getPendingJob(JOB_ID)
        if(!reset && previous?.extras?.getLong("generation")==state.generation)return@synchronized
        val due=DailySyncTiming.due(state,now)
        val extras=PersistableBundle().apply {putString("name",TASK_NAME);putLong("generation",state.generation);putLong("plannedAt",due.toInstant().toEpochMilli())}
        val job=JobInfo.Builder(JOB_ID,ComponentName(app,DailySchoolSyncJob::class.java)).setExtras(extras)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
            .setMinimumLatency(Duration.between(now,due).toMillis().coerceAtLeast(0))
            .setBackoffCriteria(30*60*1000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        if(jobs.schedule(job)!=JobScheduler.RESULT_SUCCESS) {
            DailySyncPreferences(app).write(state.copy(summary="系统未能安排检查，请重新打开同步设置"))
        }
    }
}

class DailySchoolSyncJob:JobService() {
    private class Run(val params:JobParameters,val cancelled:AtomicBoolean=AtomicBoolean(),var stopped:Boolean=false)
    private var running:Run?=null
    override fun onStartJob(params:JobParameters):Boolean {
        val run=Run(params);running=run
        DailySchoolSync.worker.execute {
            try {
                val state=DailySyncPreferences(applicationContext).read()
                if(state.generation==params.extras.getLong("generation") && !run.cancelled.get()) {
                    DailySchoolSync.active=run.cancelled
                    DailySyncEngine(DailySchoolSync.repository(applicationContext)).run(ZonedDateTime.now(),run.cancelled)
                }
            }finally {
                if(DailySchoolSync.active===run.cancelled)DailySchoolSync.active=null
                Handler(Looper.getMainLooper()).post {
                    if(running===run)running=null
                    if(!run.stopped) {
                        jobFinished(params,false)
                        DailySchoolSync.worker.execute {runCatching {DailySyncScheduler.reconcile(applicationContext,reset=true)}}
                    }
                }
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        running?.takeIf {it.params.jobId==params.jobId && it.params.extras.getLong("generation")==params.extras.getLong("generation")}
            ?.let {it.stopped=true;it.cancelled.set(true)}
        val state=DailySyncPreferences(applicationContext).read()
        return state.enabled && state.result!=SyncResult.NEEDS_LOGIN
    }
}
class DailySyncRescheduleReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED))return
        val pending=goAsync()
        DailySchoolSync.worker.execute {try{DailySyncScheduler.reconcile(context,reset=true)}finally{pending.finish()}}
    }
}
