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
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cn.campuslife.assistant.reminder.ReminderScheduler
import cn.campuslife.assistant.ui.ToolGlyph
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
        val header=Ui.header(this,"倒数日")
        val writable=!screen.busy && !screen.readError && screen.state!=null
        if(draft==null) (header.getChildAt(0) as LinearLayout).addView(
            Ui.iconButton(this,"plus","新增倒数日") { newCountdown() }.apply { tag="planner-add-header";isEnabled=writable },
            LinearLayout.LayoutParams(dp(48),dp(48)))
        root.addView(header)
        val content=Ui.column(this).apply { setPadding(dp(20),dp(12),dp(20),dp(24)) }
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
        if(draft!=null) {
            content.addView(Ui.text(this,if(draft.baseRevision==null) "添加重要日期" else "编辑日期",19f,bold=true))
            content.addView(Ui.space(this,14))
            val form=buildForm(draft)
            content.addView(form)
            Ui.enabled(form,writable)
        } else {
            val state=screen.state!!
            content.addView(Ui.text(this,"记住重要的日子，不用一直惦记。",14f,Ui.muted))
            content.addView(Ui.space(this,20))
            if(state.countdowns.isEmpty()) content.addView(Ui.card(this).apply {
                tag="planner-empty"
                addView(ImageView(this@PlannerActivity).apply {
                    setImageDrawable(ToolGlyph(context,"calendar",Ui.blue));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },LinearLayout.LayoutParams(dp(40),dp(40)))
                addView(Ui.space(this@PlannerActivity,16))
                addView(Ui.text(this@PlannerActivity,"把重要的日子留在这里",18f,bold=true))
                addView(Ui.space(this@PlannerActivity,8))
                addView(Ui.text(this@PlannerActivity,"生日、纪念日、体测，也可以写自己的安排。",14f,Ui.muted))
            })
            // Presentation only: an upcoming date takes the hero, not an old expired record.
            val now=today()
            val upcoming=state.countdowns.filter { PlannerRules.countdownDate(it,now)>=now }.sortedBy { PlannerRules.countdownDate(it,now) }
            val past=state.countdowns.filter { PlannerRules.countdownDate(it,now)<now }.sortedByDescending { PlannerRules.countdownDate(it,now) }
            val ordered=upcoming+past
            ordered.firstOrNull()?.let {
                content.addView(countdownCard(it,writable,hero=true));content.addView(Ui.space(this,24))
            }
            if(ordered.size>1) {
                val heading=Ui.row(this)
                heading.addView(Ui.text(this,"其他日期",18f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
                heading.addView(Ui.text(this,"${ordered.size-1} 个",13f,Ui.muted))
                content.addView(heading);content.addView(Ui.space(this,14))
                ordered.drop(1).forEach {
                    content.addView(countdownCard(it,writable));content.addView(Ui.space(this,10))
                }
            }
            content.addView(Ui.space(this,10))
            content.addView(Ui.button(this,"添加重要日期") { newCountdown() }.apply {
                tag="planner-add";isEnabled=writable
                setTextColor(Ui.ink);background=Ui.shape(context,Ui.pale,12)
            },LinearLayout.LayoutParams(-1,-2))
            content.addView(Ui.space(this,22))
            content.addView(Ui.text(this,"日期支持点选 · 标题可选，也可自己写",13f,Ui.muted).apply { gravity=Gravity.CENTER })
        }
        val reminders=screen.state!!.countdowns.any { it.reminder } || draft?.reminder==true
        if(reminders) {
            ReminderScheduler(this).unavailableReason()?.let { reason ->
                content.addView(Ui.space(this,12)); content.addView(Ui.text(this,reason,13f,Ui.muted))
                content.addView(Ui.button(this,"通知设置") { openNotificationSettings() })
            }
        }
    }
    private fun newCountdown() {
        if(screen.busy || screen.readError || screen.state==null) return
        screen.draft=PlannerDraft(1,title="生日",date=today().toString(),time="09:00",yearly=true)
        render()
    }
    private fun countdownCard(item: CountdownItem, writable: Boolean,hero: Boolean=false): LinearLayout = Ui.card(this).apply {
        val days=PlannerRules.days(item,today())
        val label=when { days>0 -> "还有 $days 天"; days==0L -> "就是今天"; else -> "已过 ${-days} 天" }
        val target=PlannerRules.countdownDate(item,today())
        val extras=buildList {
            if(item.yearly) add("每年重复")
            if(item.reminder) add("当天 ${item.reminderTime.format(timeFormat)} 提醒")
        }
        if(hero) {
            setPadding(dp(22),dp(24),dp(22),dp(24));background=Ui.shape(context,Ui.pale,18)
            addView(Ui.text(this@PlannerActivity,when {
                days>0 -> "距离${item.title}还有"
                days==0L -> item.title
                else -> "${item.title}已经过去"
            },16f,Ui.blue).apply { tag="planner-hero-title" })
            addView(Ui.space(this@PlannerActivity,14))
            val number=when { days==0L -> "就是今天";days<0 -> "已过 ${-days} 天";else -> "$days 天" }
            val numberRow=Ui.row(this@PlannerActivity)
            numberRow.addView(Ui.text(this@PlannerActivity,number,if(days==0L || days<0) 36f else 62f,Ui.blue,true).apply {
                if(days>0) text=SpannableString(number).apply {
                    setSpan(RelativeSizeSpan(.28f),number.indexOf(' '),number.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tag="planner-hero-days"
            },LinearLayout.LayoutParams(0,-2,1f))
            if(resources.configuration.fontScale<=1.2f && resources.configuration.screenWidthDp>=350) {
                numberRow.addView(ImageView(this@PlannerActivity).apply {
                    setImageDrawable(ToolGlyph(context,"calendar",Color.rgb(185,200,239)));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },LinearLayout.LayoutParams(dp(56),dp(56)).apply { marginStart=dp(12) })
            }
            addView(numberRow);addView(Ui.space(this@PlannerActivity,12))
            addView(Ui.text(this@PlannerActivity,target.format(dateFormat),13f,Ui.muted))
            if(extras.isNotEmpty()) { addView(Ui.space(this@PlannerActivity,6));addView(Ui.text(this@PlannerActivity,extras.joinToString(" · "),13f,Ui.muted)) }
        } else {
            val row=Ui.row(this@PlannerActivity)
            row.addView(ImageView(this@PlannerActivity).apply {
                setImageDrawable(ToolGlyph(context,"calendar",if(item.yearly) Color.rgb(128,86,182) else Ui.blue))
                background=Ui.shape(context,if(item.yearly) Color.rgb(243,238,252) else Ui.pale,13)
                setPadding(dp(10),dp(10),dp(10),dp(10));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(dp(42),dp(42)).apply { marginEnd=dp(12) })
            val labels=Ui.column(this@PlannerActivity)
            labels.addView(Ui.text(this@PlannerActivity,item.title,16f,bold=true))
            labels.addView(Ui.space(this@PlannerActivity,6))
            labels.addView(Ui.text(this@PlannerActivity,target.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")),13f,Ui.muted))
            if(extras.isNotEmpty()) { labels.addView(Ui.space(this@PlannerActivity,4));labels.addView(Ui.text(this@PlannerActivity,extras.joinToString(" · "),12f,Ui.muted)) }
            val remaining=if(days==0L) "今天" else "${if(days<0) "已过 " else ""}${kotlin.math.abs(days)} 天"
            val stacked=resources.configuration.fontScale>1.2f || resources.configuration.screenWidthDp<360 || kotlin.math.abs(days)>999
            if(stacked) {
                labels.addView(Ui.space(this@PlannerActivity,8));labels.addView(Ui.text(this@PlannerActivity,remaining,22f,Ui.blue,true))
            }
            row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            if(!stacked) row.addView(Ui.text(this@PlannerActivity,remaining,24f,Ui.ink,true),LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(12) })
            addView(row)
        }
        tag="countdown-${item.id}"; isEnabled=writable; isFocusable=true
        background=RippleDrawable(ColorStateList.valueOf(0x183159d8),background,null)
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
        background=Ui.shape(context,Color.WHITE,16)
        addView(Ui.text(this@PlannerActivity,"这是什么日子",15f,bold=true))
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
        addView(Ui.text(this@PlannerActivity,"日期",15f,bold=true))
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
        buttons.addView(Ui.button(this@PlannerActivity,"保存",true) { saveDraft(draft) }.apply { tag="planner-save" },LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dp(8) })
        buttons.addView(Ui.textButton(this@PlannerActivity,"取消") { screen.draft=null; render() }.apply { tag="planner-cancel" },LinearLayout.LayoutParams(0,-2,1f))
        addView(buttons)
        if(draft.baseRevision!=null) {
            addView(Ui.space(this@PlannerActivity,12))
            addView(Ui.textButton(this@PlannerActivity,"删除这条记录") {
                AlertDialog.Builder(this@PlannerActivity).setTitle("删除这条记录？").setMessage("删除后同时取消它的提醒。")
                    .setNegativeButton("取消",null).setPositiveButton("删除") { _,_ ->
                        screen.change(clearDraft=true) { state ->
                            require(state.countdowns.find { it.id==draft.id }?.revision==draft.baseRevision) { "Record changed while editing" }
                            state.without(draft.id)
                        }
                    }.show()
            }.apply { tag="planner-delete";setTextColor(Color.rgb(180,67,64)) },LinearLayout.LayoutParams(-1,-2))
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
        text=label; isChecked=value; textSize=16f; setTextColor(Ui.ink); minHeight=dp(48); isSaveEnabled=false
        buttonTintList=ColorStateList.valueOf(Ui.blue)
        tag=if(label=="每年重复") "planner-yearly" else "planner-reminder"
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
                else "通知权限未授予，仍可保存、编辑倒数日。提醒暂不显示，可在系统设置开启。"
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
