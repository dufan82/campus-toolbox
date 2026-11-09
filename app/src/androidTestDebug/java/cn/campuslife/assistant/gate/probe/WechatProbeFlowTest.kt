package cn.campuslife.assistant.gate.probe

import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.content.Intent
import android.app.Activity
import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import java.lang.reflect.Proxy
import com.tencent.mm.opensdk.openapi.IWXAPI
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.campuslife.assistant.gate.GateActivity
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WechatProbeFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @After fun clearFactory() { ProbeClients.testFactory = null }

    @Test fun debugGatePageOffersAnExplicitProbeEntry() {
        ActivityScenario.launch(GateActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("Debug builds need the verification entry", activity.window.decorView.findViewWithTag<View>("gate_wechat_probe"))
            }
        }
    }

    @Test fun openingMissingIdReturningAndRecreatingNeverConstructTheSdk() {
        var created = 0
        ProbeClients.testFactory = { created++; error("Must not create SDK without a configured AppID") }
        val monitor = object : Instrumentation.ActivityMonitor() {
            var external = 0
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_VIEW) || intent.`package` == "com.tencent.mm") {
                    external++
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(WechatProbeActivity::class.java).use { scenario ->
                scenario.onActivity { a ->
                    a.window.decorView.findViewWithTag<View>("wechat_probe_run").performClick()
                    assertTrue(a.window.decorView.findViewWithTag<TextView>("wechat_probe_status").text.contains("未发送请求"))
                }
                scenario.recreate()
                assertEquals(0, created)
                assertEquals(0, monitor.external)
                instrumentation.waitForIdleSync()
                // Shell-owned temporary evidence survives Gradle's test-package cleanup.
                val capture = instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/gate004-page.png")
                ParcelFileDescriptor.AutoCloseInputStream(capture).use { it.readBytes() }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun pendingRequestAndAppIdSurviveRecreationWithoutResubmitting() {
        var sends = 0
        var reply: ((Boolean) -> Unit)? = null
        ProbeClients.testFactory = {
            object : ProbeClient {
                override fun installed() = true
                override fun register() = true
                override fun send(callback: (Boolean) -> Unit) { sends++; reply = callback }
                override fun close() {}
            }
        }
        ActivityScenario.launch(WechatProbeActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.window.decorView.findViewWithTag<EditText>("wechat_probe_app_id").setText("wx0123456789abcdef")
                val button = a.window.decorView.findViewWithTag<View>("wechat_probe_run")
                button.performClick()
                assertFalse(button.isEnabled)
            }
            scenario.recreate()
            scenario.onActivity { a ->
                assertEquals("wx0123456789abcdef", a.window.decorView.findViewWithTag<EditText>("wechat_probe_app_id").text.toString())
                assertEquals(1, sends)
                reply!!(true)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { a ->
                assertTrue(a.window.decorView.findViewWithTag<TextView>("wechat_probe_status").text.contains("不是登录或通行成功"))
            }
            assertEquals(1, sends)
        }
    }

    @Test fun realSdkRequestObjectOnlyContainsTheFixedNonSchoolTestPage() {
        val req = com.tencent.mm.opensdk.modelbiz.OpenWebview.Req().apply { url = ProbeTarget.URL }
        assertEquals("https://www.qq.com/", req.url)
        assertEquals(12, req.type)
        assertTrue(req.checkArgs())
        val bundle = android.os.Bundle()
        req.toBundle(bundle)
        assertEquals(ProbeTarget.URL, java.net.URLDecoder.decode(bundle.getString("_wxapi_jump_to_webview_url"), "UTF-8"))
    }

    @Test fun actualAdapterOnlySubmitsTheFixedPageToAnInjectedSdk() {
        var captured: com.tencent.mm.opensdk.modelbiz.OpenWebview.Req? = null
        var registered: String? = null
        var detached = false
        val fake = Proxy.newProxyInstance(IWXAPI::class.java.classLoader, arrayOf(IWXAPI::class.java)) { _, method, args ->
            when (method.name) {
                "isWXAppInstalled" -> true
                "registerApp" -> { registered = args!![0] as String; true }
                "sendReq" -> { captured = args!![0] as com.tencent.mm.opensdk.modelbiz.OpenWebview.Req; true }
                "detach" -> { detached = true; null }
                else -> throw AssertionError("Unexpected SDK call: ${method.name}")
            }
        } as IWXAPI
        val adapter = SdkProbeClient(instrumentation.targetContext, "wx0123456789abcdef", fake)
        assertTrue(adapter.installed())
        assertTrue(adapter.register())
        adapter.send { fail("The injected SDK does not invoke a completion") }
        assertEquals("wx0123456789abcdef", registered)
        assertEquals(ProbeTarget.URL, captured!!.url)
        assertEquals(12, captured!!.type)
        assertTrue(captured!!.checkArgs())
        adapter.close()
        assertTrue(detached)
    }
}
