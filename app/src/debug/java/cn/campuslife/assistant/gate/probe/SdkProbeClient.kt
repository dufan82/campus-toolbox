package cn.campuslife.assistant.gate.probe

import android.content.Context
import com.tencent.mm.opensdk.modelbiz.OpenWebview
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import com.tencent.mm.opensdk.openapi.IWXAPI
import java.util.UUID

internal class SdkProbeClient(
    context: Context,
    private val appId: String,
    private val api: IWXAPI = WXAPIFactory.createWXAPI(context.applicationContext, appId, true)
) : ProbeClient {
    // Use the application's own registered ID and retain WeChat signature verification.
    override fun installed() = api.isWXAppInstalled
    override fun register() = api.registerApp(appId)
    override fun send(callback: (Boolean) -> Unit) {
        val request = OpenWebview.Req().apply {
            url = ProbeTarget.URL
            transaction = UUID.randomUUID().toString()
        }
        check(request.checkArgs())
        api.sendReq(request) { callback(it) }
    }
    override fun close() = api.detach()
}

/** Instrumentation injection is confined to the debug source set, not Intent extras. */
internal object ProbeClients {
    var testFactory: ((String) -> ProbeClient)? = null
    fun create(context: Context, appId: String): ProbeClient =
        testFactory?.invoke(appId) ?: SdkProbeClient(context, appId)
}
