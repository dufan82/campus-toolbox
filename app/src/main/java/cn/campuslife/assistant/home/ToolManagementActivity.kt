package cn.campuslife.assistant.home

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import cn.campuslife.assistant.ui.Ui

class ToolManagementActivity: Activity() {
    private var state=HomeLayout.defaults()
    private var busy=true
    private var failed=false
    private lateinit var body: LinearLayout
    private val manage get()=intent.getBooleanExtra("manage",false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        HomeLayoutRepository(this).load { result -> if(!isDestroyed) { busy=false;result.fold({ state=it },{ failed=true;Ui.toast(this,"首页设置无法读取，原设置已保留") });render() } }
    }
    private fun render() {
        val root=Ui.page(this);root.addView(Ui.header(this,if(manage) "管理常用工具" else "全部工具"))
        body=Ui.column(this).apply { setPadding(Ui.dp(context,18),0,Ui.dp(context,18),Ui.dp(context,24)) }
        root.addView(Ui.scroll(this,body),LinearLayout.LayoutParams(-1,0,1f))
        if(busy) { body.addView(Ui.text(this,"正在保存或读取…"));return }
        if(failed) body.addView(Ui.text(this,"设置无法读取，请重试；原记录保留。",13f,Ui.muted))
        val ordered=state.normalized().order
        ordered.forEachIndexed { index,id ->
            val tool=ToolCatalog.tools.single { it.id==id }
            val card=Ui.row(this).apply { background=Ui.shape(context,android.graphics.Color.WHITE,14,Ui.line);setPadding(Ui.dp(context,14),Ui.dp(context,10),Ui.dp(context,8),Ui.dp(context,10));minimumHeight=Ui.dp(context,78) }
            val labels=Ui.column(this);labels.addView(Ui.text(this,tool.title,15f,bold=true));labels.addView(Ui.space(this,6));labels.addView(Ui.text(this,if(manage && id in state.hidden) "已隐藏" else tool.subtitle,11f,Ui.muted))
            card.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            if(manage) {
                val hide=id !in state.hidden
                listOf("上移" to { change { it.move(id,-1) } },"下移" to { change { it.move(id,1) } },(if(hide) "隐藏" else "恢复") to { change { it.hide(id,hide) } }).forEachIndexed { actionIndex,(label,action) ->
                    card.addView(Ui.textButton(this,label) { action() }.apply { textSize=12f;setPadding(0,Ui.dp(context,6),0,Ui.dp(context,6));tag="tool_${id}_$actionIndex";isEnabled=!failed && !(actionIndex==0 && index==0) && !(actionIndex==1 && index==ordered.lastIndex) },LinearLayout.LayoutParams(Ui.dp(this,44),-2))
                }
            } else {
                card.isClickable=true;card.isFocusable=true;card.tag="all_tool_$id";card.contentDescription=tool.title+"，"+tool.subtitle;card.setOnClickListener { ToolNavigation.open(this,id) }
                card.addView(android.widget.ImageView(this).apply { setImageDrawable(cn.campuslife.assistant.ui.ToolGlyph(context,"next",Ui.muted));importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(Ui.dp(this,18),Ui.dp(this,18)))
            }
            body.addView(Ui.space(this,10));body.addView(card)
        }
        if(manage && !failed) { body.addView(Ui.space(this,20));body.addView(Ui.button(this,"恢复默认布局") {
            AlertDialog.Builder(this).setTitle("恢复默认布局？").setMessage("只恢复普通工具顺序和显示，不影响资料、课程或照片。")
                .setPositiveButton("恢复") { _,_-> change { HomeLayout.defaults() } }.setNegativeButton("取消",null).show()
        }.apply { tag="home_reset" }) }
    }
    private fun change(transform: (HomeLayout)->HomeLayout) {
        if(busy || failed) return
        busy=true;render()
        HomeLayoutRepository(this).update(transform) { result -> if(!isDestroyed) { busy=false;result.fold({state=it},{Ui.toast(this,it.message ?: "未保存")});render() } }
    }
}
