package cn.campuslife.assistant.story

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

object StorySubmissionLauncher {
    const val EXTRA_UNAVAILABLE_REASON="cn.campuslife.assistant.story.UNAVAILABLE_REASON"
    const val BROWSER_UNAVAILABLE="browser_unavailable"
    const val INVALID_CONFIGURATION="invalid_configuration"
    fun open(activity: Activity,configured: String=StorySubmissionConfig.PUBLIC_FORM_URL) {
        if(StoryCloudConfig.configured) {fallback(activity,"");return}
        val raw=configured
        val url=StoryRules.publicFormUrl(raw)
        if (url!=null) {
            try { activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); return }
            catch (_: ActivityNotFoundException) { fallback(activity,BROWSER_UNAVAILABLE); return }
            catch (_: SecurityException) { fallback(activity,BROWSER_UNAVAILABLE); return }
        }
        fallback(activity,if (raw.isEmpty()) "" else INVALID_CONFIGURATION)
    }
    private fun fallback(activity: Activity,reason: String) {
        activity.startActivity(Intent(activity,StorySubmissionActivity::class.java).putExtra(EXTRA_UNAVAILABLE_REASON,reason))
    }
}
