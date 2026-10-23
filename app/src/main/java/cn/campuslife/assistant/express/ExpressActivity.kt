package cn.campuslife.assistant.express

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** Host entry: startActivity(Intent(this, ExpressActivity::class.java)). No extras required. */
class ExpressActivity : Activity() {
    private val backgroundColor = cn.campuslife.assistant.ui.Ui.background
    private val ink = cn.campuslife.assistant.ui.Ui.ink
    private val muted = cn.campuslife.assistant.ui.Ui.muted
    private val blue = cn.campuslife.assistant.ui.Ui.blue
    private lateinit var configuration: ExpressConfiguration
    private lateinit var launcher: ExpressLauncher
    private lateinit var content: LinearLayout
    private lateinit var feedback: TextView
    private val labels = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        configuration = ExpressConfiguration(ExpressPreferences(this))
        launcher = ExpressLauncher(this, configuration)
        buildScreen()
        feedback.text = savedInstanceState?.getString("feedback") ?: "选好入口，点一下打开。可通过“入口设置”更新链接。"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("feedback", feedback.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun buildScreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        @Suppress("DEPRECATION")
        window.statusBarColor = backgroundColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = backgroundColor
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        header.addView(button("返回", false) { finish() }, LinearLayout.LayoutParams(dp(80), -2))
        header.addView(text("快递快捷入口", 22, ink, true).apply {
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = column().apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        val intro = card()
        intro.addView(text("取件少找一步", 21, ink, true))
        intro.addView(text("3 个快捷入口 · 首页可直接打开 · 设置仅在本机", 14, muted), spaced(8))
        intro.addView(text("本模块不读取短信，不上传任何数据。打开后由第三方 App 处理链接，可能需要登录或联网。", 14, muted), spaced(8))
        feedback = text("", 14, ink).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = shape(Color.rgb(235, 242, 255), 10)
        }
        intro.addView(feedback, spaced(12))
        content.addView(intro, spaced(0, 16))
        ExpressCatalog.entries.forEach { addEntry(it) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun addEntry(entry: ExpressEntry) {
        val block = card()
        block.addView(text(entry.title, 19, ink, true))
        block.addView(text(entry.subtitle, 14, muted), spaced(4))
        val label = text("", 13, muted)
        labels[entry.id] = label
        updateLabel(entry.id)
        block.addView(label, spaced(8))
        block.addView(button("打开${entry.title}", true) {
            val result = launcher.launch(entry.id)
            if(result.message.isNotEmpty()) feedback.text = "${entry.title}：${result.message}"
            if (result.candidate == null || result.candidate.kind == RouteKind.APP_HOME) {
                AlertDialog.Builder(this).setTitle(if (result.candidate == null) "未能打开" else "已回退到首页")
                    .setMessage(result.message).setPositiveButton("知道了", null)
                    .setNeutralButton("入口设置") { _, _ -> showSettings(entry) }.show().ensureTouchTargets()
            }
        }, spaced(12))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("入口设置", false) { showSettings(entry) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        actions.addView(button("诊断", false) { showDiagnosis(entry) }, LinearLayout.LayoutParams(0, -2, 1f))
        block.addView(actions, spaced(8))
        content.addView(block, spaced(0, 12))
    }

    private fun updateLabel(entryId: String) {
        val effective = configuration.effective(entryId)
        val version = if (entryId == "douyin_orders") {
            if (configuration.douyinVersion() == DouyinVersion.LITE) " · 极速版优先" else " · 标准版优先"
        } else ""
        labels[entryId]?.text = (if (effective.source == ConfigSource.CUSTOM) "自定义入口" else "默认入口") + version +
            if (effective.warning != null) " · 无效设置已忽略" else ""
    }

    private fun showSettings(entry: ExpressEntry) {
        val effective = configuration.effective(entry.id)
        val form = column().apply { setPadding(dp(20), dp(8), dp(20), dp(16)) }
        val versionGroup = if (entry.id == "douyin_orders") RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@ExpressActivity).apply {
                id = VERSION_STANDARD; text = "优先抖音标准版"; minHeight = dp(48); setTextColor(ink)
            })
            addView(RadioButton(this@ExpressActivity).apply {
                id = VERSION_LITE; text = "优先抖音极速版"; minHeight = dp(48); setTextColor(ink)
            })
            check(if (configuration.douyinVersion() == DouyinVersion.LITE) VERSION_LITE else VERSION_STANDARD)
        } else null
        versionGroup?.let { form.addView(text("版本选择", 15, ink, true)); form.addView(it, spaced(4, 12)) }
        val targetInfo = text("", 13, muted)
        fun selectedVersion() = if (versionGroup?.checkedRadioButtonId == VERSION_LITE) DouyinVersion.LITE else DouyinVersion.STANDARD
        fun selectedTarget() = if (entry.id == "douyin_orders" && selectedVersion() == DouyinVersion.LITE) entry.targets.last() else entry.targets.first()
        fun refreshTargetInfo() {
            val target = selectedTarget()
            targetInfo.text = "固定目标：${target.label}\n${target.packageName}\n允许：http、https、${target.schemes.joinToString("、")}\n链接始终交给这个 App，不会更换目标包名。"
        }
        refreshTargetInfo()
        versionGroup?.setOnCheckedChangeListener { _, _ -> refreshTargetInfo() }
        form.addView(targetInfo)
        val linkLabel = text("自定义链接（可留空使用默认）", 15, ink, true)
        form.addView(linkLabel, spaced(16))
        val input = EditText(this).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minHeight = dp(56)
            maxLines = 5
            setTextColor(ink)
            textSize = 15f
            hint = "https://… 或当前平台协议链接"
            setText(effective.customLink.orEmpty())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        linkLabel.labelFor = input.id
        form.addView(input, spaced(8))
        effective.warning?.let { form.addView(text(it, 13, muted), spaced(8)) }
        form.addView(text("自定义链接优先，其后尝试默认链接，最后才尝试 App 首页。检测仅说明可发起跳转，不能证明到达取件页。", 13, muted), spaced(12))
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this).setTitle("${entry.title} · 入口设置").setView(scroll)
            .setPositiveButton("保存", null).setNegativeButton("取消", null).setNeutralButton("恢复默认", null).create()
        dialog.setOnShowListener {
            dialog.ensureTouchTargets()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val link = input.text.toString()
                val error = if (link.isBlank()) null else LinkPolicy.error(link, selectedTarget().schemes)
                if (error != null) {
                    input.error = error
                    input.requestFocus()
                } else {
                    try {
                        // Validate before saving either value, so a rejected URL cannot change the version.
                        if (entry.id == "douyin_orders") configuration.setDouyinVersion(selectedVersion())
                        configuration.saveLink(entry.id, link)
                        updateLabel(entry.id)
                        feedback.text = "${entry.title}设置已保存，仅保存在本机。"
                        dialog.dismiss()
                    } catch (exception: IllegalStateException) { input.error = exception.message }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                try {
                    configuration.restore(entry.id)
                    updateLabel(entry.id)
                    feedback.text = "${entry.title}已恢复默认。"
                    dialog.dismiss()
                } catch (exception: IllegalStateException) { input.error = exception.message }
            }
        }
        dialog.show()
    }

