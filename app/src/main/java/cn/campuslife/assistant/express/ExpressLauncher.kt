package cn.campuslife.assistant.express

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

data class ExpressProbe(val candidate: RouteCandidate, val available: Boolean)
data class ExpressDiagnostics(val config: EffectiveExpressConfig, val probes: List<ExpressProbe>) {
    val summary: String get() = when {
        probes.any { it.available && it.candidate.kind == RouteKind.LINK } -> "可发起跳转；目标页面仍需在第三方 App 中确认。"
        probes.any { it.available && it.candidate.kind == RouteKind.APP_HOME } -> "仅可打开 App 首页；页面链接目前不可用。"
        else -> "暂无可用入口；可能未安装、被停用或受系统限制。"
    }
}

data class ExpressLaunchResult(val candidate: RouteCandidate?, val usedFallback: Boolean,
    val message: String, val failedAttempts: Int)

/** Both operations obtain the same effective plan. Never parse arbitrary intents or clear third-party tasks. */
class ExpressLauncher(private val activity: Activity, private val configuration: ExpressConfiguration) {
    fun diagnose(entryId: String): ExpressDiagnostics {
        val plan = configuration.effective(entryId)
        return ExpressDiagnostics(plan, plan.candidates.map { route ->
            ExpressProbe(route, try {
                intentFor(route)?.resolveActivity(activity.packageManager) != null
            } catch (_: SecurityException) { false } catch (_: IllegalArgumentException) { false })
        })
    }

    fun launch(entryId: String): ExpressLaunchResult {
        val plan = configuration.effective(entryId)
        var failedAttempts = 0
        plan.candidates.forEach { candidate ->
            try {
                val intent = intentFor(candidate)
                if (intent == null) {
                    failedAttempts++
                } else {
                    activity.startActivity(intent)
                    val target = plan.entry.targets.single { it.packageName == candidate.packageName }.label
                    val message = if (candidate.kind == RouteKind.APP_HOME) {
                        "页面跳转未成功，已回退打开${target}首页，请手动进入。"
                    } else {
                        "" // Successful links are silent, including successful fallback links.
                    }
                    return ExpressLaunchResult(candidate, failedAttempts > 0, message, failedAttempts)
                }
            } catch (_: ActivityNotFoundException) { failedAttempts++
            } catch (_: SecurityException) { failedAttempts++
            } catch (_: IllegalArgumentException) { failedAttempts++ }
        }
        return ExpressLaunchResult(null, false, "未能打开${plan.entry.title}。请检查目标 App 是否已安装并启用，或在入口设置中更新链接。", failedAttempts)
    }

    private fun intentFor(candidate: RouteCandidate): Intent? = when (candidate.kind) {
        RouteKind.LINK -> Intent(Intent.ACTION_VIEW, Uri.parse(requireNotNull(candidate.uri)).normalizeScheme())
            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(candidate.packageName)
        RouteKind.APP_HOME -> activity.packageManager.getLaunchIntentForPackage(candidate.packageName)?.apply {
            // PackageManager launch intents may include NEW_TASK/RESET_TASK_IF_NEEDED; remove all task flags.
            flags = 0
        }
    }
}
