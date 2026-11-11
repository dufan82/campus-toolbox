package cn.campuslife.assistant.schedule

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.Switch
import cn.campuslife.assistant.ui.RetainedWork
import cn.campuslife.assistant.ui.Ui
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DailySyncSettingsActivity:Activity() {
    private class Session {
        val work=RetainedWork<DailySyncSettingsActivity>({Handler(Looper.getMainLooper()).post(it)})
        var state:DailySyncState?=null;var error=""
    }
    private lateinit var session:Session
    private var prompt:AlertDialog?=null
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);session=lastNonConfigurationInstance as? Session ?: Session()
        session.work.bind(this){_,_->render()};render()
    }
    private val prefs by lazy { getSharedPreferences("school-daily-sync-v1", MODE_PRIVATE) }
    private val refreshHandler=Handler(Looper.getMainLooper())
    private val refreshState=Runnable { if (!session.work.busy) refresh() }
    private val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshHandler.removeCallbacks(refreshState);refreshHandler.post(refreshState)
    }
    override fun onResume(){super.onResume();prefs.registerOnSharedPreferenceChangeListener(listener);refresh()}
    override fun onPause(){prefs.unregisterOnSharedPreferenceChangeListener(listener);refreshHandler.removeCallbacks(refreshState);super.onPause()}
    override fun onRetainNonConfigurationInstance():Any=session
    override fun onDestroy(){prompt?.dismiss();session.work.detach(this);if(!isChangingConfigurations)session.work.close();super.onDestroy()}
    private fun refresh(){
        val app=applicationContext
        session.work.submit("读取同步状态…",{DailySyncPreferences(app).read()},{result->
            result.onSuccess {session.state=it;session.error=""}.onFailure {session.error="同步设置暂时无法读取，请返回重试"};render()
        })
    }
    private fun change(enabled:Boolean){
        if(session.work.busy)return
        if(!enabled)DailySchoolSync.active?.set(true)
        val app=applicationContext
        session.work.submit("保存设置…",{DailySchoolSync.setEnabled(app,enabled);DailySyncPreferences(app).read()},{result->
            result.onSuccess {session.state=it;session.error=""}.onFailure {session.error="设置未能保存，请重试"};render()
        })
    }
    private fun render(){
        val page=Ui.page(this);page.addView(Ui.header(this,"每日自动检查课表"))
        val body=Ui.column(this).apply {setPadding(Ui.dp(context,20),Ui.dp(context,16),Ui.dp(context,20),Ui.dp(context,28))}
        page.addView(Ui.scroll(this,body),LinearLayout.LayoutParams(-1,0,1f))
        val state=session.state
        body.addView(Switch(this).apply {
            tag="school_sync_switch";text="每日自动检查课表";textSize=17f;setTextColor(Ui.ink);minimumHeight=Ui.dp(context,56)
            isChecked=state?.enabled==true;isEnabled=state!=null && !session.work.busy
            setOnCheckedChangeListener {_,checked->
                if(checked){
                    isChecked=false
                    prompt=AlertDialog.Builder(this@DailySyncSettingsActivity).setTitle("开启每日课表检查？")
                        .setMessage("每天07:00检查并更新课表，省电或断网时可能延后。\n\n登录并确认学校导入后，会在本机加密保存课表凭据供后台使用；不保存密码。关闭检查或退出学校登录会清除凭据。")
                        .setNegativeButton("暂不开启",null).setPositiveButton("开启"){_,_->change(true)}.show()
                }else if(state?.enabled==true)change(false)
            }
        },LinearLayout.LayoutParams(-1,-2))
        body.addView(Ui.text(this,"每天 07:00",14f,Ui.muted));body.addView(Ui.space(this,22))
        fun time(value:Long)=if(value==0L)"暂无" else DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))
        body.addView(Ui.text(this,"最近检查  ${time(state?.lastAttempt?:0)}",15f).apply {tag="school_sync_attempt"})

        body.addView(Ui.space(this,12));body.addView(Ui.text(this,session.error.ifEmpty {state?.let(DailySyncPresentation::summary)?:"正在读取…"},15f,Ui.muted).apply {tag="school_sync_result"})
        if(state?.enabled==true && state.result==SyncResult.NEEDS_LOGIN){
            body.addView(Ui.space(this,18));body.addView(Ui.button(this,"登录并确认学校导入",true){startActivity(Intent(this,ScheduleTransferActivity::class.java))})
        }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            body.addView(Ui.textButton(this,"允许课表更新通知（可选）"){requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),20701)})
    }
}
