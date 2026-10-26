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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=Ui.page(this)
        val content=Ui.column(this).apply { setPadding(Ui.dp(context,20),Ui.dp(context,12),Ui.dp(context,20),Ui.dp(context,16)) }
        root.addView(content,LinearLayout.LayoutParams(-1,-2))
        val header=Ui.row(this)
        header.addView(Ui.text(this,"校园工具箱",24f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(Ui.iconButton(this,"settings","设置") { settings() },LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)))
        content.addView(header);content.addView(Ui.space(this,12))
        val quick=Ui.row(this).apply { background=Ui.shape(context,Color.WHITE,16,Ui.line) }
        val launcher=ExpressLauncher(this,ExpressConfiguration(ExpressPreferences(this)))
        ExpressCatalog.entries.forEachIndexed { index,entry ->
            if(index>0) quick.addView(View(this).apply { setBackgroundColor(Ui.line) },LinearLayout.LayoutParams(Ui.dp(this,1),-1))
            val tile=Ui.column(this).apply {
                gravity=Gravity.CENTER;minimumHeight=Ui.dp(context,116);isClickable=true;isFocusable=true
                tag="shortcut_${entry.id}";contentDescription=entry.title
                setPadding(Ui.dp(context,4),Ui.dp(context,16),Ui.dp(context,4),Ui.dp(context,16))
                background=RippleDrawable(ColorStateList.valueOf(0x220c6b58),Ui.shape(context,Color.WHITE,16),null)
                setOnClickListener {
                    val result=launcher.launch(entry.id)
                    if(result.candidate==null || result.candidate.kind==RouteKind.APP_HOME) AlertDialog.Builder(this@MainActivity)
                        .setTitle(if(result.candidate==null) "未能打开" else "已回退到应用首页").setMessage(result.message)
                        .setPositiveButton("知道了",null).setNeutralButton("入口设置") { _,_->open(ExpressActivity::class.java) }.show()
                }
            }
            val badge=ImageView(this).apply {
                val red=index<2;setImageDrawable(ToolGlyph(context,listOf("package","qr","bag")[index],if(red)0xffb7433e.toInt() else Ui.ink))
                setPadding(Ui.dp(context,10),Ui.dp(context,10),Ui.dp(context,10),Ui.dp(context,10))
                background=Ui.shape(context,if(red)0xfffff2f1.toInt() else Ui.background,12)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            tile.addView(badge,LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,44)));tile.addView(Ui.space(this,10))
            tile.addView(Ui.text(this,entry.title,13f).apply { gravity=Gravity.CENTER;maxLines=2 })
            quick.addView(tile,LinearLayout.LayoutParams(0,-2,1f))
        }
        content.addView(quick)
        toolsBody=Ui.column(this).apply { setPadding(Ui.dp(context,20),0,Ui.dp(context,20),Ui.dp(context,24));tag="home_tools" }
        root.addView(Ui.scroll(this,toolsBody),LinearLayout.LayoutParams(-1,0,1f))
        renderTools()
        // After the first layout; all network and preference reads stay off the UI thread.
        root.post { if (!isDestroyed) { updateStarted=true;checkUpdate() } }
    }
    override fun onResume() { super.onResume();if(::toolsBody.isInitialized) { reloadLayout();if(updateStarted) checkUpdate() } }
    override fun onDestroy() { updateTicket++;super.onDestroy() }
    private fun checkUpdate() {
        val ticket=++updateTicket
        val reference=WeakReference(this)
        val app=applicationContext
        UpdateRuntime.worker.execute {
            val result=runCatching { UpdateRepository(app).check(false) }
            Handler(Looper.getMainLooper()).post {
                reference.get()?.takeIf { !it.isDestroyed && it.updateTicket==ticket }?.let { activity ->
                    activity.availableUpdate=result.getOrNull()?.takeIf { it.offer }?.update
                    activity.renderTools()
                }
            }
        }
    }
    private fun reloadLayout() {
        val ticket=++layoutTicket
        HomeLayoutRepository(this).load { result -> if(!isDestroyed && ticket==layoutTicket) {
            result.fold({ homeLayout=it;layoutError=false },{ layoutError=true;homeLayout=null });renderTools()
        } }
    }
    private fun renderTools() {
        toolsBody.removeAllViews()
        val state=homeLayout ?: HomeLayout.defaults()
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
        heading.addView(Ui.text(this,"我的常用工具",17f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(Ui.button(this,"管理") { startActivity(Intent(this,ToolManagementActivity::class.java).putExtra("manage",true)) }.apply { tag="home_manage" })
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
                val tile=Ui.card(this).apply {
                    minimumHeight=Ui.dp(context,128);isClickable=true;isFocusable=true
                    tag=if(id=="map") "open_campus_map" else "home_tool_$id";contentDescription=tool.title+"，"+tool.subtitle
                    background=RippleDrawable(ColorStateList.valueOf(0x220c6b58),Ui.shape(context,Color.WHITE,14,Ui.line),null)
                    setOnClickListener { ToolNavigation.open(this@MainActivity,id) }
                }
                tile.addView(ImageView(this).apply { setImageDrawable(ToolGlyph(context,tool.glyph,Ui.blue));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(Ui.dp(this,26),Ui.dp(this,26)))
                tile.addView(Ui.space(this,12));tile.addView(Ui.text(this,tool.title,16f,bold=true));tile.addView(Ui.space(this,5));tile.addView(Ui.text(this,tool.subtitle,12f,Ui.muted))
                row.addView(tile,LinearLayout.LayoutParams(0,-2,1f).apply { if(index==0) marginEnd=Ui.dp(this@MainActivity,10) })
            }
            if(ids.size==1) row.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
            toolsBody.addView(row);toolsBody.addView(Ui.space(this,10))
        }
        toolsBody.addView(Ui.button(this,"全部工具") { open(ToolManagementActivity::class.java) }.apply { tag="home_all_tools" },LinearLayout.LayoutParams(-1,-2))
        toolsBody.addView(Ui.space(this,18));toolsBody.addView(Ui.text(this,"资料与照片只在本机处理 · 地图需联网",12f,Ui.muted))
    }
    private fun open(type: Class<out Activity>) { startActivity(Intent(this,type)) }
    private fun settings() {
        AlertDialog.Builder(this).setTitle("工具箱设置").setItems(arrayOf("我的校园资料","快捷入口设置与诊断","检查更新","使用说明")) { _,index ->
            when(index) {
                0->open(ProfileActivity::class.java)
                1->open(ExpressActivity::class.java)
                2->startActivity(Intent(this,UpdateActivity::class.java).putExtra("manual",true))
                else->AlertDialog.Builder(this).setTitle("使用说明")
                    .setMessage("照片水印：选图或拍照 → 点选上方字段 → 编辑资料 → 在下方预览 → 命名并保存。学号默认关闭，标签上的数字是排列顺序。\n\n只保留一种简洁样式；设置自动记住。导出创建新照片，原图不会覆盖。资料和照片只在本机处理。\n\n快递按钮直接向官方 App 发起跳转，成功时不弹提示；是否到达目标页由第三方版本、登录和链接有效性决定。")
                    .setPositiveButton("知道了",null).show()
            }
        }.setNegativeButton("关闭",null).show()
    }
}
