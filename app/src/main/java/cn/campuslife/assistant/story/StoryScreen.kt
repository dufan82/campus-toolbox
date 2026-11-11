package cn.campuslife.assistant.story

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import cn.campuslife.assistant.ui.Ui

/** Small screen-local helpers, using the existing Ui/ToolGlyph without changing them. */
abstract class StoryScreen : Activity() {
    protected val main=Handler(Looper.getMainLooper())
    protected val repository by lazy { StoryRepository.get(this) }
    protected fun onUi(block: ()->Unit) { main.post { if (!isDestroyed && !isFinishing) block() } }
    protected fun body() = Ui.column(this).apply { setPadding(Ui.dp(context,20),Ui.dp(context,16),Ui.dp(context,20),Ui.dp(context,28)) }
    protected fun status(value: String) = Ui.text(this,value,14f,Ui.muted).apply {
        accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    protected fun heading(root: LinearLayout,title: String,submission: Boolean=false) {
        val header=Ui.header(this,title)
        ((header.getChildAt(0) as LinearLayout).getChildAt(1) as TextView).gravity=Gravity.CENTER
        if (submission) (header.getChildAt(0) as LinearLayout).addView(Ui.textButton(this,"投稿") { StorySubmissionLauncher.open(this) }.apply { tag="story_submission" })
        root.addView(header)
    }
    protected fun error(target: TextView,cause: Throwable?) {
        target.visibility=android.view.View.VISIBLE
        target.text=cause?.message ?: "暂时无法读取，请稍后重试"
    }
    protected fun actionRow()=Ui.row(this).apply { gravity=Gravity.END }
}