    private fun showDiagnosis(entry: ExpressEntry) {
        val result = launcher.diagnose(entry.id)
        val report = buildString {
            appendLine(result.summary)
            appendLine("有效配置：${if (result.config.source == ConfigSource.CUSTOM) "自定义优先" else "默认"}")
            result.config.warning?.let { appendLine(it) }
            appendLine("按下列顺序尝试；诊断本身不会启动第三方 App：")
            result.probes.forEachIndexed { index, probe ->
                val target = entry.targets.single { it.packageName == probe.candidate.packageName }.label
                appendLine()
                appendLine("${index + 1}. $target · ${if (probe.candidate.kind == RouteKind.APP_HOME) "首页回退" else "页面链接"}")
                appendLine(if (probe.available) "可发起跳转" else "未发现可处理的入口（未安装、版本不支持或系统限制）")
                probe.candidate.uri?.let { appendLine(it) }
            }
            appendLine()
            append("第三方 App 也可能自行落到首页；系统无法确认实际页面。所有配置及诊断均在本机完成。")
        }
        val message = text(report, 14, ink).apply {
            setPadding(dp(20), dp(8), dp(20), dp(16))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this).setTitle("${entry.title} · 诊断")
            .setView(ScrollView(this).apply { addView(message) })
            .setPositiveButton("知道了", null).setNeutralButton("入口设置") { _, _ -> showSettings(entry) }
            .show().ensureTouchTargets()
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        setLineSpacing(dp(3).toFloat(), 1.05f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setTextColor(if (primary) Color.WHITE else blue)
        background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x33FFFFFF else 0x222563EB),
            shape(if (primary) blue else Color.rgb(235, 242, 255), 12), null)
        setOnClickListener { action() }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card() = column().apply {
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = shape(Color.WHITE, 18)
    }
    private fun shape(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }
    private fun spaced(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }
    private fun AlertDialog.ensureTouchTargets() {
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            getButton(which)?.apply { minHeight = dp(48); minimumHeight = dp(48) }
        }
    }
    private fun dp(value: Int): Int = (resources.displayMetrics.density * value + 0.5f).toInt()

    private companion object {
        const val VERSION_STANDARD = 2001
        const val VERSION_LITE = 2002
    }
}
