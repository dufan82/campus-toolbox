package cn.campuslife.assistant.schedule

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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
        var clearingLogin = false
        var terms = emptyList<SchoolTerm>()
        var candidate: ScheduleData? = null
        var schoolPreview = false
        var baseline: ScheduleData? = null
        var baselineSnapshot: ScheduleSnapshot? = null
        var previewDifference: ScheduleUpdateDifference? = null
        var previewDate: LocalDate? = null
        val selectedLegacy = mutableSetOf<String>()
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
    private var dialog: Dialog? = null
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
    private val ready get() = session.data != null && !session.work.busy && !session.clearingLogin

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
        resumed = false; loginHandler.removeCallbacks(loginTick)
        if (web != null) CookieManager.getInstance().flush()
        super.onPause()
    }
    @Deprecated("Platform compatibility") override fun onBackPressed() = requestBack()
    private fun requestBack() {
        if (session.clearingLogin) { Ui.toast(this, "正在清除网页登录状态，请稍候"); return }
        if (session.work.busy) {
            if (session.downloading) { session.cancelled.set(true); clearAuthorization(); session.notice = "正在取消，当前请求结束后返回；原课表未改。"; updateStatus() }
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
            session.cancelled.set(true); clearAuthorization(); session.work.close()
        }
        super.onDestroy()
    }
    private fun destroyWeb() {
        loginHandler.removeCallbacks(loginTick); probingLogin = false
        web?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.stopLoading(); it.webChromeClient = null; it.destroy() }
        web = null; readButton = null
    }
    private fun clearAuthorization() {
        session.authorization = null
        session.autoImportAttempted = false
    }
    private fun home() {
        clearAuthorization(); session.stage = Stage.HOME; session.candidate = null; session.baseline = null; session.baselineSnapshot = null; session.previewDifference = null; session.previewDate = null; session.selectedLegacy.clear()
        session.progress = ""; session.warnings = emptyList(); render()
    }
    private fun confirmClearWebLogin() {
        dialog = AlertDialog.Builder(this).setTitle("清除学校登录状态？")
            .setMessage("将清除本应用 WebView 的全部 Cookie 和网页存储，可能影响本应用其他网页的登录状态；不会删除本地课表或课表备份。学校服务器上的登录状态不一定同时退出。")
            .setNegativeButton("取消", null).setPositiveButton("清除并退出") { _, _ ->
                runCatching { DailySchoolSync.forgetLogin(applicationContext) }
                clearAuthorization()
                val current = session
                current.clearingLogin = true
                current.notice = "正在清除本应用网页登录状态…"
                render()
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    current.clearingLogin = false
                    current.notice = "已清除本应用网页 Cookie，并请求清除网页存储；下次请重新登录。"
                    current.owner?.get()?.render()
                }
            }.show()
    }
    private fun render() {
        if (session.stage == Stage.LOGIN && web != null && !session.work.busy) { updateStatus(); return }
        destroyWeb(); page.removeAllViews()
        page.addView(Ui.header(this, when (session.stage) {
            Stage.LOGIN -> "学校课表"
            Stage.PREVIEW -> "课表更新预览"
            Stage.TERMS -> "选择学期"
            Stage.HOME -> "课表导入与备份"
        }))
        status = Ui.text(this, "", 14f, Ui.muted).apply {
            setPadding(dp(16), 0, dp(16), dp(8)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }.also { page.addView(it) }
        updateStatus()
        if (session.stage == Stage.LOGIN && !session.work.busy) { showWeb(); return }
        val body = Ui.column(this).apply { setPadding(dp(16), 0, dp(16), dp(24)) }
        page.addView(Ui.scroll(this, body), LinearLayout.LayoutParams(-1, 0, 1f))
        if (session.work.busy || session.clearingLogin) {
            body.addView(android.widget.ProgressBar(this))
            if (session.downloading) action(body, "取消导入", true) { requestBack() }
            return
        }
        when (session.stage) {
            Stage.HOME -> {
                val card = Ui.card(this)
                card.addView(Ui.text(this, "更新学校课表", 21f, bold = true))
                card.addView(Ui.space(this, 8))
                card.addView(Ui.text(this, "先看差异，再选择未来更新或整表替换。", 15f, Ui.muted))
                card.addView(Ui.space(this, 16))
                action(card, "登录学校平台", ready, true) { session.notice = "请自行登录，识别成功后自动读取当前学期；未自动开始时，打开个人课表或点击下方导入。"; session.stage = Stage.LOGIN; render() }
                card.addView(Ui.text(this, "网页会话尽量保留，过期后需重新登录。", 13f, Ui.muted))
                card.addView(Ui.textButton(this, "登录与隐私说明") { showLoginInfo() }, LinearLayout.LayoutParams(-1, -2))
                body.addView(card)
                body.addView(Ui.space(this, 20))
                body.addView(Ui.text(this, "备份与恢复", 17f, bold = true))
                body.addView(Ui.space(this, 10))
                val backups = Ui.card(this)
                action(backups, "导出课表备份", ready && session.data?.semester != null) { export() }
                action(backups, "从备份恢复", ready) { pickBackup() }
                action(backups, "恢复上次替换前的课表", ready && session.previous != null) { preview(session.previous!!, "上次替换前副本", emptyList(), session.data!!) }
                backups.addView(Ui.text(this, "备份只含课表，不含登录凭据；请自行保管。", 13f, Ui.muted))
                body.addView(backups)
                body.addView(Ui.space(this, 14))
                action(body, "清除 / 退出学校登录", ready) { confirmClearWebLogin() }
                body.addView(Ui.textButton(this, "返回课程表") { finish() }, LinearLayout.LayoutParams(-1, -2))
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
        status?.apply {
            text = listOf(session.notice, if (session.work.busy) session.progress.ifBlank { session.work.label } else "").filter { it.isNotBlank() }.joinToString("\n")
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        readButton?.isEnabled = !session.work.busy && !probingLogin
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun showWeb() {
        val view = WebView(this)
        web = view
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            @Suppress("DEPRECATION")
            setSavePassword(false)
            @Suppress("DEPRECATION")
            setSaveFormData(false)
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
        view.loadUrl(SchoolPortalPolicy.START_URL)
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
            val baseline = current.store.loadSnapshot()
            val result = SchoolPortalClient.network(authorization, current.cancelled).collect(term, current.cancelled) { done, total ->
                Handler(Looper.getMainLooper()).post { current.progress = "已读取 $done / $total 周"; current.owner?.get()?.updateStatus() }
            }
            baseline to result
        }, { result ->
            session.downloading = false; session.progress = ""
            if (session.cancelled.get()) { session.notice = "已取消，原课表未改。"; home() }
            else result.onSuccess { (before, imported) ->
                session.baselineSnapshot = before
                preview(imported.data, term.label, imported.warnings, before.data, school = true)
            }
                .onFailure { session.notice = "整学期未能完整读取，没有保存。请检查网络或重新登录后重试；原课表未改。"; render() }
        })
    }
    private fun preview(data: ScheduleData, source: String, warnings: List<String>, baseline: ScheduleData, school: Boolean = false) {
        session.schoolPreview = school
        session.selectedLegacy.clear(); session.previewDifference = null; session.previewDate = null
        if (!school) session.baselineSnapshot = null
        session.candidate = data; session.baseline = baseline; session.source = source; session.warnings = warnings
        session.stage = Stage.PREVIEW; session.notice = "预览尚未保存，取消不会修改原课表。"
        if (!school) { render(); return }
        val date = LocalDate.now()
        val started = session.work.submit("正在计算课表差异…", {
            ScheduleUpdatePreview.compare(baseline, data, date)
        }, { result ->
            result.onSuccess { difference -> session.previewDifference = difference; session.previewDate = date }
                .onFailure { session.notice = "课表差异无法计算，原课表未改；请返回后重试。" }
            render()
        })
        if (!started) { session.notice = "正在处理其他操作，请返回后重试。"; render() }
    }
    private fun showLoginInfo() {
        dialog = Ui.bottomSheet(this, "登录与隐私说明") { body, _ ->
            body.addView(Ui.text(this, "课表保存在本机，登录后逐周读取完整学期，先预览再确认。", 16f))
            body.addView(Ui.space(this))
            body.addView(Ui.text(this, "网页组件尽量在本机保留会话，过期需重新登录。每日检查默认关闭；开启并确认后，仅将课表 Token 用 Android Keystore 加密保存供后台只读使用。关闭或退出登录会删除后台凭据。不读取密码。", 15f, Ui.muted))
            body.addView(Ui.space(this))
            body.addView(Ui.text(this, "未来更新保留过去及受保护课程；整表替换包含过去及手动课程。写入前保留最近一次本机副本。每日自动检查在课程表管理中单独开启。", 15f, Ui.muted))
            body.addView(Ui.space(this))
            body.addView(Ui.text(this, "课表备份含课程、教师、教室及学期日期，不含账号、密码或登录凭据。", 15f, Ui.muted))
        }
    }
    private fun showPreview(body: LinearLayout) {
        val data = session.candidate ?: return
        if (session.schoolPreview && session.warnings.isEmpty() && runCatching { DailySyncPreferences(applicationContext).read().enabled }.getOrDefault(false)) {
            body.addView(Ui.textButton(this, "确认本次登录用于每日检查（不改课表）") { confirmBackgroundLogin() })
        }
        val summary = Ui.card(this)
        summary.tag = "schedule.preview.summary"
        summary.addView(Ui.text(this, session.source, 21f, bold = true))
        summary.addView(Ui.space(this, 8))
        summary.addView(Ui.text(this, "${data.semester?.startMonday ?: "未设学期"} 起 · ${data.semester?.totalWeeks ?: 0}周", 14f, Ui.muted))
        summary.addView(Ui.text(this, "${data.courses.map { it.name }.distinct().size}个课程名称 · ${data.courses.size}个上课时段", 14f, Ui.muted))
        body.addView(summary)
        body.addView(Ui.space(this, 12))
        val difference = if (session.schoolPreview) session.previewDifference else null
        if (session.schoolPreview) {
            if (difference == null) {
                body.addView(Ui.text(this, "差异计算失败，暂不能未来合并；仍可核对后整表替换。", 15f))
            } else {
                val counts = Ui.row(this)
                fun count(label: String, count: Int, tagValue: String): LinearLayout = Ui.card(this).apply {
                    tag = tagValue
                    background = Ui.shape(this@ScheduleTransferActivity, Ui.pale, 14)
                    addView(Ui.text(this@ScheduleTransferActivity, label, 13f, Ui.muted))
                    addView(Ui.space(this@ScheduleTransferActivity, 8))
                    addView(Ui.text(this@ScheduleTransferActivity, "${count}次", 26f, Ui.blue, true))
                }
                counts.addView(count("仅本地有", difference.onlyLocal.size, "schedule.diff.local"),
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
                counts.addView(count("仅学校有", difference.onlySchool.size, "schedule.diff.school"),
                    LinearLayout.LayoutParams(0, -2, 1f))
                body.addView(counts)
                body.addView(Ui.space(this, 10))
                body.addView(Ui.text(this, "比较自${session.previewDate}起的上课次数，按名称、教师、教室及节次核对；不含地点关联和作息。以下不是最终合并结果。", 13f, Ui.muted))
                val snapshot = session.baselineSnapshot
                if (snapshot != null && snapshot.data == session.baseline) {
                    val future = Ui.card(this)
                    future.tag = "schedule.future.options"
                    future.addView(Ui.text(this, "仅更新今日及未来", 18f, bold = true))
                    future.addView(Ui.space(this, 8))
                    future.addView(Ui.text(this, "过去课程、受保护课程保留；未登记来源的旧课程默认全部保护。", 14f, Ui.muted))
                    val unknown = snapshot.data.courses.filter { it.id !in snapshot.registry.managed && it.id !in snapshot.registry.protectedIds }
                    if (unknown.isNotEmpty()) {
                        action(future, "选择旧课程（已选${session.selectedLegacy.size}/${unknown.size}）", ready) { chooseLegacy(unknown) }
                        future.addView(Ui.text(this, "只勾选你确认可按学校课程处理的记录。", 13f, Ui.muted))
                    }
                    if (snapshot.registry.protectedIds.isNotEmpty())
                        future.addView(Ui.text(this, "存在本地编辑或删除过的学校课程，无法可靠确定原时段，暂不能使用未来合并。", 14f, Ui.muted))
                    action(future, "预览仅今日及未来更新", ready, true) { previewFuture() }
                    body.addView(Ui.space(this, 12)); body.addView(future)
                }
            }
        }
        session.warnings.forEach { body.addView(Ui.text(this, it, 14f, Ui.muted)) }
        if (data.courses.isEmpty()) body.addView(Ui.text(this, "此课表没有课程。请核对学期，避免替换成空表。", 15f))
        body.addView(Ui.space(this, 12))
        val replacement = Ui.card(this)
        replacement.tag = "schedule.replace.options"
        replacement.addView(Ui.text(this, "整表替换", 18f, bold = true))
        replacement.addView(Ui.space(this, 8))
        replacement.addView(Ui.text(this, "包含过去及手动课程，不是未来合并。替换前保留最近一次本机副本。", 14f, Ui.muted))
        action(replacement, "确认保存到本机", ready, !session.schoolPreview) { confirmSave() }
        body.addView(replacement)
        action(body, "取消，不修改原课表", true) { home() }
        if (difference != null) {
            fun showRows(label: String, rows: List<ScheduleOccurrence>) {
                if (rows.isEmpty()) return
                body.addView(Ui.space(this, 14))
                body.addView(Ui.text(this, "$label（${rows.size}次，显示前30次）", 17f, bold = true))
                rows.take(30).forEach { row ->
                    val card = Ui.card(this)
                    card.addView(Ui.text(this, "${row.date} · 第${row.startPeriod}–${row.endPeriod}节", 12f, Ui.muted))
                    card.addView(Ui.space(this, 7))
                    card.addView(Ui.text(this, row.name, 17f, bold = true))
                    card.addView(Ui.space(this, 6))
                    card.addView(Ui.text(this, "教室：${row.classroom.ifBlank { "未填写" }} · 教师：${row.teacher.ifBlank { "未填写" }}", 14f, Ui.muted))
                    body.addView(Ui.space(this, 8)); body.addView(card)
                }
            }
            showRows("仅本地", difference.onlyLocal)
            showRows("仅学校", difference.onlySchool)
        }
        body.addView(Ui.space(this, 20))
        body.addView(Ui.text(this, "导入课程 · 前30个时段", 17f, bold = true))
        data.courses.take(30).forEach { course ->
            val card = Ui.card(this)
            card.addView(Ui.text(this, course.name, 17f, bold = true))
            card.addView(Ui.space(this, 6))
            card.addView(Ui.text(this, "周${course.weekday} · ${SchedulePresentation.periodRange(course)} · 第${course.startWeek}–${course.endWeek}周" +
                when (course.pattern) { WeekPattern.ODD -> "（单周）"; WeekPattern.EVEN -> "（双周）"; else -> "" }, 13f, Ui.muted))
            card.addView(Ui.text(this, "${course.classroom.ifBlank { "教室未填写" }} · ${course.teacher.ifBlank { "教师未填写" }}", 14f, Ui.muted))
            body.addView(Ui.space(this, 8)); body.addView(card)
        }
    }
    private fun chooseLegacy(courses: List<Course>) {
        val selected = session.selectedLegacy.toMutableSet()
        val labels = courses.mapIndexed { index, course ->
            "${index+1}. ${course.name} · 周${course.weekday} 第${course.startPeriod}–${course.endPeriod}节 · ${course.startWeek}–${course.endWeek}周 · ${course.teacher} ${course.classroom}"
        }.toTypedArray()
        val checked = courses.map { it.id in selected }.toBooleanArray()
        dialog = AlertDialog.Builder(this).setTitle("选择可按学校课程处理的旧课程")
            .setMultiChoiceItems(labels, checked) { _, index, value ->
                if (value) selected.add(courses[index].id) else selected.remove(courses[index].id)
            }
            .setNegativeButton("取消", null).setPositiveButton("确认选择") { _, _ ->
                session.selectedLegacy.clear(); session.selectedLegacy.addAll(selected); render()
            }.show()
    }
    private fun previewFuture() {
        val snapshot = session.baselineSnapshot ?: return
        val school = session.candidate ?: return
        val selected = session.selectedLegacy.toSet()
        val today = LocalDate.now()
        session.work.submit("正在计算未来更新…", {
            val plan = SchoolFutureMerge.plan(snapshot.data, school, snapshot.registry.managed, selected, today, snapshot.registry.protectedIds)
            plan to ScheduleUpdatePreview.compare(snapshot.data, plan.data, today)
        }, { result ->
            result.onSuccess { (plan, difference) ->
                fun describe(rows: List<ScheduleOccurrence>) = rows.take(12).joinToString("\n") { "${it.date} 第${it.startPeriod}–${it.endPeriod}节 ${it.name} ${it.classroom}" }
                val details = "从 $today 起，移除 ${difference.onlyLocal.size} 次、新增 ${difference.onlySchool.size} 次；因受保护课程冲突而未添加 ${plan.blockedOccurrences} 次。过去课程和受保护课程保留，写入前保留一份本机副本。\n\n" +
                    "移除（前12次）：\n${describe(difference.onlyLocal).ifEmpty { "无" }}\n\n新增（前12次）：\n${describe(difference.onlySchool).ifEmpty { "无" }}"
                dialog = AlertDialog.Builder(this).setTitle("确认仅更新今日及未来？").setMessage(details)
                    .setNegativeButton("取消", null).setPositiveButton("确认未来更新") { _, _ ->
                        val current = session; val store = current.store; val appContext = applicationContext
                        val authorization = current.authorization
                        val complete = current.warnings.isEmpty()
                        current.work.submit("正在安全更新课程表…", {
                            val saved = store.applyFutureWithBackup(snapshot, school, selected, today)
                            if (complete) DailySchoolSync.authorizeAfterImport(appContext,authorization,saved.data)
                            val recorded = SchoolImportReminder.mayRecordFutureSuccess(saved.blockedOccurrences) && runCatching { SchoolImportReminder.recordSuccess(appContext) }.getOrDefault(false)
                            Triple(saved, runCatching { store.previousImport() }.getOrNull(), recorded)
                        }, { savedResult ->
                            savedResult.onSuccess { (saved, previous, recorded) ->
                                session.data = saved.data; session.previous = previous
                                session.notice = when {
                                    saved.blockedOccurrences > 0 -> "未来课表已处理，但 ${saved.blockedOccurrences} 次因受保护课程冲突未加入；未刷新更新时间。"
                                    recorded -> "未来课表已保存。"
                                    else -> "课表已保存，但导入时间未能记录。"
                                }
                                home()
                            }.onFailure { session.notice = "更新失败或课表、日期已变化，没有应用更新。请重新预览。"; render() }
                        })
                    }.show()
            }.onFailure { error -> session.notice = error.message ?: "无法安全合并，原课表未改。"; render() }
        })
    }
    private fun confirmSave() {
        val candidate = session.candidate ?: return
        val before = session.baseline ?: return
        dialog = AlertDialog.Builder(this).setTitle("替换当前本地课表？")
            .setMessage("将以预览中的${candidate.courses.size}个时段整表替换当前课表，包括过去及手动课程，并非仅更新今日及未来。替换前会保留一份本机副本；需要长期保留的旧学期请先导出备份。")
            .setNegativeButton("返回核对", null).setPositiveButton("确认替换") { _, _ ->
                val store = session.store
                val school = session.schoolPreview
                val appContext = applicationContext
                val authorization = session.authorization
                val complete = session.warnings.isEmpty()
                session.work.submit("正在保存本地课表…", {
                    val saved = store.replaceWithBackup(before, candidate, schoolImport = school)
                    if (school && complete) DailySchoolSync.authorizeAfterImport(appContext,authorization,saved)
                    val recorded = !school || runCatching { SchoolImportReminder.recordSuccess(appContext) }.getOrDefault(false)
                    Triple(saved, runCatching { store.previousImport() }.getOrNull(), recorded)
                }, { result ->
                    result.onSuccess { (saved, previous, recorded) -> session.data = saved; session.previous = previous; session.notice = if (recorded) "已保存到本机，可断网查看。" else "课表已保存，但导入时间未能记录，可能无法显示更新提醒。"; home() }
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
                    .onFailure { session.notice = "备份无法读取、格式不支持或超过1MB；请选择校伴导出的JSON。原课表未改。"; render() }
            }, enqueue = true)
        }
    }
    private fun confirmBackgroundLogin() {
        val candidate=session.candidate ?: return
        val authorization=session.authorization ?: return
        val app=applicationContext
        dialog=AlertDialog.Builder(this).setTitle("用于每日课表检查？")
            .setMessage("请确认学校页面中是你自己的课表。只授权加密保存本次登录凭据，不替换本地课程。需先在课表管理中开启每日检查，且学校与本地学期、作息一致。")
            .setNegativeButton("取消",null).setPositiveButton("确认") {_,_->
                session.work.submit("确认后台登录…",{
                    check(session.schoolPreview && session.warnings.isEmpty())
                    val local=session.store.load()
                    require(local.semester==candidate.semester && local.periods==candidate.periods)
                    DailySchoolSync.authorizeAfterImport(app,authorization,candidate)
                },{result->
                    session.notice=if(result.getOrDefault(false))"每日检查已恢复，本地课表未改。" else "尚未启用每日检查，或学期、作息不一致／凭据保存失败；本地课表未改。"
                    updateStatus()
                })
            }.show()
    }
    private fun action(parent: LinearLayout, label: String, enabled: Boolean, primary: Boolean = false, click: () -> Unit) {
        parent.addView(Ui.button(this, label, primary, click).apply { isEnabled = enabled }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    private fun dp(value: Int) = Ui.dp(this, value)
}
