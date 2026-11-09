package cn.campuslife.assistant.gate.probe

import org.junit.Assert.*
import org.junit.Test

class WechatProbeControllerTest {
    private class FakeClient : ProbeClient {
        var installedValue = true
        var registerValue = true
        var sends = 0
        var closes = 0
        var throwAt = ""
        var reply: ((Boolean) -> Unit)? = null
        override fun installed(): Boolean { if (throwAt == "installed") error("test"); return installedValue }
        override fun register(): Boolean { if (throwAt == "register") error("test"); return registerValue }
        override fun send(callback: (Boolean) -> Unit) { if (throwAt == "send") error("test"); sends++; reply = callback }
        override fun close() { closes++; if (throwAt == "close") error("test") }
    }
    private class Harness {
        var now = 10_000L
        var creates = 0
        val client = FakeClient()
        val timeouts = mutableListOf<() -> Unit>()
        val controller = WechatProbeController({ now }, { creates++; client }, { it() }, { _, action -> timeouts.add(action); Unit })
        fun start() = controller.start("wx0123456789abcdef")
    }

    @Test fun onlyAnOrdinaryFixedPageAndWellFormedIdsAreAccepted() {
        assertEquals("https://www.qq.com/", ProbeTarget.URL)
        assertTrue(ProbeTarget.validAppId("wx0123456789abcdef"))
        listOf("", "wx", "wx0123456789abcdef00", "WX0123456789abcdef", "https://wx.fjpit.com/", "0123456789abcdef0123456789abcdef").forEach {
            assertFalse(ProbeTarget.validAppId(it))
        }
    }

    @Test fun createRefreshAndMissingConfigurationNeverCreateTheSdk() {
        val h = Harness()
        h.controller.refresh()
        h.controller.start("")
        assertEquals(ProbeStage.NEED_APP_ID, h.controller.stage)
        assertEquals(0, h.creates)
        assertEquals(0, h.client.sends)
    }

    @Test fun noWechatOrFailedRegistrationNeverSends() {
        val missing = Harness().apply { client.installedValue = false }
        missing.start()
        assertEquals(ProbeStage.NO_WECHAT, missing.controller.stage)
        assertEquals(0, missing.client.sends)
        val failed = Harness().apply { client.registerValue = false }
        failed.start()
        assertEquals(ProbeStage.REGISTER_FALSE, failed.controller.stage)
        assertEquals(0, failed.client.sends)
    }

    @Test fun sdkBooleanResultsRemainSdkResultsAndDoNotTriggerAnotherRequest() {
        for (value in listOf(true, false)) {
            val h = Harness()
            h.start()
            assertEquals(ProbeStage.SENDING, h.controller.stage)
            h.start()
            assertEquals(1, h.client.sends)
            h.client.reply!!(value)
            assertEquals(if (value) ProbeStage.SDK_TRUE else ProbeStage.SDK_FALSE, h.controller.stage)
            h.controller.refresh()
            h.start() // debounce also applies to immediate synchronous completion
            assertEquals(1, h.client.sends)
        }
    }

    @Test fun timeoutLateCallbackAndOldAttemptCannotOverwriteNewResult() {
        val h = Harness()
        h.start()
        val old = h.client.reply!!
        h.now += 20_000
        h.timeouts[0]()
        assertEquals(ProbeStage.UNKNOWN, h.controller.stage)
        old(true)
        assertEquals(ProbeStage.UNKNOWN, h.controller.stage)
        h.start()
        old(false)
        assertEquals(ProbeStage.SENDING, h.controller.stage)
        h.client.reply!!(true)
        assertEquals(ProbeStage.SDK_TRUE, h.controller.stage)
        assertEquals(2, h.client.sends)
    }

    @Test fun returnAfterTimeoutDoesNotRetry() {
        val h = Harness()
        h.start()
        h.now += 20_001
        h.controller.refresh()
        assertEquals(ProbeStage.UNKNOWN, h.controller.stage)
        assertEquals(1, h.client.sends)
    }

    @Test fun lostProcessSessionAndCloseNeverStartOrAcceptLateWork() {
        val h = Harness()
        h.controller.restoredWithoutSession()
        assertEquals(ProbeStage.UNKNOWN, h.controller.stage)
        assertEquals(0, h.creates)
        h.start()
        val late = h.client.reply!!
        h.controller.close()
        late(true)
        h.start()
        assertEquals(1, h.client.sends)
        assertEquals(1, h.client.closes)
    }

    @Test fun sdkRuntimeFailuresLeaveTheControllerUsable() {
        for (operation in listOf("installed", "register", "send")) {
            val h = Harness().apply { client.throwAt = operation }
            h.start()
            assertEquals(ProbeStage.ERROR, h.controller.stage)
            h.controller.close()
        }
        val h = Harness().apply { client.throwAt = "close" }
        h.start()
        h.controller.close() // SDK detach exceptions must not escape.
    }
}
