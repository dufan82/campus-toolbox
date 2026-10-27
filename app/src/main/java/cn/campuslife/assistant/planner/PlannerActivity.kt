package cn.campuslife.assistant.planner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.campuslife.assistant.reminder.ReminderScheduler
import cn.campuslife.assistant.ui.Ui
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

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
    var reminder: Boolean = false,
    var preset: CountdownPreset = CountdownPreset.fromTitle(title),
    var customTitle: String = if(preset==CountdownPreset.CUSTOM) title else ""
) {
    fun save(bundle: Bundle) {
        bundle.putInt("planner.draft.kind",kind); bundle.putString("planner.draft.id",id)
        bundle.putString("planner.draft.revision",baseRevision); bundle.putString("planner.draft.title",title)
        bundle.putString("planner.draft.date",date); bundle.putString("planner.draft.time",time)
        bundle.putString("planner.draft.category",category); bundle.putInt("planner.draft.priority",priority)
        bundle.putInt("planner.draft.repeat",repeat); bundle.putBoolean("planner.draft.yearly",yearly)
        bundle.putBoolean("planner.draft.reminder",reminder)
        bundle.putString("planner.draft.preset",preset.name); bundle.putString("planner.draft.customTitle",customTitle)
    }
    companion object {
        fun restore(bundle: Bundle?): PlannerDraft? {
            // Old saved TODO forms must not revive a retired editor after process recreation.
            if(bundle==null || !bundle.containsKey("planner.draft.id") || bundle.getInt("planner.draft.kind")!=1) return null
            return PlannerDraft(bundle.getInt("planner.draft.kind"),bundle.getString("planner.draft.id")!!,
                bundle.getString("planner.draft.revision"),bundle.getString("planner.draft.title", ""),
                bundle.getString("planner.draft.date", ""),bundle.getString("planner.draft.time", ""),
                bundle.getString("planner.draft.category", ""),bundle.getInt("planner.draft.priority",1),
                bundle.getInt("planner.draft.repeat"),bundle.getBoolean("planner.draft.yearly"),bundle.getBoolean("planner.draft.reminder")).apply {
                    preset=runCatching { CountdownPreset.valueOf(bundle.getString("planner.draft.preset").orEmpty()) }.getOrDefault(preset)
                    customTitle=bundle.getString("planner.draft.customTitle",customTitle)
                }
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
    private var picker: Dialog?=null
    private val dateFormat=DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE",Locale.SIMPLIFIED_CHINESE)
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
    override fun onDestroy() { picker?.dismiss(); picker=null; screen.changed=null; super.onDestroy() }
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
        // Keep the form's actions above the keyboard without changing other tool pages.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,insets.getInsets(WindowInsetsCompat.Type.ime()).bottom))
            insets
        }
        val draft=screen.draft
        root.addView(Ui.header(this,if(draft==null) "倒数日" else if(draft.baseRevision==null) "添加日子" else "编辑日子"))
        val content=Ui.column(this).apply { setPadding(dp(18),0,dp(18),dp(20)) }
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
        if(draft!=null) {
            val form=buildForm(draft)
            content.addView(form)
            Ui.enabled(form,writable)
        } else {
            val state=screen.state!!
            content.addView(Ui.text(this,"记住值得期待的日子",14f,Ui.muted))
            content.addView(Ui.space(this,16))
            if(state.countdowns.isEmpty()) content.addView(Ui.card(this).apply {
                addView(Ui.text(this@PlannerActivity,"把重要的日子留在这里",18f,bold=true))
                addView(Ui.space(this@PlannerActivity,8))
                addView(Ui.text(this@PlannerActivity,"生日、纪念日、体测，也可以写自己的安排。",14f,Ui.muted))
            })
            state.countdowns.sortedBy { PlannerRules.countdownDate(it,today()) }.forEach {
                content.addView(countdownCard(it,writable)); content.addView(Ui.space(this))
            }
            val footer=Ui.column(this).apply { setPadding(dp(18),dp(8),dp(18),dp(12)) }
            footer.addView(Ui.button(this@PlannerActivity,"新增倒计时",true) {
                screen.draft=PlannerDraft(1,title="生日",date=today().toString(),time="09:00",yearly=true)
                render()
            }.apply { isEnabled=writable })
            root.addView(footer)
        }
        val reminders=screen.state!!.countdowns.any { it.reminder } || draft?.reminder==true
        if(reminders) {
            ReminderScheduler(this).unavailableReason()?.let { reason ->
                content.addView(Ui.space(this,12)); content.addView(Ui.text(this,reason,13f,Ui.muted))
                content.addView(Ui.button(this,"通知设置") { openNotificationSettings() })
            }
        }
    }
    private fun countdownCard(item: CountdownItem, writable: Boolean): LinearLayout = Ui.card(this).apply {
        val days=PlannerRules.days(item,today())
        val label=when { days>0 -> "还有 $days 天"; days==0L -> "就是今天"; else -> "已过 ${-days} 天" }
        addView(Ui.text(this@PlannerActivity,item.title,18f,bold=true))
        addView(Ui.space(this@PlannerActivity,12))
        addView(Ui.text(this@PlannerActivity,label,32f,Ui.blue,true))
        addView(Ui.space(this@PlannerActivity,12))
        val target=PlannerRules.countdownDate(item,today())
        addView(Ui.text(this@PlannerActivity,target.format(dateFormat),13f,Ui.muted))
        val extras=buildList {
            if(item.yearly) add("每年重复")
            if(item.reminder) add("当天 ${item.reminderTime.format(timeFormat)} 提醒")
        }
        if(extras.isNotEmpty()) { addView(Ui.space(this@PlannerActivity,6)); addView(Ui.text(this@PlannerActivity,extras.joinToString(" · "),12f,Ui.muted)) }
        tag="countdown-${item.id}"; isEnabled=writable; isFocusable=true
        background=RippleDrawable(ColorStateList.valueOf(0x220c6b58),background,null)
        contentDescription="${item.title}，$label，${target.format(dateFormat)}，点按编辑"
        accessibilityDelegate=object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host,info); info.className=Button::class.java.name
            }
        }
        setOnClickListener {
            if(writable) {
                screen.draft=PlannerDraft(1,item.id,item.revision,item.title,item.date.toString(),
                    item.reminderTime.format(timeFormat),yearly=item.yearly,reminder=item.reminder)
                render()
            }
        }
    }
    private fun buildForm(draft: PlannerDraft): LinearLayout = Ui.card(this).apply {
        addView(Ui.text(this@PlannerActivity,"什么日子",14f,bold=true))
        addView(Ui.space(this@PlannerActivity,8))
        addView(Ui.button(this@PlannerActivity,"${draft.preset.label}  ▾") {
            AlertDialog.Builder(this@PlannerActivity).setTitle("选择事项")
                .setItems(CountdownPreset.entries.map { it.label }.toTypedArray()) { _,index ->
                    val next=CountdownPreset.entries[index]
                    if(next!=draft.preset) {
                        if(draft.preset==CountdownPreset.CUSTOM) draft.customTitle=draft.title
                        draft.preset=next
                        draft.title=if(next==CountdownPreset.CUSTOM) draft.customTitle else next.label
                        draft.yearly=next.yearlyDefault
                        render()
                    }
                }.setNegativeButton("取消",null).show()
        }.apply { tag="planner-preset"; gravity=Gravity.START or Gravity.CENTER_VERTICAL; contentDescription="选择事项，当前${draft.preset.label}" })
        if(draft.preset==CountdownPreset.CUSTOM) {
            field(this,"标题",draft.title,"写下想记录的日子","planner-title",120) { draft.title=it; draft.customTitle=it }
        }
        addView(Ui.space(this@PlannerActivity,24))
        addView(Ui.text(this@PlannerActivity,"哪一天",14f,bold=true))
        addView(Ui.space(this@PlannerActivity,8))
        val date=runCatching { LocalDate.parse(draft.date) }.getOrNull()
        addView(Ui.button(this@PlannerActivity,date?.format(dateFormat) ?: "选择日期") { chooseDate(draft) }
            .apply { tag="planner-date"; gravity=Gravity.START or Gravity.CENTER_VERTICAL; contentDescription="选择日期，${date?.format(dateFormat) ?: "尚未选择"}" })
        addView(Ui.space(this@PlannerActivity,12))
        addView(check("每年重复",draft.yearly) { draft.yearly=it })
        addView(check("当天提醒我",draft.reminder) { enabled ->
            draft.reminder=enabled
            render()
            if(enabled) {
                ReminderScheduler(this@PlannerActivity).ensureChannel()
                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),PERMISSION_REQUEST)
                else ReminderScheduler(this@PlannerActivity).unavailableReason()?.let { Ui.toast(this@PlannerActivity,it) }
            }
        })
        if(draft.reminder) {
            addView(Ui.button(this@PlannerActivity,"提醒时间  ${draft.time.ifBlank { "09:00" }}") { chooseTime(draft) }
                .apply { tag="planner-time"; gravity=Gravity.START or Gravity.CENTER_VERTICAL })
            addView(Ui.space(this@PlannerActivity,8))
            addView(Ui.text(this@PlannerActivity,"系统省电限制可能使提醒延迟。",12f,Ui.muted))
        }
        addView(Ui.space(this@PlannerActivity,16))
        formError=Ui.text(this@PlannerActivity,"",14f,Ui.ink).also { addView(it) }
        val buttons=Ui.row(this@PlannerActivity)
        buttons.addView(Ui.button(this@PlannerActivity,"保存",true) { saveDraft(draft) },LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(8) })
        buttons.addView(Ui.button(this@PlannerActivity,"取消") { screen.draft=null; render() },LinearLayout.LayoutParams(0,-2,1f))
        addView(buttons)
        if(draft.baseRevision!=null) {
            addView(Ui.space(this@PlannerActivity,12))
            addView(Ui.button(this@PlannerActivity,"删除这条记录") {
                AlertDialog.Builder(this@PlannerActivity).setTitle("删除这条记录？").setMessage("删除后同时取消它的提醒。")
                    .setNegativeButton("取消",null).setPositiveButton("删除") { _,_ ->
                        screen.change(clearDraft=true) { state ->
                            require(state.countdowns.find { it.id==draft.id }?.revision==draft.baseRevision) { "Record changed while editing" }
                            state.without(draft.id)
                        }
                    }.show()
            })
        }
    }
    private fun chooseDate(draft: PlannerDraft) {
        val current=runCatching { LocalDate.parse(draft.date) }.getOrDefault(today())
        picker=DatePickerDialog(this,{ _,year,month,day ->
            draft.date=LocalDate.of(year,month+1,day).toString(); render()
        },current.year,current.monthValue-1,current.dayOfMonth).apply {
            datePicker.minDate=LocalDate.of(1900,1,1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            datePicker.maxDate=LocalDate.of(2199,12,31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            show()
        }
    }
    private fun chooseTime(draft: PlannerDraft) {
        val current=runCatching { LocalTime.parse(draft.time,timeFormat) }.getOrDefault(LocalTime.of(9,0))
        picker=TimePickerDialog(this,{ _,hour,minute ->
            draft.time=LocalTime.of(hour,minute).format(timeFormat); render()
        },current.hour,current.minute,true).apply { show() }
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
    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if(screen.draft!=null && !screen.busy) { screen.draft=null; render() } else if(!screen.busy) super.onBackPressed()
    }
    private fun today()=LocalDate.now(ZoneId.systemDefault())
    private fun dp(value: Int)=Ui.dp(this,value)
    companion object { private const val PERMISSION_REQUEST=1601 }
}
