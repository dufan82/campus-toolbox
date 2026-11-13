package cn.campuslife.assistant.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import cn.campuslife.assistant.express.*
import cn.campuslife.assistant.map.CampusMapActivity
import cn.campuslife.assistant.home.*
import cn.campuslife.assistant.update.*
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

class MainActivity : Activity() {
    private lateinit var toolsBody: LinearLayout
    private var homeLayout: HomeLayout?=null
    private var layoutError=false
    private var layoutTicket=0
    private var updateTicket=0
    private var updateStarted=false
    private var availableUpdate: UpdatePackage?=null
    private var nextLesson: HomeLesson?=null
    private var nextLessonData: cn.campuslife.assistant.schedule.ScheduleData?=null
    private var lessonTicket=0
    private var lessonDate: java.time.LocalDate?=null
    private var syncNeedsLogin=false
    private lateinit var poemText: android.widget.TextView
    private lateinit var poemCredit: android.widget.TextView
    private var poemDialog: android.app.Dialog?=null
    private var poemBusy=false
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(cn.campuslife.assistant.R.style.AppTheme)
        super.onCreate(savedInstanceState)
        val root=Ui.page(this)
        val content=Ui.column(this).apply { setPadding(Ui.dp(context,20),Ui.dp(context,12),Ui.dp(context,20),Ui.dp(context,16)) }
        root.addView(content,LinearLayout.LayoutParams(-1,-2))
        val header=Ui.column(this).apply { tag="home_poem";minimumHeight=Ui.dp(context,52) }
        val poem=Ui.column(this).apply {
            tag="home_poem_content";isClickable=true;isFocusable=true;minimumHeight=Ui.dp(context,48)
            setPadding(0,0,0,Ui.dp(context,3))
            setOnClickListener { showPoem() }
        }
        val current=cn.campuslife.assistant.poetry.PoemSession.peek()
        poemText=Ui.text(this,current?.text.orEmpty(),14.5f).apply {tag="home_poem_text";setLineSpacing(Ui.dp(context,3).toFloat(),1f)}
        poemCredit=Ui.text(this,current?.credit.orEmpty(),10.5f,Ui.muted).apply {tag="home_poem_credit";gravity=Gravity.END;textAlignment=View.TEXT_ALIGNMENT_VIEW_END}
        val poemLine=Ui.row(this)
        poemLine.addView(poemText,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=Ui.dp(this@MainActivity,8)})
        poemLine.addView(Ui.iconButton(this,"settings","设置") { settings() },LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)))
        poem.addView(poemLine,LinearLayout.LayoutParams(-1,-2));poem.addView(poemCredit,LinearLayout.LayoutParams(-1,-2))
        header.addView(poem,LinearLayout.LayoutParams(-1,-2))
        content.addView(header);content.addView(Ui.space(this,8))
        val quick=Ui.row(this).apply { background=Ui.shape(context,Color.WHITE,16,Ui.line) }
        val launcher=ExpressLauncher(this,ExpressConfiguration(ExpressPreferences(this)))
        ExpressCatalog.entries.forEachIndexed { index,entry ->
            if(index>0) quick.addView(View(this).apply { setBackgroundColor(Ui.line) },LinearLayout.LayoutParams(Ui.dp(this,1),-1))
            val tile=Ui.column(this).apply {
                gravity=Gravity.CENTER;minimumHeight=Ui.dp(context,100);isClickable=true;isFocusable=true
                tag="shortcut_${entry.id}";contentDescription=entry.title
                setPadding(Ui.dp(context,4),Ui.dp(context,16),Ui.dp(context,4),Ui.dp(context,16))
                background=RippleDrawable(ColorStateList.valueOf(0x2235b6a0),Ui.shape(context,Color.WHITE,16),null)
                setOnClickListener {
                    val result=launcher.launch(entry.id)
                    if(result.candidate==null || result.candidate.kind==RouteKind.APP_HOME) AlertDialog.Builder(this@MainActivity)
                        .setTitle(if(result.candidate==null) "未能打开" else "已回退到应用首页").setMessage(result.message)
                        .setPositiveButton("知道了",null).setNeutralButton("入口设置") { _,_->open(ExpressActivity::class.java) }.show()
                }
            }
            val badge=ImageView(this).apply {
                setImageDrawable(ToolGlyph(context,listOf("package","qr","bag")[index],Ui.ink))
                setPadding(Ui.dp(context,10),Ui.dp(context,10),Ui.dp(context,10),Ui.dp(context,10))
                background=Ui.shape(context,when(index){0->Ui.tealTint;1->Ui.orangeTint;else->Ui.pale},12)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            tile.addView(badge,LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,44)));tile.addView(Ui.space(this,10))
            tile.addView(Ui.text(this,entry.title,13f).apply { gravity=Gravity.CENTER;maxLines=2 })
            quick.addView(tile,LinearLayout.LayoutParams(0,-2,1f))
        }
        content.addView(quick)
        toolsBody=Ui.column(this).apply { setPadding(Ui.dp(context,20),0,Ui.dp(context,20),Ui.dp(context,24));tag="home_tools" }
        root.addView(Ui.scroll(this,toolsBody),LinearLayout.LayoutParams(-1,0,1f))
        root.addView(Ui.bottomNavigation(this,"home"))
        renderTools()
        // After the first layout; all network and preference reads stay off the UI thread.
        root.post { if (!isDestroyed) { loadPoem();updateStarted=true;checkUpdate() } }
    }
    private var dailyWarmPosted=false
    override fun onStart() {
        super.onStart()
        if(!dailyWarmPosted) {
            dailyWarmPosted=true
            val owner=WeakReference(this);val app=applicationContext
            window.decorView.postDelayed({
                owner.get()?.takeIf {!it.isFinishing && !it.isDestroyed && it.hasWindowFocus()}?.let {
                    cn.campuslife.assistant.daily.Daily60Runtime.warm(app)
                }
            },1500)
        }
    }
    override fun onResume() { super.onResume();if(::toolsBody.isInitialized) { reloadLayout();loadNextLesson();if(updateStarted) checkUpdate() } }
    override fun onDestroy() { updateTicket++;lessonTicket++;poemDialog?.dismiss();super.onDestroy() }
    private fun checkUpdate() {
        val ticket=++updateTicket
        val reference=WeakReference(this)
        val app=applicationContext
        UpdateRuntime.worker.execute {
            val result=runCatching { UpdateRepository(app).check(false) }
            Handler(Looper.getMainLooper()).post {
                reference.get()?.takeIf { !it.isDestroyed && it.updateTicket==ticket }?.let { activity ->
                    val update=result.getOrNull()?.takeIf { it.offer }?.update
                    if (activity.availableUpdate!=update) {
                        activity.availableUpdate=update
                        activity.renderTools()
                    }
                }
            }
        }
    }
    private fun reloadLayout() {
        val ticket=++layoutTicket
        HomeLayoutRepository(this).load { result -> if(!isDestroyed && ticket==layoutTicket) {
            val previous=homeLayout;val wasError=layoutError
            result.fold({ homeLayout=it;layoutError=false },{ layoutError=true;homeLayout=null })
            if (previous!=homeLayout || wasError!=layoutError) renderTools()
        } }
    }
    private fun loadNextLesson() {
        val ticket=++lessonTicket;val app=applicationContext;val reference=WeakReference(this)
        homePreviewIo.execute {
            val sync=runCatching { cn.campuslife.assistant.schedule.DailySyncPreferences(app).read() }.getOrNull()
            runCatching { cn.campuslife.assistant.schedule.DailySyncScheduler.reconcile(app) }
            val result=runCatching { cn.campuslife.assistant.schedule.ScheduleStore(app).load() }
            val data=result.getOrNull()
            val today=java.time.LocalDate.now()
            val upcoming=data?.let { HomeSchedulePreview.next(it,today,java.time.LocalTime.now()) }
            Handler(Looper.getMainLooper()).post { reference.get()?.takeIf { !it.isDestroyed && it.lessonTicket==ticket }?.let {
                val needsLogin=sync?.enabled==true && sync.result==cn.campuslife.assistant.schedule.SyncResult.NEEDS_LOGIN
                val changed=it.syncNeedsLogin!=needsLogin || it.nextLesson!=upcoming || it.nextLessonData!=data || it.lessonDate!=today
                it.syncNeedsLogin=needsLogin;it.nextLesson=upcoming;it.nextLessonData=data
                it.lessonDate=today
                // onResume still reloads every source; equal immutable results need no new Views.
                if (changed) it.renderTools()
            } }
        }
    }
    private fun renderTools() {
        toolsBody.removeAllViews()
        val state=homeLayout ?: HomeLayout.defaults()
        if(syncNeedsLogin) {
            toolsBody.addView(Ui.textButton(this,"课表自动检查已暂停 · 重新登录") {
                startActivity(Intent(this,cn.campuslife.assistant.schedule.DailySyncSettingsActivity::class.java))
            }.apply {tag="school_sync_login_needed"},LinearLayout.LayoutParams(-1,-2))
            toolsBody.addView(Ui.space(this,8))
        }
        availableUpdate?.let { update ->
            toolsBody.addView(Ui.button(this,"新版本 ${update.versionName} · 查看更新") {
                UpdatePrompts.show(this,update,{
                    val reference=WeakReference(this)
                    val app=applicationContext
                    UpdateRuntime.worker.execute {
                        val saved=runCatching { UpdateRepository(app).ignore(update) }.isSuccess
                        Handler(Looper.getMainLooper()).post { reference.get()?.takeIf { !it.isDestroyed }?.let { activity ->
                            if(saved) { activity.availableUpdate=null;activity.renderTools() }
                            else Ui.toast(activity,"选择未能保存，请稍后再试")
                        } }
                    }
                },{ startActivity(Intent(this,UpdateActivity::class.java).putExtra("manual",false)) })
            }.apply { tag="app_update_banner" },LinearLayout.LayoutParams(-1,-2))
            toolsBody.addView(Ui.space(this,10))
        }
        val heading=Ui.row(this)
        heading.addView(Ui.text(this,"常用工具",17f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(Ui.textButton(this,"编辑") { startActivity(Intent(this,ToolManagementActivity::class.java).putExtra("manage",true)) }.apply { tag="home_manage" })
        toolsBody.addView(heading);toolsBody.addView(Ui.space(this,8))
        if(layoutError) {
            toolsBody.addView(Ui.text(this,"首页设置读取失败，临时显示默认布局；原设置未更改。",13f,Ui.muted).apply { tag="home_layout_error" })
            toolsBody.addView(Ui.button(this,"重试读取") { reloadLayout() })
        } else if(homeLayout==null) { toolsBody.addView(Ui.text(this,"正在读取工具…",13f,Ui.muted));return }
        if(state.visible().isEmpty()) toolsBody.addView(Ui.text(this,"还没有常用工具，点管理添加。",14f,Ui.muted))
        state.visible().chunked(2).forEach { ids ->
            val row=Ui.row(this).apply { gravity=Gravity.TOP }
            ids.forEachIndexed { index,id ->
                val tool=ToolCatalog.tools.single { it.id==id }
                val tile=Ui.row(this).apply {
                    minimumHeight=Ui.dp(context,86);isClickable=true;isFocusable=true
                    setPadding(Ui.dp(context,12),Ui.dp(context,14),Ui.dp(context,8),Ui.dp(context,14))
                    tag=if(id=="map") "open_campus_map" else "home_tool_$id";contentDescription=tool.title+"，"+tool.subtitle
                    background=RippleDrawable(ColorStateList.valueOf(0x18172b49),Ui.shape(context,Color.WHITE,14,Ui.line),null)
                    setOnClickListener { ToolNavigation.open(this@MainActivity,id) }
                }
                val badge=ImageView(this).apply { setImageDrawable(ToolGlyph(context,if(id=="planner") "clock" else tool.glyph,Ui.ink));setPadding(Ui.dp(context,7),Ui.dp(context,7),Ui.dp(context,7),Ui.dp(context,7));background=Ui.shape(context,when(id) { "map"->Ui.tealTint;"watermark"->Ui.orangeTint;else->Ui.pale },11);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                tile.addView(badge,LinearLayout.LayoutParams(Ui.dp(this,35),Ui.dp(this,35)).apply { marginEnd=Ui.dp(this@MainActivity,9) })
                val title=when(id) { "schedule"->"课程表";"planner"->"倒数日";else->tool.title }
                val subtitle=when(id) { "map"->"搜楼栋、找位置";"schedule"->"查看本周安排";"watermark"->"选照片，加水印";"planner"->"记住重要日期";else->tool.subtitle }
                val labels=Ui.column(this);labels.addView(Ui.text(this,title,15f,bold=true));labels.addView(Ui.space(this,5));labels.addView(Ui.text(this,subtitle,11f,Ui.muted))
                tile.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
                row.addView(tile,LinearLayout.LayoutParams(0,-2,1f).apply { if(index==0) marginEnd=Ui.dp(this@MainActivity,10) })
            }
            if(ids.size==1) row.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
            toolsBody.addView(row);toolsBody.addView(Ui.space(this,10))
        }
        toolsBody.addView(Ui.textButton(this,"全部工具") { open(ToolManagementActivity::class.java) }.apply { tag="home_all_tools" },LinearLayout.LayoutParams(-1,-2))
        toolsBody.addView(Ui.space(this,12))
        val lesson=nextLesson
        if(lesson!=null) {
            val heading=Ui.row(this);heading.addView(Ui.text(this,"下次上课",17f,bold=true),LinearLayout.LayoutParams(0,-2,1f));heading.addView(Ui.textButton(this,"查看课表") { ToolNavigation.open(this,"schedule") });toolsBody.addView(heading)
            val card=Ui.card(this).apply { tag="home_next_lesson";isClickable=true;isFocusable=true;setOnClickListener { ToolNavigation.open(this@MainActivity,"schedule") } }
            val date=lesson.date;val today=java.time.LocalDate.now()
            val day=when(date) { today->"今天";today.plusDays(1)->"明天";else->"${date.monthValue}月${date.dayOfMonth}日" }
            val period=if(lesson.course.startPeriod==lesson.course.endPeriod) "第${lesson.course.startPeriod}节" else "第${lesson.course.startPeriod}–${lesson.course.endPeriod}节"
            card.addView(Ui.text(this,"$day · $period",12f,Ui.muted));card.addView(Ui.space(this,10));card.addView(Ui.text(this,lesson.course.name,17f,bold=true));card.addView(Ui.space(this,8))
            val start=nextLessonData?.periods?.getOrNull(lesson.course.startPeriod-1)?.start
            val details=listOf(lesson.course.classroom.takeIf { it.isNotBlank() },start?.toString()).filterNotNull().joinToString(" · ")
            if(details.isNotBlank()) card.addView(Ui.text(this,details,13f,Ui.muted))
            toolsBody.addView(card)
        } else {
            toolsBody.addView(Ui.textButton(this,if(nextLessonData?.semester==null) "添加或导入课表" else "查看课程安排") { ToolNavigation.open(this,"schedule") },LinearLayout.LayoutParams(-1,-2))
        }

    }
    private fun loadPoem(next:Boolean=false) {
        if(poemBusy)return
        poemBusy=true
        val app=applicationContext;val reference=WeakReference(this)
        homePreviewIo.execute {
            val result=runCatching {if(next)cn.campuslife.assistant.poetry.PoemSession.next(app) else cn.campuslife.assistant.poetry.PoemSession.current(app)}
            Handler(Looper.getMainLooper()).post {reference.get()?.takeIf {!it.isDestroyed}?.let {activity->
                activity.poemBusy=false
                result.onSuccess {poem->
                    activity.poemText.text=poem.text;activity.poemCredit.text=poem.credit
                    activity.poemDialog?.takeIf {it.isShowing}?.window?.decorView?.let {sheet->
                        sheet.findViewWithTag<android.widget.TextView>("poem_sheet_text")?.text=poem.text
                        sheet.findViewWithTag<android.widget.TextView>("poem_sheet_credit")?.text=poem.credit
                    }
                }
                    .onFailure {if(next) Ui.toast(activity,"暂时无法换句，请重试")}
            }}
        }
    }
    private fun showPoem() {
        val current=cn.campuslife.assistant.poetry.PoemSession.peek() ?: return
        poemDialog?.dismiss()
        poemDialog=Ui.bottomSheet(this,"诗词") {body,_->
            val text=Ui.text(this,current.text,18f).apply {tag="poem_sheet_text";setLineSpacing(Ui.dp(context,6).toFloat(),1f)}
            val credit=Ui.text(this,current.credit,12f,Ui.muted).apply {tag="poem_sheet_credit";gravity=Gravity.END}
            body.addView(text);body.addView(Ui.space(this,10));body.addView(credit,LinearLayout.LayoutParams(-1,-2));body.addView(Ui.space(this,20))
            val buttons=Ui.row(this)
            buttons.addView(Ui.button(this,"复制") {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("诗句",text.text.toString()+"\n"+credit.text))
                Ui.toast(this,"已复制")
            }.apply {tag="poem_copy"},LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=Ui.dp(this@MainActivity,10)})
            buttons.addView(Ui.button(this,"换一句",true) {loadPoem(true)}.apply {tag="poem_next"},LinearLayout.LayoutParams(0,-2,1f))
            body.addView(buttons)
        }
    }
    private fun open(type: Class<out Activity>) { startActivity(Intent(this,type)) }
    companion object { private val homePreviewIo=java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it,"home-schedule-preview") } }
    private fun settings() { MainNavigation.navigate(this,"home","settings") }
}
