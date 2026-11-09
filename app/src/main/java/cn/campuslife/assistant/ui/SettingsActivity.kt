package cn.campuslife.assistant.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import cn.campuslife.assistant.BuildConfig
import cn.campuslife.assistant.express.ExpressActivity
import cn.campuslife.assistant.home.ToolManagementActivity
import cn.campuslife.assistant.update.UpdateActivity

/** Personal settings are ordinary local settings, not an account or login requirement. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=Ui.page(this)
        val heading=Ui.row(this).apply { setBackgroundColor(Color.WHITE);setPadding(Ui.dp(context,20),Ui.dp(context,10),Ui.dp(context,20),Ui.dp(context,10)) }
        heading.addView(Ui.text(this,"我的",24f,bold=true));root.addView(heading)
        val body=Ui.column(this).apply { setPadding(Ui.dp(context,20),Ui.dp(context,22),Ui.dp(context,20),Ui.dp(context,20)) }
        root.addView(Ui.scroll(this,body),LinearLayout.LayoutParams(-1,0,1f))
        val summary=Ui.row(this)
        summary.addView(ImageView(this).apply {
            setImageDrawable(ToolGlyph(context,"user",Ui.blue));setPadding(Ui.dp(context,13),Ui.dp(context,13),Ui.dp(context,13),Ui.dp(context,13));background=Ui.shape(context,Ui.pale,18)
            importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(Ui.dp(this,54),Ui.dp(this,54)).apply { marginEnd=Ui.dp(this@SettingsActivity,14) })
        val intro=Ui.column(this);intro.addView(Ui.text(this,"我的工具箱",20f,bold=true));intro.addView(Ui.space(this,6));intro.addView(Ui.text(this,"不需要注册账号",12f,Ui.muted));summary.addView(intro)
        body.addView(summary);body.addView(Ui.space(this,26));body.addView(Ui.text(this,"常用设置",12f,Ui.muted));body.addView(Ui.space(this,10))
        val common=Ui.card(this).apply { setPadding(Ui.dp(context,16),0,Ui.dp(context,12),0) }
        common.addView(item("user","水印资料","只填写需要展示的信息","settings_profile") { open(ProfileActivity::class.java) })
        common.addView(item("layout","首页布局","排序、隐藏和恢复工具","settings_layout") { startActivity(Intent(this,ToolManagementActivity::class.java).putExtra("manage",true)) })
        common.addView(item("package","快递入口设置","查看和调整已有链接","settings_express") { open(ExpressActivity::class.java) })
        body.addView(common);body.addView(Ui.space(this,22));body.addView(Ui.text(this,"应用",12f,Ui.muted));body.addView(Ui.space(this,10))
        val app=Ui.card(this).apply { setPadding(Ui.dp(context,16),0,Ui.dp(context,12),0) }
        app.addView(item("refresh","检查更新",BuildConfig.VERSION_NAME,"settings_update") { startActivity(Intent(this,UpdateActivity::class.java).putExtra("manual",true)) })
        app.addView(item("shield","隐私说明","","settings_privacy") {
            AlertDialog.Builder(this).setTitle("资料留在本机").setMessage("照片和水印在本机处理，课程表与日期本地保存。地图按需联网、点击定位后申请权限。学校登录和更新检查使用原有官方服务，不上传你的照片或课表。仅主动开启每日课表同步后，登录Token才会通过Android Keystore与AES-GCM加密保存在本机；不保存密码或其他应用的Cookie，关闭同步或退出登录会清除该Token。")
                .setPositiveButton("知道了",null).show()
        })
        app.addView(item("info","关于校园工具箱","","settings_about") {
            AlertDialog.Builder(this).setTitle("校园工具箱").setMessage("快递直达、校园地图、照片水印、课程表和倒数日。\n\n照片水印生成新照片，不覆盖原图；课表导入后可离线查看。\n\n版本 ${BuildConfig.VERSION_NAME}")
                .setPositiveButton("知道了",null).show()
        })
        body.addView(app);body.addView(Ui.space(this,22));body.addView(Ui.text(this,"课表、日期和照片资料保存在本机",11f,Ui.muted).apply { gravity=Gravity.CENTER })
        body.addView(Ui.space(this,12))
        body.addView(Ui.textButton(this,"意见反馈") { cn.campuslife.assistant.feedback.FeedbackLauncher.open(this) }.apply {
            tag="settings_feedback";contentDescription="意见反馈";textSize=13f;setTextColor(Ui.muted)
            val icon=ToolGlyph(context,"info",Ui.muted).apply { setBounds(0,0,Ui.dp(context,18),Ui.dp(context,18)) }
            setCompoundDrawablesRelative(icon,null,null,null);compoundDrawablePadding=Ui.dp(context,6)
        },LinearLayout.LayoutParams(-2,-2).apply { gravity=Gravity.END })
        root.addView(Ui.bottomNavigation(this,"settings"))
    }
    private fun item(glyph: String,title: String,subtitle: String,id: String,click: ()->Unit)=Ui.row(this).apply {
        tag=id;minimumHeight=Ui.dp(context,68);isClickable=true;isFocusable=true;contentDescription=listOf(title,subtitle).filter { it.isNotBlank() }.joinToString("，")
        setPadding(0,Ui.dp(context,13),0,Ui.dp(context,13))
        background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x183159d8),Ui.shape(context,Color.WHITE,8),null)
        setOnClickListener { click() }
        addView(ImageView(context).apply { setImageDrawable(ToolGlyph(context,glyph,Ui.muted));importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(Ui.dp(context,22),Ui.dp(context,22)).apply { marginEnd=Ui.dp(context,13) })
        val text=Ui.column(context);text.addView(Ui.text(context,title,15f,bold=true));if(subtitle.isNotBlank()) { text.addView(Ui.space(context,5));text.addView(Ui.text(context,subtitle,12f,Ui.muted)) }
        addView(text,LinearLayout.LayoutParams(0,-2,1f));addView(ImageView(context).apply { setImageDrawable(ToolGlyph(context,"next",Ui.muted));importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(Ui.dp(context,16),Ui.dp(context,16)))
    }
    private fun open(type: Class<out Activity>)=startActivity(Intent(this,type))
}
