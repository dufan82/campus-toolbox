package cn.campuslife.assistant.planner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.view.View
import android.widget.*
import cn.campuslife.assistant.reminder.ReminderScheduler
import cn.campuslife.assistant.ui.Ui
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

internal class PlannerDraft(
    val kind: Int,
    val id: String = PlannerRules.newId(),
    val baseRevision: String? = null,
    var title: String = "",
    var date: String = "",
    var time: String = "",
    var category: String = "",
    var priority: Int = Priority.NORMAL.ordinal,
    var repeat: Int = Repeat.NONE.ordinal,
    var yearly: Boolean = false,
    var reminder: Boolean = false
) {
    fun save(bundle: Bundle) {
        bundle.putInt("planner.draft.kind",kind); bundle.putString("planner.draft.id",id)
        bundle.putString("planner.draft.revision",baseRevision); bundle.putString("planner.draft.title",title)
        bundle.putString("planner.draft.date",date); bundle.putString("planner.draft.time",time)
        bundle.putString("planner.draft.category",category); bundle.putInt("planner.draft.priority",priority)
        bundle.putInt("planner.draft.repeat",repeat); bundle.putBoolean("planner.draft.yearly",yearly)
        bundle.putBoolean("planner.draft.reminder",reminder)
    }
    companion object {
        fun restore(bundle: Bundle?): PlannerDraft? {
            // Old saved TODO forms must not revive a retired editor after process recreation.
            if(bundle==null || !bundle.containsKey("planner.draft.id") || bundle.getInt("planner.draft.kind")!=1) return null
            return PlannerDraft(bundle.getInt("planner.draft.kind"),bundle.getString("planner.draft.id")!!,
                bundle.getString("planner.draft.revision"),bundle.getString("planner.draft.title", ""),
                bundle.getString("planner.draft.date", ""),bundle.getString("planner.draft.time", ""),
                bundle.getString("planner.draft.category", ""),bundle.getInt("planner.draft.priority",1),
                bundle.getInt("planner.draft.repeat"),bundle.getBoolean("planner.draft.yearly"),bundle.getBoolean("planner.draft.reminder"))
        }
    }
}

/** Retains pending writes and their result across recreation without holding an Activity. */
private class PlannerScreen(private val repo: PlannerRepository) {
    var state: PlannerState?=null
    var readError=false
    var busy=false
    var message=""
    var draft: PlannerDraft?=null
    var changed: (()->Unit)?=null
    private val main=Handler(Looper.getMainLooper())
    fun load() {
        if(busy) return
        busy=true; changed?.invoke()
        repo.load { result -> main.post {
            busy=false; readError=result.isFailure
            result.onSuccess { state=it; message="" }.onFailure {
                message="记录读取失败，原文件已保留；当前只读。请勿卸载或清除数据。可重试，仍失败请联系维护者。"
            }
            changed?.invoke()
        } }
    }
    fun change(clearDraft: Boolean=false, transform: (PlannerState)->PlannerState) {
        if(busy || readError || state==null) return
        busy=true; message="正在保存…"; changed?.invoke()
        repo.change(transform) { result -> main.post {
            busy=false
            result.onSuccess { state=it; message="已保存"; if(clearDraft) draft=null }
                .onFailure { message="保存失败，原记录未更改，表单已保留。请检查存储空间；记录可能已变化，请重新读取后再试。" }
            changed?.invoke()
        } }
    }
}

