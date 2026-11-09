package cn.campuslife.assistant.gate.probe

/** Debug-only probe. Its only destination is an ordinary, non-school page. */
internal object ProbeTarget {
    const val URL = "https://www.qq.com/"
    fun validAppId(value: String) = value.matches(Regex("wx[a-f0-9]{16}"))
}

internal interface ProbeClient {
    fun installed(): Boolean
    fun register(): Boolean
    fun send(callback: (Boolean) -> Unit)
    fun close()
}

internal enum class ProbeStage {
    IDLE, NEED_APP_ID, NO_WECHAT, REGISTER_FALSE, SENDING, SDK_TRUE, SDK_FALSE, UNKNOWN, ERROR
}

/** Main-thread confined; SDK completions are marshalled by dispatch. Never starts on restore. */
internal class WechatProbeController(
    private val clock: () -> Long,
    private val clientFactory: (String) -> ProbeClient,
    private val dispatch: (() -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit
) {
    var stage = ProbeStage.IDLE
        private set
    var onChanged: ((ProbeStage) -> Unit)? = null
    private var client: ProbeClient? = null
    private var closed = false
    private var attempt = 0
    private var nextAllowed = 0L
    private var deadline = 0L

    fun start(rawAppId: String) {
        if (closed || stage == ProbeStage.SENDING || clock() < nextAllowed) return
        val appId = rawAppId.trim()
        if (!ProbeTarget.validAppId(appId)) {
            move(ProbeStage.NEED_APP_ID)
            return
        }
        nextAllowed = clock() + 1500
        val token = ++attempt
        releaseClient()
        try {
            val current = clientFactory(appId).also { client = it }
            if (!current.installed()) { move(ProbeStage.NO_WECHAT); return }
            if (!current.register()) { move(ProbeStage.REGISTER_FALSE); return }
            deadline = clock() + 20_000
            move(ProbeStage.SENDING)
            schedule(20_000) {
                if (!closed && token == attempt && stage == ProbeStage.SENDING) move(ProbeStage.UNKNOWN)
            }
            current.send { value ->
                dispatch {
                    if (!closed && token == attempt && stage == ProbeStage.SENDING) {
                        move(if (value) ProbeStage.SDK_TRUE else ProbeStage.SDK_FALSE)
                    }
                }
            }
        } catch (_: RuntimeException) {
            // Do not expose SDK exception strings, credentials, or device identifiers.
            move(ProbeStage.ERROR)
        }
    }

    fun refresh() {
        if (!closed && stage == ProbeStage.SENDING && clock() >= deadline) move(ProbeStage.UNKNOWN)
    }

    fun restoredWithoutSession() {
        if (stage == ProbeStage.IDLE) move(ProbeStage.UNKNOWN)
    }

    fun close() {
        closed = true
        attempt++
        onChanged = null
        releaseClient()
    }

    private fun releaseClient() {
        val previous = client
        client = null
        try { previous?.close() } catch (_: RuntimeException) { /* Best-effort SDK detach. */ }
    }

    private fun move(value: ProbeStage) { stage = value; onChanged?.invoke(value) }
}
