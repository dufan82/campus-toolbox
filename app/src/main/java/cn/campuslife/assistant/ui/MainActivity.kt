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

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=Ui.page(this)
        val content=Ui.column(this).apply { setPadding(Ui.dp(context,20),Ui.dp(context,16),Ui.dp(context,20),Ui.dp(context,24)) }
        root.addView(Ui.scroll(this,content),LinearLayout.LayoutParams(-1,0,1f))
        val header=Ui.row(this)
        header.addView(Ui.text(this,"校园工具箱",24f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(Ui.iconButton(this,"settings","设置") { settings() },LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)))
        content.addView(header);content.addView(Ui.space(this,20))
        content.addView(Ui.text(this,"快捷跳转",17f,bold=true));content.addView(Ui.space(this,12))
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
        content.addView(quick);content.addView(Ui.space(this,26))
        content.addView(Ui.text(this,"图片工具",17f,bold=true));content.addView(Ui.space(this,12))
        val watermark=Ui.card(this).apply { background=Ui.shape(context,Ui.pale,16,Ui.line) }
        val heading=Ui.row(this)
        heading.addView(ImageView(this).apply { setImageDrawable(ToolGlyph(context,"image",Ui.blue)) },LinearLayout.LayoutParams(Ui.dp(this,26),Ui.dp(this,26)).apply { marginEnd=Ui.dp(this@MainActivity,12) })
        heading.addView(Ui.text(this,"活动照片水印",17f,bold=true))
        watermark.addView(heading);watermark.addView(Ui.space(this,14));watermark.addView(Ui.text(this,"添加校园信息，高清另存新照片。",14f,Ui.muted))
        watermark.addView(Ui.space(this,20));watermark.addView(Ui.button(this,"开始制作",true) { open(WatermarkActivity::class.java) },LinearLayout.LayoutParams(-1,Ui.dp(this,50)))
        content.addView(watermark);content.addView(Ui.space(this,24));content.addView(Ui.text(this,"照片与资料，仅在本机处理",12f,Ui.muted))
    }
    private fun open(type: Class<out Activity>) { startActivity(Intent(this,type)) }
    private fun settings() {
        AlertDialog.Builder(this).setTitle("工具箱设置").setItems(arrayOf("我的校园资料","快捷入口设置与诊断","使用说明")) { _,index ->
            when(index) {
                0->open(ProfileActivity::class.java)
                1->open(ExpressActivity::class.java)
                else->AlertDialog.Builder(this).setTitle("使用说明")
                    .setMessage("照片水印：选图或拍照 → 点选上方字段 → 编辑资料 → 在下方预览 → 命名并保存。学号默认关闭，标签上的数字是排列顺序。\n\n只保留一种简洁样式；设置自动记住。导出创建新照片，原图不会覆盖。资料和照片只在本机处理。\n\n快递按钮直接向官方 App 发起跳转，成功时不弹提示；是否到达目标页由第三方版本、登录和链接有效性决定。")
                    .setPositiveButton("知道了",null).show()
            }
        }.setNegativeButton("关闭",null).show()
    }
}
