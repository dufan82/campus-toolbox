package cn.campuslife.assistant.gate.probe

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import cn.campuslife.assistant.ui.Ui

/** Does not authenticate a student, accept school URLs, or report passage results. */
class WechatProbeActivity : Activity() {
    private lateinit var controller: WechatProbeController
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var run: Button
    private val changed: (ProbeStage) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val handler = Handler(Looper.getMainLooper())
        controller = lastNonConfigurationInstance as? WechatProbeController ?: WechatProbeController(
            clock = SystemClock::elapsedRealtime,
            clientFactory = { ProbeClients.create(app, it) },
            dispatch = { action -> handler.post { action() }; Unit },
            schedule = { delay, action -> handler.postDelayed({ action() }, delay); Unit }
        ).also {
            if (savedInstanceState?.getBoolean("attempted") == true) it.restoredWithoutSession()
        }
        val root = Ui.page(this)
        root.addView(Ui.header(this, "微信网页验证", "仅测试版 · 不是进出校登记"))
        val body = Ui.column(this).apply { setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), Ui.dp(context, 24)) }
        root.addView(Ui.scroll(this, body), LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(Ui.text(this, "只测试能否在微信打开腾讯首页，不访问学校通行网址。", 15f))
        body.addView(Ui.space(this, 18))
        val label = Ui.text(this, "本应用的微信移动应用 AppID", 15f, bold = true)
        body.addView(label)
        input = Ui.input(this, savedInstanceState?.getString("app_id").orEmpty(), "wx 开头的移动应用 AppID") {}.apply {
            id = View.generateViewId()
            tag = "wechat_probe_app_id"
            filters = arrayOf(InputFilter.LengthFilter(64))
        }
        label.labelFor = input.id
        body.addView(input, LinearLayout.LayoutParams(-1, -2))
        body.addView(Ui.text(this, "只填校园工具箱自己的移动应用 AppID，不填 AppSecret，也不借用别人的 ID。尚未注册时先不要付费。", 13f, Ui.muted))
        body.addView(Ui.space(this, 18))
        run = Ui.button(this, "用微信打开测试页", primary = true) { controller.start(input.text.toString()) }.apply {
            tag = "wechat_probe_run"
        }
        body.addView(run, LinearLayout.LayoutParams(-1, -2))
        body.addView(Ui.space(this, 16))
        status = Ui.text(this, "", 14f).apply {
            tag = "wechat_probe_status"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(status)
        body.addView(Ui.space(this, 16))
        body.addView(Ui.text(this, "SDK 返回 true 也不代表网页已打开。请在微信里确认实际页面；如出现 bad_param 等提示，只记录提示文字，不发送登录信息。", 13f, Ui.muted))
        controller.onChanged = changed
        render(controller.stage)
    }

    override fun onResume() { super.onResume(); controller.refresh() }
    override fun onRetainNonConfigurationInstance(): Any = controller
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("app_id", input.text.toString())
        outState.putBoolean("attempted", controller.stage != ProbeStage.IDLE)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (controller.onChanged === changed) controller.onChanged = null
        if (!isChangingConfigurations) controller.close()
        super.onDestroy()
    }

    private fun render(stage: ProbeStage) {
        run.isEnabled = stage != ProbeStage.SENDING
        input.isEnabled = stage != ProbeStage.SENDING
        run.text = if (stage == ProbeStage.SENDING) "正在调用微信…" else "用微信打开测试页"
        status.text = when (stage) {
            ProbeStage.IDLE -> "尚未测试。填写 AppID 后，点击按钮才会调用微信。"
            ProbeStage.NEED_APP_ID -> "缺少有效的移动应用 AppID。未创建 SDK、未发送请求。"
            ProbeStage.NO_WECHAT -> "SDK 未检测到可用微信。没有发送网页请求。"
            ProbeStage.REGISTER_FALSE -> "SDK registerApp 返回 false。尚未调用网页打开接口；请核对微信、AppID及绑定信息。"
            ProbeStage.SENDING -> "正在调用 SDK，仅打开腾讯首页。请勿重复点击。"
            ProbeStage.SDK_TRUE -> "SDK 发送调用返回 true。是否真正打开，仍需在微信里确认；这不是登录或通行成功。"
            ProbeStage.SDK_FALSE -> "SDK 发送调用返回 false。没有证据表明网页已打开；请核对应用配置和平台能力。"
            ProbeStage.UNKNOWN -> "上一次测试结果未知，可能已超时或进程被重建。没有自动重试，需主动再次点击。"
            ProbeStage.ERROR -> "SDK 调用出错，页面仍可使用。未自动重试，请核对应用配置后再试。"
        }
    }
}
