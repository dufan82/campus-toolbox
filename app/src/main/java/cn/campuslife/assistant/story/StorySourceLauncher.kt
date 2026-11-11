package cn.campuslife.assistant.story

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import cn.campuslife.assistant.ui.Ui

object StorySourceLauncher {
    fun intent(url:String):Intent?=StoryProvenance.publicSourceUrl(url)?.let {
        Intent(Intent.ACTION_VIEW,it.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    }
    fun open(activity:Activity,url:String) {
        val intent=intent(url) ?: run {Ui.toast(activity,"原文链接暂不可用");return}
        try {activity.startActivity(intent)}
        catch(_:ActivityNotFoundException){Ui.toast(activity,"没有能打开原文的应用")}
        catch(_:SecurityException){Ui.toast(activity,"原文未能打开，请检查系统设置")}
    }
}
