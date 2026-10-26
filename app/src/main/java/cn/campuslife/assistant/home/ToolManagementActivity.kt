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
        if(manage) body.addView(Ui.text(this,if(failed) "设置无法读取，请保留应用并重试；没有覆盖原记录。" else "只调整普通工具。三个快递入口始终固定在首页。",13f,Ui.muted))
        val ordered=state.normalized().order
        ordered.forEachIndexed { index,id ->
            val tool=ToolCatalog.tools.single { it.id==id }
            val card=Ui.card(this);card.addView(Ui.text(this,tool.title,17f,bold=true));card.addView(Ui.text(this,tool.subtitle,13f,Ui.muted))
            if(manage) {
                val row=Ui.row(this)
                val hide=id !in state.hidden
                listOf("上移" to { change { it.move(id,-1) } },"下移" to { change { it.move(id,1) } },(if(hide) "隐藏" else "恢复") to { change { it.hide(id,hide) } }).forEachIndexed { actionIndex,(label,action) ->
                    row.addView(Ui.button(this,label) { action() }.apply { tag="tool_${id}_$actionIndex";isEnabled=!failed && !(actionIndex==0 && index==0) && !(actionIndex==1 && index==ordered.lastIndex) },LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=Ui.dp(this@ToolManagementActivity,6) })
                }
                card.addView(Ui.space(this,8));card.addView(row)
            } else card.addView(Ui.button(this,"打开") { ToolNavigation.open(this,id) }.apply { tag="all_tool_$id" })
            body.addView(Ui.space(this,12));body.addView(card)
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
