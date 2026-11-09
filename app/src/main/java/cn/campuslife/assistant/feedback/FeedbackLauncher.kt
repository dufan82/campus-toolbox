package cn.campuslife.assistant.feedback

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

object FeedbackLauncher {
    const val EXTRA_UNAVAILABLE_REASON = "feedback_unavailable_reason"
    const val INVALID_CONFIGURATION = "invalid_configuration"
    const val BROWSER_UNAVAILABLE = "browser_unavailable"

    fun open(context: Context, configuredUrl: String = FeedbackConfig.FEEDBACK_FORM_URL) {
        val url = FeedbackRules.formUrl(configuredUrl)
        var reason: String? = if (configuredUrl.isNotBlank()) INVALID_CONFIGURATION else null
        if (url != null) {
            // Open only the configured URL. No extras, draft text, contacts, tokens or attachments.
            val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                reason = BROWSER_UNAVAILABLE
            } catch (_: SecurityException) {
                reason = BROWSER_UNAVAILABLE
            }
        }
        val local = Intent(context, FeedbackActivity::class.java)
        if (reason != null) local.putExtra(EXTRA_UNAVAILABLE_REASON, reason)
        if (context !is Activity) local.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(local)
    }
}
