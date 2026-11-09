package cn.campuslife.assistant.gate

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.widget.LinearLayout
import cn.campuslife.assistant.BuildConfig
import cn.campuslife.assistant.ui.Ui
import java.util.Locale

class GateActivity : Activity() {
    private var launching = false
    private var fallbackDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.page(this)
        root.addView(Ui.header(this, "快捷进出校"))
        val body = Ui.column(this).apply { setPadding(Ui.dp(context, 20), Ui.dp(context, 12), Ui.dp(context, 20), Ui.dp(context, 24)) }
        root.addView(Ui.scroll(this, body), LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(Ui.text(this, "平潭校区", 18f, bold = true))
        body.addView(Ui.space(this, 8))
        body.addView(Ui.text(this, "学校入口需要在微信内打开。", 14f, Ui.muted))
        GateRoute.entries.groupBy { it.gate }.forEach { (gate, routes) ->
            body.addView(Ui.space(this, 18))
            body.addView(Ui.text(this, gate, 16f, bold = true))
            routes.forEach { route ->
                body.addView(Ui.space(this, 10))
                val color = when (route) {
                    GateRoute.EAST_IN, GateRoute.NORTH_IN -> Ui.blue
                    GateRoute.EAST_OUT, GateRoute.NORTH_OUT -> Color.rgb(44, 106, 171)
                }
                addGateButton(body, route, color, "gate_${route.name.lowercase(Locale.ROOT)}")
            }
        }
        body.addView(Ui.space(this, 16))
        body.addView(Ui.text(this, "点击入口后，在微信选择文件传输助手或自己的聊天，确认发送，再点开链接。", 14f, Ui.muted))
        body.addView(Ui.space(this, 16))
        body.addView(Ui.button(this, "打开微信，扫现场码") { openWechat() }.apply { tag = "gate_open_wechat" }, LinearLayout.LayoutParams(-1, -2))
        body.addView(Ui.space(this, 24))
        body.addView(Ui.text(this, "请在校门现场使用，通行结果以学校官方系统为准。", 14f, Ui.muted))
        body.addView(Ui.space(this, 10))
        body.addView(Ui.text(this, "如学校要求现场扫码，请在微信右上角“＋”中打开扫一扫，扫描现场二维码。", 13f, Ui.muted))
        if (BuildConfig.DEBUG) {
            body.addView(Ui.space(this, 18))
            body.addView(Ui.button(this, "微信网页验证（仅测试版）") {
                startActivity(Intent().setClassName(this, "cn.campuslife.assistant.gate.probe.WechatProbeActivity"))
            }.apply { tag = "gate_wechat_probe" }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    override fun onResume() {
        super.onResume()
        launching = false
    }

    override fun onDestroy() {
        fallbackDialog?.dismiss()
        super.onDestroy()
    }

    private fun addGateButton(body: LinearLayout, route: GateRoute, color: Int, buttonTag: String) {
        body.addView(Ui.button(this, route.title, primary = true) {
            if (!launching && fallbackDialog == null) {
                launching = true
                if (!GateLauncher.launch(route) { startActivity(it) }) {
                    launching = false
                    fallbackDialog = AlertDialog.Builder(this)
                        .setTitle("暂时无法分享到微信")
                        .setMessage("可以复制入口，在微信发给自己后打开；也可以扫描学校现场二维码。")
                        .setPositiveButton("复制入口并打开微信") { _, _ ->
                            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(route.title, route.url))
                            openWechat()
                        }
                        .setNegativeButton("取消", null)
                        .create().apply {
                            setOnDismissListener { fallbackDialog = null }
                            show()
                        }
                }
            }
        }.apply {
            tag = buttonTag
            textSize = 18f
            minHeight = Ui.dp(context, 76)
            minimumHeight = Ui.dp(context, 76)
            background = RippleDrawable(ColorStateList.valueOf(0x33ffffff), Ui.shape(context, color, 14), null)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun openWechat() {
        if (launching) return
        launching = true
        if (!GateLauncher.openWechat({ packageManager.getLaunchIntentForPackage(it) }) { startActivity(it) }) {
            launching = false
            Ui.toast(this, "未安装微信或无法打开，请检查微信后重试")
        }
    }
}
