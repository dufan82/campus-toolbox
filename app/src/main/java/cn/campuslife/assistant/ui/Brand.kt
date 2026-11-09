package cn.campuslife.assistant.ui

import android.app.Activity
import android.content.Intent
import androidx.core.net.toUri
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import cn.campuslife.assistant.BuildConfig
import cn.campuslife.assistant.R

/** Display identity only. Package, storage keys, API paths and update repositories are not renamed. */
object Brand {
    const val name="校伴"
    const val english="CAMPUS COMPANION"
    const val positioning="校园效率工具箱"
    const val tagline="让校园生活更简单"
    const val navy=0xff172b49.toInt()
    const val teal=0xff35b6a0.toInt()
    const val orange=0xfff2a65a.toInt()
    const val mist=0xfff5f7fa.toInt()
    const val github="https://github.com/dufan82/campus-toolbox"

    fun showAbout(activity:Activity) = Ui.bottomSheet(activity,"关于$name") {body,_->
        body.addView(ImageView(activity).apply {
            setImageResource(R.drawable.logo_symbol)
            importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(Ui.dp(activity,64),Ui.dp(activity,64)).apply {gravity=Gravity.CENTER_HORIZONTAL})
        fun line(value:String,size:Float,color:Int=Ui.ink,bold:Boolean=false) = Ui.text(activity,value,size,color,bold).apply {gravity=Gravity.CENTER}
        body.addView(Ui.space(activity,10));body.addView(line(name,26f,bold=true))
        body.addView(Ui.space(activity,6));body.addView(line(activity.getString(R.string.brand_english),10f,Ui.muted).apply {letterSpacing=.14f})
        body.addView(Ui.space(activity,16));body.addView(line(activity.getString(R.string.brand_positioning),16f))
        body.addView(Ui.space(activity,6));body.addView(line(activity.getString(R.string.brand_tagline),14f,Ui.muted))
        body.addView(Ui.space(activity,18));body.addView(line("版本 ${BuildConfig.VERSION_NAME}",14f))
        body.addView(Ui.space(activity,8));body.addView(line("更新渠道：国内 CNB 优先，GitHub 备用",13f,Ui.muted))
        body.addView(Ui.space(activity,12))
        fun link(label:String,url:String) {
            body.addView(Ui.textButton(activity,label) {
                try {activity.startActivity(Intent(Intent.ACTION_VIEW,url.toUri()))}
                catch(_:Exception){Ui.toast(activity,"暂时无法打开链接，请检查浏览器设置")}
            },LinearLayout.LayoutParams(-1,-2))
            body.addView(Ui.text(activity,url,12f,Ui.muted).apply {setTextIsSelectable(true);gravity=Gravity.CENTER})
        }
        link("GitHub 项目",github)
        if(BuildConfig.CNB_REPOSITORY.isNotEmpty())link("国内下载", "https://cnb.cool/${BuildConfig.CNB_REPOSITORY}")
    }
}
