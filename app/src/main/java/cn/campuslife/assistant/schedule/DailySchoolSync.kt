package cn.campuslife.assistant.schedule

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import cn.campuslife.assistant.R
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** All enable/auth/commit transitions use the same lock; disabling cancels in-flight network work. */
object DailySchoolSync {
    internal val lock=Any()
    val worker=Executors.newSingleThreadExecutor()
    @Volatile internal var active:AtomicBoolean?=null
    fun setEnabled(context:Context,enabled:Boolean) {
        synchronized(lock) {
            active?.set(true)
            val store=DailySyncPreferences(context);val old=store.read()
            if(!enabled)SchoolSyncVault(context).clear()
            val hasCredential=enabled && SchoolSyncVault(context).read()!=null
            store.write(old.copy(enabled=enabled,generation=old.generation+1,lastAttemptDay="",
                result=if(enabled && !hasCredential)SyncResult.NEEDS_LOGIN else if(old.result==SyncResult.CHECKING)SyncResult.INTERRUPTED else old.result,
                summary=if(enabled && !hasCredential)"请登录学校平台并确认导入当前学期，启用后台凭据" else old.summary))
        }
        DailySyncScheduler.reconcile(context,reset=true)
    }
    /** Only after explicit confirmation of a complete school term, never on mere page/login detection. */
    fun authorizeAfterImport(context:Context,authorization:String?,data:ScheduleData):Boolean = synchronized(lock) {
        val prefs=DailySyncPreferences(context);val old=prefs.read()
        if(!old.enabled)return@synchronized false
        val semester=data.semester ?: return@synchronized false
        if(authorization==null)return@synchronized false
        try {
            active?.set(true)
            SchoolSyncVault(context).write(SchoolSyncCredential(authorization,semester))
            prefs.write(old.copy(generation=old.generation+1,result=SyncResult.READY,lastAttemptDay=LocalDate.now().toString(),summary="学校登录已确认，将在下次07:00检查"))
            DailySyncScheduler.reconcile(context,reset=true)
            true
        }catch(_:Exception){
            SchoolSyncVault(context).clear()
            runCatching {prefs.write(old.copy(result=SyncResult.NEEDS_LOGIN,summary="凭据未能加密保存，后台检查暂停；本地课表已保存"))}
            false
        }
    }
    fun forgetLogin(context:Context) {
        synchronized(lock){
            active?.set(true);SchoolSyncVault(context).clear()
            val prefs=DailySyncPreferences(context);val old=prefs.read()
            prefs.write(old.copy(generation=old.generation+1,result=SyncResult.NEEDS_LOGIN,summary="学校登录已清除，自动检查暂停"))
        }
        DailySyncScheduler.reconcile(context,reset=true)
    }
    fun repository(context:Context):DailySyncRepository {
        val app=context.applicationContext;val prefs=DailySyncPreferences(app);val vault=SchoolSyncVault(app);val store=ScheduleStore(app)
        return object:DailySyncRepository {
            override fun state()=synchronized(lock){prefs.read()}
            override fun update(generation:Long,transform:(DailySyncState)->DailySyncState)=synchronized(lock){
                val current=prefs.read();if(current.enabled && current.generation==generation)prefs.write(transform(current))
            }
            override fun credential()=synchronized(lock){vault.read()}
            override fun clearCredential(generation:Long)=synchronized(lock){if(prefs.read().generation==generation)vault.clear()}
            override fun snapshot()=store.loadSnapshot()
            override fun fetch(credential:SchoolSyncCredential,cancelled:AtomicBoolean):SchoolFetch {
                val client=SchoolPortalClient.network(credential.authorization,cancelled)
                val term=SchoolPortalJson.currentTerm(client.terms()) ?: error("未取得唯一当前学期")
                val result=client.collect(term,cancelled){_,_->}
                return SchoolFetch(result.data,result.warnings.isEmpty())
            }
            override fun apply(expected:ScheduleSnapshot,school:ScheduleData,today:LocalDate,generation:Long)=synchronized(lock){
                val current=prefs.read();check(current.enabled && current.generation==generation && active?.get()!=true)
                store.applyAutomatic(expected,school,today)
            }
            override fun notify(changes:SchoolChanges) {
                if(Build.VERSION.SDK_INT>=33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
                val manager=app.getSystemService(NotificationManager::class.java)
                if(!manager.areNotificationsEnabled())return
                manager.createNotificationChannel(NotificationChannel("school-sync","课表更新",NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description="仅在每日检查实际更新课程后提醒";lockscreenVisibility=Notification.VISIBILITY_PRIVATE
                })
                val intent=PendingIntent.getActivity(app,20701,Intent(app,ScheduleActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(20701,Notification.Builder(app,"school-sync").setSmallIcon(R.drawable.ic_app)
                    .setContentTitle("课表已更新").setContentText(changes.summary()).setContentIntent(intent).setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).build())
            }
        }
    }
}