class PlannerActivity : Activity() {
    private lateinit var screen: PlannerScreen
    private val timeFormat=DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private var formError: TextView?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen=(lastNonConfigurationInstance as? PlannerScreen) ?: PlannerScreen(PlannerRepository.get(this)).apply {
            draft=PlannerDraft.restore(savedInstanceState)
        }
        screen.changed={ if(!isFinishing && !isDestroyed) render() }
        render()
        if(screen.state==null && !screen.busy) screen.load()
    }
    override fun onRetainNonConfigurationInstance(): Any = screen
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        screen.draft?.save(outState)
    }
    override fun onDestroy() { screen.changed=null; super.onDestroy() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        render()
    }
    override fun onResume() {
        super.onResume()
        // Reconcile permission/channel changes on return from settings, without discarding an edit.
        if(::screen.isInitialized && screen.state!=null && !screen.busy) screen.load()
    }
    private fun render() {
        formError=null
        val root=Ui.page(this)
        root.addView(Ui.header(this,"倒计时","记录仅保存在本机"))
        val content=Ui.column(this).apply { setPadding(dp(18),0,dp(18),dp(24)) }
        root.addView(Ui.scroll(this,content),LinearLayout.LayoutParams(-1,0,1f))
        if(screen.message.isNotEmpty()) content.addView(Ui.text(this,screen.message,14f,Ui.muted))
        if(screen.readError) {
            content.addView(Ui.button(this,"重试读取") { screen.load() }.apply { isEnabled=!screen.busy })
            content.addView(Ui.text(this,"为保护原记录，读取恢复前无法新增、编辑或删除。",14f,Ui.muted))
        }
        if(screen.state==null) {
            if(!screen.readError) content.addView(Ui.text(this,"正在读取本机记录…"))
            return
        }
        val writable=!screen.busy && !screen.readError
        if(screen.draft!=null) {
            val form=buildForm(screen.draft!!)
            content.addView(Ui.space(this)); content.addView(form)
            Ui.enabled(form,writable)
        } else {
            content.addView(Ui.space(this))
            content.addView(Ui.button(this,"新增倒计时",true) {
                screen.draft=PlannerDraft(1,time="09:00")
                render()
            }.apply { isEnabled=writable })
        }
        content.addView(Ui.space(this))
        val state=screen.state!!
        if(state.countdowns.isEmpty()) content.addView(Ui.text(this,"暂无倒计时，添加日期开始记录。",14f,Ui.muted))
        state.countdowns.sortedBy { PlannerRules.countdownDate(it,today()) }.forEach {
            content.addView(countdownCard(it,writable && screen.draft==null)); content.addView(Ui.space(this))
        }
        val reminders=state.countdowns.any { it.reminder } || screen.draft?.reminder==true
        if(reminders) {
            content.addView(Ui.text(this,ReminderScheduler(this).unavailableReason()
                ?: "提醒使用系统非精确定时，可能延迟；重启后会重新安排。强行停止应用或厂商省电限制可能导致无法送达。",12f,Ui.muted))
            content.addView(Ui.button(this,"通知设置") { openNotificationSettings() })
        }
    }
    private fun countdownCard(item: CountdownItem, writable: Boolean): LinearLayout = Ui.card(this).apply {
        addView(Ui.text(this@PlannerActivity,item.title,17f,bold=true))
        val days=PlannerRules.days(item,today())
        val label=when { days>0 -> "还有 $days 天"; days==0L -> "就是今天"; else -> "已过 ${-days} 天" }
        addView(Ui.text(this@PlannerActivity,label,25f,Ui.blue,true))
        addView(Ui.text(this@PlannerActivity,"${PlannerRules.countdownDate(item,today())}${if(item.yearly) " · 每年纪念日" else ""}",13f,Ui.muted))
        if(item.reminder) addView(Ui.text(this@PlannerActivity,"当天 ${item.reminderTime.format(timeFormat)} 提醒",12f,Ui.muted))
        actions(this,writable,{ screen.draft=PlannerDraft(1,item.id,item.revision,item.title,item.date.toString(),
            item.reminderTime.format(timeFormat),yearly=item.yearly,reminder=item.reminder); render() },item.id)
    }
    private fun actions(card: LinearLayout,writable: Boolean,edit: ()->Unit,id: String) {
        card.addView(Ui.space(this,8))
        val row=Ui.row(this)
        row.addView(Ui.button(this,"编辑",onClick=edit).apply { isEnabled=writable },LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(8) })
        row.addView(Ui.button(this,"删除") {
            AlertDialog.Builder(this).setTitle("删除这条记录？").setMessage("删除后同时取消它的提醒。")
                .setNegativeButton("取消",null).setPositiveButton("删除") { _,_ -> screen.change { it.without(id) } }.show()
        }.apply { isEnabled=writable },LinearLayout.LayoutParams(0,-2,1f))
        card.addView(row)
    }
    private fun buildForm(draft: PlannerDraft): LinearLayout = Ui.card(this).apply {
        addView(Ui.text(this@PlannerActivity,if(draft.baseRevision==null) "新增倒计时" else "编辑记录",18f,bold=true))
        field(this,"标题（必填，最多 120 字）",draft.title,"输入标题","planner-title",120) { draft.title=it }
        field(this,"目标日期（必填，YYYY-MM-DD）",draft.date,
            "例如 2026-12-31","planner-date",10) { draft.date=it }
        field(this,"当天提醒时间（HH:mm）",draft.time,"例如 09:00","planner-time",5) { draft.time=it }
        addView(check("每年重复纪念日（2 月 29 日在平年按 2 月末）",draft.yearly) { draft.yearly=it })
        addView(check("开启本条本机提醒（默认关闭）",draft.reminder) { enabled ->
            draft.reminder=enabled
            if(enabled) {
                ReminderScheduler(this@PlannerActivity).ensureChannel()
                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),PERMISSION_REQUEST)
                else ReminderScheduler(this@PlannerActivity).unavailableReason()?.let { Ui.toast(this@PlannerActivity,it) }
            }
        })
        addView(Ui.text(this@PlannerActivity,"提醒需要日期和时间；不会补发很久以前的提醒。记录可独立使用，无需课程表。",12f,Ui.muted))
        formError=Ui.text(this@PlannerActivity,"",14f,Ui.ink).also { addView(it) }
        val buttons=Ui.row(this@PlannerActivity)
        buttons.addView(Ui.button(this@PlannerActivity,"保存",true) { saveDraft(draft) },LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(8) })
        buttons.addView(Ui.button(this@PlannerActivity,"取消") { screen.draft=null; render() },LinearLayout.LayoutParams(0,-2,1f))
        addView(buttons)
    }
    private fun field(parent: LinearLayout,label: String,value: String,hint: String,tagValue: String,max: Int,changed: (String)->Unit) {
        parent.addView(Ui.space(this,8)); val labelView=Ui.text(this,label,13f,Ui.muted)
        parent.addView(labelView)
        parent.addView(Ui.input(this,value,hint,changed=changed).apply {
            tag=tagValue; id=View.generateViewId(); labelView.labelFor=id
            isSaveEnabled=false // Draft owns state; generated view IDs must not restore into a different field.
            filters=arrayOf(InputFilter.LengthFilter(max))
        })
    }
    private fun check(label: String,value: Boolean,changed: (Boolean)->Unit) = CheckBox(this).apply {
        text=label; isChecked=value; textSize=14f; setTextColor(Ui.ink); minHeight=dp(48); isSaveEnabled=false
        setOnCheckedChangeListener { _,checked -> changed(checked) }
    }
    private fun saveDraft(draft: PlannerDraft) {
        try {
            val date=draft.date.trim().takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
            val time=draft.time.trim().takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it,timeFormat) }
            require(draft.kind==1 && date!=null) { "请填写目标日期" }
            require(!draft.reminder || time!=null) { "开启提醒需填写时间" }
            val countdown=CountdownItem(draft.id,draft.title.trim(),requireNotNull(date),draft.yearly,
                draft.reminder,time ?: LocalTime.of(9,0))
            screen.change(clearDraft=true) { state ->
                val revision=state.countdowns.find { it.id==draft.id }?.revision
                require(revision==draft.baseRevision) { "Record changed while editing" }
                state.copy(countdowns=state.countdowns.filterNot { it.id==draft.id }+countdown)
            }
        } catch(error: Exception) {
            formError?.text=if(error is java.time.format.DateTimeParseException) "日期请用 YYYY-MM-DD，时间请用 24 小时制 HH:mm，并填写真实日期。"
                else if(error is IllegalArgumentException) error.message ?: "请检查填写内容" else "请检查填写内容"
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==PERMISSION_REQUEST) {
            screen.message=if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) "通知权限已开启，请保存记录以安排提醒。"
                else "通知权限未授予，仍可保存、编辑倒计时。提醒暂不显示，可在系统设置开启。"
            render()
        }
    }
    private fun openNotificationSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName)) }
            .onFailure { Ui.toast(this,"请到系统设置中的应用通知页面开启") }
    }
    private fun today()=LocalDate.now(ZoneId.systemDefault())
    private fun dp(value: Int)=Ui.dp(this,value)
    companion object { private const val PERMISSION_REQUEST=1601 }
}
