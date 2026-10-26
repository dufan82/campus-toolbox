package cn.campuslife.assistant.schedule

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.LinearLayout
import android.widget.TextView
import cn.campuslife.assistant.ui.RetainedWork
import cn.campuslife.assistant.ui.Ui
import java.lang.ref.WeakReference
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/** School login is isolated from local timetable editing. No JavascriptInterface or saved credentials. */
class ScheduleTransferActivity : Activity() {
    private enum class Stage { HOME, LOGIN, TERMS, PREVIEW }
    private class Session(context: Context) {
        val store = ScheduleStore(context)
        val work = RetainedWork<ScheduleTransferActivity>({ Handler(Looper.getMainLooper()).post(it) })
        val cancelled = AtomicBoolean(false)
        var owner: WeakReference<ScheduleTransferActivity>? = null
        var started = false
        var data: ScheduleData? = null
        var previous: ScheduleData? = null
        var stage = Stage.HOME
        var authorization: String? = null
        var autoImportAttempted = false
        var webSessionStarted = false
        var terms = emptyList<SchoolTerm>()
        var candidate: ScheduleData? = null
        var baseline: ScheduleData? = null
        var warnings = emptyList<String>()
        var source = ""
        var notice = ""
        var progress = ""
        var downloading = false
        var exportSnapshot: ScheduleData? = null
    }
    private lateinit var session: Session
    private lateinit var page: LinearLayout
    private var status: TextView? = null
    private var readButton: View? = null
    private var web: WebView? = null
    private var dialog: AlertDialog? = null
    private var back: android.window.OnBackInvokedCallback? = null
    private val loginHandler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var probingLogin = false
    private val loginTick = object : Runnable {
        override fun run() {
            if (!resumed || session.stage != Stage.LOGIN || session.autoImportAttempted) return
            web?.let { probeLogin(it, false) }
            loginHandler.postDelayed(this, 1500)
        }
    }
    private val ready get() = session.data != null && !session.work.busy

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = lastNonConfigurationInstance as? Session ?: Session(applicationContext)
        session.owner = WeakReference(this)
        page = Ui.page(this)
        if (Build.VERSION.SDK_INT >= 33) back = android.window.OnBackInvokedCallback { requestBack() }.also {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
        }
        session.work.bind(this) { _, _ -> render() }
        if (!session.started) {
            session.started = true
            val store = session.store
            session.work.submit("正在读取本地课表…", { store.load() to runCatching { store.previousImport() }.getOrNull() }, { result ->
                result.onSuccess { (data, previous) -> session.data = data; session.previous = previous }
                    .onFailure { session.notice = "本地课表读取失败，原文件已保留。请勿卸载应用，返回后重试。" }
                render()
            })
        }
    }
    override fun onRetainNonConfigurationInstance(): Any = session
    override fun onResume() {
        super.onResume(); resumed = true
        loginHandler.removeCallbacks(loginTick); loginHandler.post(loginTick)
    }
    override fun onPause() {
        resumed = false; loginHandler.removeCallbacks(loginTick); super.onPause()
    }
    @Deprecated("Platform compatibility") override fun onBackPressed() = requestBack()
    private fun requestBack() {
        if (session.work.busy) {
            if (session.downloading) { session.cancelled.set(true); session.notice = "正在取消，当前请求结束后返回；原课表未改。"; updateStatus() }
            else Ui.toast(this, "正在保存或读取，请稍候")
            return
        }
        if (session.stage == Stage.HOME) finish() else home()
    }
    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) back?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        dialog?.dismiss(); destroyWeb()
        session.work.detach(this)
        if (session.owner?.get() === this) session.owner = null
        if (!isChangingConfigurations) {
            session.cancelled.set(true); clearLogin(); session.work.close()
        }
        super.onDestroy()
    }
    private fun destroyWeb() {
        loginHandler.removeCallbacks(loginTick); probingLogin = false
        web?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.webChromeClient = null; it.destroy() }
        web = null; readButton = null
    }
    private fun clearLogin() {
        val usedWeb = session.webSessionStarted
        session.authorization = null
        session.autoImportAttempted = false
        session.webSessionStarted = false
        if (usedWeb) {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
        }
    }
    private fun home() {
        clearLogin(); session.stage = Stage.HOME; session.candidate = null; session.baseline = null
        session.progress = ""; session.warnings = emptyList(); render()
    }
    private fun render() {
        if (session.stage == Stage.LOGIN && web != null && !session.work.busy) { updateStatus(); return }
        destroyWeb(); page.removeAllViews()
        page.addView(Ui.header(this, if (session.stage == Stage.LOGIN) "学校课表" else "课表导入与备份", "导入后保存在本机 · 不需要一直登录"))
        status = Ui.text(this, "", 14f, Ui.muted).apply {
            setPadding(dp(16), 0, dp(16), dp(8)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also { page.addView(it) }
        updateStatus()
        if (session.stage == Stage.LOGIN && !session.work.busy) { showWeb(); return }
        val body = Ui.column(this).apply { setPadding(dp(16), 0, dp(16), dp(24)) }
        page.addView(Ui.scroll(this, body), LinearLayout.LayoutParams(-1, 0, 1f))
        if (session.work.busy) {
            body.addView(android.widget.ProgressBar(this))
            if (session.downloading) action(body, "取消导入", true) { requestBack() }
            return
        }
        when (session.stage) {
            Stage.HOME -> {
                val card = Ui.card(this)
                card.addView(Ui.text(this, "从学校平台导入", 18f, bold = true))
                card.addView(Ui.text(this, "登录福建信息职业技术学院平台后，自动读取当前学期。程序会逐周读取，预览确认后才保存，不会自动替换原课表。", 14f, Ui.muted))
                card.addView(Ui.space(this, 8))
                action(card, "登录学校平台", ready, true) { session.notice = "请自行登录，识别成功后自动读取当前学期；未自动开始时，打开个人课表或点击下方导入。"; session.stage = Stage.LOGIN; render() }
                body.addView(card); body.addView(Ui.space(this))
                body.addView(Ui.text(this, "密码只在学校网页输入。系统网页组件会临时保存登录状态，正常退出时清除，下次登录前也会清理；导入器不另存凭据，也不发送到其他服务器。", 13f, Ui.muted))
                body.addView(Ui.space(this))
                action(body, "导出课表备份", ready && session.data?.semester != null) { export() }
                action(body, "从备份恢复", ready) { pickBackup() }
                action(body, "恢复上次替换前的课表", ready && session.previous != null) { preview(session.previous!!, "上次替换前副本", emptyList(), session.data!!) }
                body.addView(Ui.text(this, "备份包含课程、教师、教室和学期日期，请自行保管。备份不含账号、密码或登录凭据。最近一次替换前副本只保存在本机。", 13f, Ui.muted))
                action(body, "返回课程表", true) { finish() }
            }
            Stage.TERMS -> {
                body.addView(Ui.text(this, "选择要离线保存的学期", 18f, bold = true))
                session.terms.forEach { term -> action(body, term.label, ready) { collect(term) } }
                action(body, "返回", true) { home() }
            }
            Stage.PREVIEW -> showPreview(body)
            Stage.LOGIN -> Unit
        }
    }
    private fun updateStatus() {
        status?.text = listOf(session.notice, if (session.work.busy) session.progress.ifBlank { session.work.label } else "").filter { it.isNotBlank() }.joinToString("\n")
        readButton?.isEnabled = !session.work.busy && !probingLogin
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun showWeb() {
        val view = WebView(this)
        web = view
        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            defaultTextEncodingName = "UTF-8"; cacheMode = WebSettings.LOAD_NO_CACHE
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?) = true
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (SchoolPortalPolicy.schoolPage(request.url.toString())) return false
                session.notice = "已拦截学校域名以外的跳转；如无法登录，请反馈登录方式。"; updateStatus(); return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url != null && !SchoolPortalPolicy.schoolPage(url)) { view.stopLoading(); session.authorization = null }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (web === view && session.stage == Stage.LOGIN && !session.autoImportAttempted) probeLogin(view, false)
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!SchoolPortalPolicy.schoolPage(request.url.toString())) {
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                        mapOf("Cache-Control" to "no-store"), java.io.ByteArrayInputStream(ByteArray(0)))
                }
                val authorization = SchoolPortalPolicy.timetableAuthorization(request.url.toString(), request.requestHeaders)
                if (authorization != null) view.post {
                    if (web === view && session.stage == Stage.LOGIN) {
                        acceptLogin(authorization, false)
                    }
                }
                return null
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (web === view && session.stage == Stage.LOGIN && !session.autoImportAttempted) {
                    session.notice = "学校页面证书验证失败，连接已停止；请稍后再试。"; updateStatus()
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (web === view && session.stage == Stage.LOGIN && request.isForMainFrame && !session.autoImportAttempted) {
                    session.notice = "学校页面暂时无法打开，请检查网络后重新加载；原课表未改。"; updateStatus()
                }
            }
        }
        page.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = Ui.row(this).apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        controls.addView(Ui.button(this, "重新加载") { view.loadUrl(SchoolPortalPolicy.START_URL) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        readButton = Ui.button(this, "导入当前学期", true) { probeLogin(view, true) }.also {
            controls.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
        }
        page.addView(controls); updateStatus()
        if (resumed) { loginHandler.removeCallbacks(loginTick); loginHandler.postDelayed(loginTick, 1500) }
        if (!session.webSessionStarted) {
            session.webSessionStarted = true
            WebStorage.getInstance().deleteAllData()
            view.clearCache(true)
            CookieManager.getInstance().removeAllCookies {
                if (web === view && session.stage == Stage.LOGIN) view.loadUrl(SchoolPortalPolicy.START_URL)
            }
        } else view.loadUrl(SchoolPortalPolicy.START_URL)
    }
    private fun probeLogin(view: WebView, manual: Boolean) {
        if (web !== view || session.stage != Stage.LOGIN || session.work.busy || probingLogin) return
        probingLogin = true
        if (manual) session.notice = "正在检查学校登录状态…"
        updateStatus()
        view.evaluateJavascript(SchoolLoginProbe.script) { result ->
            if (web !== view || session.stage != Stage.LOGIN) return@evaluateJavascript
            probingLogin = false
            val authorization = SchoolLoginProbe.authorization(view.url, result)
            if (manual && authorization == null) session.authorization = null
            if (authorization != null) acceptLogin(authorization, manual)
            else if (manual) session.notice = "尚未识别到登录状态。请先完成学校登录并打开个人课表，再点导入；原课表未改。"
            updateStatus()
        }
    }
    private fun acceptLogin(authorization: String, manual: Boolean) {
        session.authorization = authorization
        if (!session.work.busy && (manual || !session.autoImportAttempted)) {
            session.autoImportAttempted = true
            readTerms()
        }
    }
    private fun readTerms() {
        val authorization = session.authorization ?: return
        session.notice = "已识别学校登录，正在查找当前学期…"; session.progress = ""
        session.cancelled.set(false); session.downloading = true
        val cancelled = session.cancelled
        session.work.submit("正在读取学期…", { SchoolPortalClient.network(authorization, cancelled).terms() }, { result ->
            session.downloading = false
            result.onSuccess { terms ->
                if (!session.cancelled.get()) {
                    session.terms = terms
                    val term = SchoolPortalJson.currentTerm(terms)
                    if (term != null) { collect(term); return@submit }
                    session.stage = Stage.TERMS
                    session.notice = "学校未返回唯一的当前学期，请在下方选择；未改动原课表。"
                }
            }.onFailure { session.notice = "当前学期读取失败，请检查网络或重新登录后点击导入重试；原课表未改。" }
            if (session.cancelled.get()) home() else render()
        })
    }
    private fun collect(term: SchoolTerm) {
        val authorization = session.authorization ?: run { home(); return }
        session.cancelled.set(false); session.downloading = true; session.notice = "正在读取${term.label}，全部完成后才能保存。"
        val current = session
        session.work.submit("准备读取整学期…", {
            val baseline = current.store.load()
            val result = SchoolPortalClient.network(authorization, current.cancelled).collect(term, current.cancelled) { done, total ->
                Handler(Looper.getMainLooper()).post { current.progress = "已读取 $done / $total 周"; current.owner?.get()?.updateStatus() }
            }
            baseline to result
        }, { result ->
            session.downloading = false; session.progress = ""
            if (session.cancelled.get()) { session.notice = "已取消，原课表未改。"; home() }
            else result.onSuccess { (before, imported) -> preview(imported.data, term.label, imported.warnings, before) }
                .onFailure { session.notice = "整学期未能完整读取，没有保存。请检查网络或重新登录后重试；原课表未改。"; render() }
        })
    }
    private fun preview(data: ScheduleData, source: String, warnings: List<String>, baseline: ScheduleData) {
        session.candidate = data; session.baseline = baseline; session.source = source; session.warnings = warnings
        session.stage = Stage.PREVIEW; session.notice = "预览尚未保存，取消不会修改原课表。"; render()
    }
    private fun showPreview(body: LinearLayout) {
        val data = session.candidate ?: return
        body.addView(Ui.text(this, session.source, 18f, bold = true))
        body.addView(Ui.text(this, "${data.semester?.startMonday ?: "未设学期"} 起 · ${data.semester?.totalWeeks ?: 0}周\n${data.courses.map { it.name }.distinct().size}个课程名称 · ${data.courses.size}个上课时段"))
        session.warnings.forEach { body.addView(Ui.text(this, it, 14f, Ui.muted)) }
        if (data.courses.isEmpty()) body.addView(Ui.text(this, "此课表没有课程。请确认学期是否正确，避免替换成空表。", 15f))
        body.addView(Ui.space(this))
        action(body, "确认保存到本机", ready, true) { confirmSave() }
        action(body, "取消，不修改原课表", true) { home() }
        body.addView(Ui.text(this, "以下为前30个时段，保存后可在本地周表逐周查看和编辑。", 13f, Ui.muted))
        data.courses.take(30).forEach { course ->
            body.addView(Ui.text(this, "${course.name}\n周${course.weekday} · 第${course.startPeriod}–${course.endPeriod}节 · 第${course.startWeek}–${course.endWeek}周" +
                when(course.pattern) { WeekPattern.ODD -> "（单周）"; WeekPattern.EVEN -> "（双周）"; else -> "" } + "\n${course.teacher}  ${course.classroom}", 14f))
            body.addView(Ui.space(this))
        }
    }
    private fun confirmSave() {
        val candidate = session.candidate ?: return
        val before = session.baseline ?: return
        dialog = AlertDialog.Builder(this).setTitle("替换当前本地课表？")
            .setMessage("将保存预览中的${candidate.courses.size}个时段，替换当前课表。替换前会保留一份本机副本，可在这里恢复。需要长期保留的旧学期请先导出备份。")
            .setNegativeButton("返回核对", null).setPositiveButton("确认替换") { _, _ ->
                val store = session.store
                session.work.submit("正在保存本地课表…", {
                    val saved = store.replaceWithBackup(before, candidate)
                    saved to runCatching { store.previousImport() }.getOrNull()
                }, { result ->
                    result.onSuccess { (saved, previous) -> session.data = saved; session.previous = previous; session.notice = "已保存到本机，可断网查看。"; home() }
                        .onFailure { session.notice = "保存失败或原课表已被其他页面修改，没有替换。请返回并重新预览；原文件已保留。"; render() }
                })
            }.show()
    }
    private fun export() {
        session.exportSnapshot = session.data ?: return
        launchDocument(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "课程表备份-${LocalDate.now()}.json"), 201)
    }
    private fun pickBackup() = launchDocument(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 202)
    private fun launchDocument(intent: Intent, code: Int) {
        try { @Suppress("DEPRECATION") startActivityForResult(intent, code) }
        catch (_: Exception) { session.notice = "没有可用的系统文件选择器，请检查设备设置后重试。"; render() }
    }
    @Deprecated("Platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in 201..202 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val resolver = applicationContext.contentResolver
        if (requestCode == 201) {
            val snapshot = session.exportSnapshot ?: run { session.notice = "导出状态已丢失，请重新点击导出。"; render(); return }
            session.work.submit("正在导出备份…", {
                resolver.openOutputStream(uri, "wt")?.use { ScheduleBackup.write(it, snapshot) } ?: error("Document unavailable")
            }, { result ->
                session.exportSnapshot = null
                session.notice = if (result.isSuccess) "备份已导出，请自行保管。" else "导出未完成，目标可能留下不完整文件，请核对后重试；本地课表未改。"
                render()
            })
        } else {
            val store = session.store
            session.work.submit("正在核对备份…", {
                val before = store.load()
                val restored = resolver.openInputStream(uri)?.use { ScheduleBackup.read(it) } ?: error("Document unavailable")
                before to restored
            }, { result ->
                result.onSuccess { (before, restored) -> preview(restored, "课表备份", emptyList(), before) }
                    .onFailure { session.notice = "备份无法读取、格式不支持或超过1MB；请选择校园工具箱导出的JSON。原课表未改。"; render() }
            }, enqueue = true)
        }
    }
    private fun action(parent: LinearLayout, label: String, enabled: Boolean, primary: Boolean = false, click: () -> Unit) {
        parent.addView(Ui.button(this, label, primary, click).apply { isEnabled = enabled }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    private fun dp(value: Int) = Ui.dp(this, value)
}
