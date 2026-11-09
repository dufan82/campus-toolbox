package cn.campuslife.assistant.gate

import android.content.ActivityNotFoundException
import android.content.Intent

/** Uses Android's public share/launcher intents. The user confirms any sending in WeChat. */
object GateLauncher {
    const val WECHAT_PACKAGE = "com.tencent.mm"

    fun intent(route: GateRoute): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        setPackage(WECHAT_PACKAGE)
        putExtra(Intent.EXTRA_TEXT, route.url)
        putExtra(Intent.EXTRA_TITLE, route.title)
    }

    fun launch(route: GateRoute, start: (Intent) -> Unit): Boolean = try {
        start(intent(route))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun openWechat(resolve: (String) -> Intent?, start: (Intent) -> Unit): Boolean = try {
        val launch = resolve(WECHAT_PACKAGE)
        if (launch == null) false else {
            start(launch.setPackage(WECHAT_PACKAGE))
            true
        }
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
