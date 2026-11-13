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
    private enum class Stage { HOME, BACKUP, LOGIN, TERMS, PREVIEW }
    private class Session(context: Context) {
        val store = ScheduleStore(context)
        val work = RetainedWork<ScheduleTransferActivity>({ Handler(Looper.getMainLooper()).post(it) })
        val cancelled = AtomicBoolean(false)
        var owner: WeakReference<ScheduleTransferActivity>? = null
        var started = false
        var data: ScheduleData? = null
        var previous: ScheduleData? = null
        var stage = Stage.HOME
        var backupMode = false
        var syncState: DailySyncState? = null
        var checkPreview: SchoolCheckPreview? = null
        var checkHistory: SchoolCheckRecord? = null
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
    private val syncPrefs by lazy { getSharedPreferences("school-daily-sync-v1", MODE_PRIVATE) }
    private val syncRefresh = object : Runnable {
        override fun run() {
            if (!resumed || session.stage != Stage.HOME) return
            if (session.work.busy) { loginHandler.postDelayed(this, 100); return }
            val app=applicationContext
            session.work.submit("读取同步状态…", { DailySyncPreferences(app).read() to SchoolCheckHistoryStore(app).read() }, { result ->
                result.onSuccess { (state,history) -> session.syncState=state;session.checkHistory=history }; render()
            })
        }
    }
    private val syncListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        loginHandler.removeCallbacks(syncRefresh); loginHandler.post(syncRefresh)
    }
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
            session.backupMode = intent.getBooleanExtra("schedule.backup", false)
            if (session.backupMode) session.stage = Stage.BACKUP
            val store = session.store
            session.work.submit("正在读取本地课表…", { Triple(store.load(), runCatching { store.previousImport() }.getOrNull(), DailySyncPreferences(applicationContext).read()) to SchoolCheckHistoryStore(applicationContext).read() }, { result ->
                result.onSuccess { (loaded,history) -> session.data=loaded.first;session.previous=loaded.second;session.syncState=loaded.third;session.checkHistory=history }
                    .onFailure { session.notice = "本地课表读取失败，原文件已保留。请勿卸载应用，返回后重试。" }
                render()
            })
        }
    }
    override fun onRetainNonConfigurationInstance(): Any = session
    override fun onResume() {
        super.onResume(); resumed = true
        syncPrefs.registerOnSharedPreferenceChangeListener(syncListener)
        if (session.started && session.data != null) loginHandler.post(syncRefresh)
        loginHandler.removeCallbacks(loginTick); loginHandler.post(loginTick)
    }
    override fun onPause() {
        resumed = false; syncPrefs.unregisterOnSharedPreferenceChangeListener(syncListener); loginHandler.removeCallbacks(syncRefresh); loginHandler.removeCallbacks(loginTick)
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
        if (session.stage == Stage.HOME || session.stage == Stage.BACKUP) finish() else home()
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
        clearAuthorization(); session.stage = if (session.backupMode) Stage.BACKUP else Stage.HOME; session.candidate = null; session.baseline = null; session.baselineSnapshot = null; session.previewDifference = null; session.previewDate = null; session.checkPreview = null
        session.progress = ""; session.warnings = emptyList(); render()
        if (resumed && !session.backupMode) loginHandler.post(syncRefresh)
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
            Stage.LOGIN -> "学校登录"
            Stage.PREVIEW -> if (session.schoolPreview) "检查结果" else "恢复课表"
            Stage.TERMS -> "选择学期"
            Stage.HOME -> "检查更新"
            Stage.BACKUP -> "备份与恢复"
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
                action(body, "登录学校平台", ready, true) {
                    session.notice = ""; session.stage = Stage.LOGIN; render()
                }
                body.addView(Ui.space(this, 18))
                showCheckResult(body)
                body.addView(Ui.space(this, 20))
                showDailyOptions(body)
                body.addView(Ui.space(this, 20))
                body.addView(Ui.textButton(this, "退出学校登录") { confirmClearWebLogin() })
            }
            Stage.BACKUP -> {
                action(body, "导出课表备份", ready && session.data?.semester != null) { export() }
                action(body, "从备份恢复", ready) { pickBackup() }
                action(body, "恢复上次更新前课表", ready && session.previous != null) {
                    preview(session.previous!!, "上次更新前课表", emptyList(), session.data!!)
                }
            }
            Stage.TERMS -> {
                body.addView(Ui.text(this, "选择学期", 18f, bold = true))
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
        val app=applicationContext
        session.work.submit("正在读取学期…", { SchoolCheckHistoryStore(app).recordingFailure({SchoolReadFailure.message(it,"学期")}) {SchoolPortalClient.network(authorization, cancelled).terms()} }, { result ->
            session.downloading = false
            result.onSuccess { terms ->
                if (!session.cancelled.get()) {
                    session.terms = terms
                    val term = SchoolPortalJson.currentTerm(terms)
                    if (term != null) { collect(term); return@submit }
                    session.stage = Stage.TERMS
                    session.notice = "学校未返回唯一的当前学期，请在下方选择；未改动原课表。"
                }
            }.onFailure { error ->
                session.notice = SchoolReadFailure.message(error,"学期")
                rememberCheckFailure(session.notice)
            }
            if (session.cancelled.get()) home() else render()
        })
    }
    private fun collect(term: SchoolTerm) {
        val authorization = session.authorization ?: run { home(); return }
        session.cancelled.set(false); session.downloading = true; session.notice = "正在读取${term.label}…"
        val current = session
        val app=applicationContext
        session.work.submit("准备读取整学期…", {
            SchoolCheckHistoryStore(app).recordingFailure({SchoolReadFailure.message(it,"课表")}) {
            val baseline = current.store.loadSnapshot()
            val result = SchoolPortalClient.network(authorization, current.cancelled).collect(term, current.cancelled) { done, total ->
                Handler(Looper.getMainLooper()).post { current.progress = "已读取 $done / $total 周"; current.owner?.get()?.updateStatus() }
            }
            baseline to result
            }
        }, { result ->
            session.downloading = false; session.progress = ""
            if (session.cancelled.get()) { session.notice = "已取消，原课表未改。"; home() }
            else result.onSuccess { (before, imported) ->
                session.baselineSnapshot = before
                preview(imported.data, term.label, imported.warnings, before.data, school = true)
            }
                .onFailure { session.notice = SchoolReadFailure.message(it,"课表"); rememberCheckFailure(session.notice); render() }
        })
    }
    private fun preview(data: ScheduleData, source: String, warnings: List<String>, baseline: ScheduleData, school: Boolean = false) {
        session.schoolPreview = school
        session.checkPreview = null; session.previewDifference = null; session.previewDate = null
        if (!school) session.baselineSnapshot = null
        session.candidate = data; session.baseline = baseline; session.source = source; session.warnings = warnings
        session.stage = Stage.PREVIEW; session.notice = ""
        if (!school) { render(); return }
        val date = LocalDate.now()
        val app=applicationContext
        val started = session.work.submit("正在计算课表差异…", {
            SchoolCheckHistoryStore(app).recordingFailure("检查未完成，差异计算失败") {
            val snapshot = session.baselineSnapshot ?: error("本地课表状态已丢失，请重新检查。")
            check(snapshot.data == baseline)
            val plan = SchoolUpdateCheck.preview(snapshot, data, date)
            val diff=ScheduleUpdatePreview.compare(baseline,plan.plan.data,date)
            val message=when {warnings.isNotEmpty()->"课表未完整读取，未更新";plan.plan.data==baseline && plan.plan.conflicts>0->"已检查，受保护课程已保留";plan.plan.data==baseline->"已检查，课表没有变化";else->"已检查，发现课表更新"}
            val outcome=if(warnings.isNotEmpty())SchoolCheckOutcome.failed(untouched=true) else
                SchoolCheckOutcome.preview(plan.plan.changes,plan.plan.conflicts,plan.plan.data!=baseline || plan.plan.conflicts>0)
            val history=SchoolCheckRecord(System.currentTimeMillis(),message,if(warnings.isEmpty())SchoolCheckHistory.details(plan.plan,date,applied=false) else "",outcome)
            runCatching {SchoolCheckHistoryStore(applicationContext).write(history)}
            Triple(plan,diff,history)
            }
        }, { result ->
            result.onSuccess { (plan,difference,history) -> session.checkPreview=plan;session.previewDifference=difference;session.previewDate=date;session.checkHistory=history }
                .onFailure { session.notice = "课表差异无法计算，原课表未改；请返回后重试。"; rememberCheckFailure("检查未完成，差异计算失败") }
            render()
        })
        if (!started) { session.notice = "正在处理其他操作，请返回后重试。"; render() }
    }
    private fun rememberCheckFailure(message:String) {
        // The worker has already attempted to persist this; do not reload/destroy the login WebView.
        session.checkHistory=SchoolCheckRecord(System.currentTimeMillis(),message,"",SchoolCheckOutcome.failed(untouched=true))
    }
    private fun showCheckResult(body:LinearLayout) {
        val record=SchoolCheckHistory.latest(session.syncState,session.checkHistory)
        if(record==null) {body.addView(Ui.text(this,"尚未检查",14f,Ui.muted).apply {tag="school_check_result"});return}
        val at=java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").format(java.time.Instant.ofEpochMilli(record.at).atZone(java.time.ZoneId.systemDefault()))
        body.addView(Ui.text(this,"最近检查 $at",13f,Ui.muted).apply {tag="school_check_time"})
        body.addView(Ui.space(this,6))
        val outcome=SchoolCheckOutcome.legacy(record)
        body.addView(Ui.text(this,SchoolCheckOutcomeText.from(outcome).check,15f).apply {tag="school_sync_result"})
        SchoolCheckStatusUi.add(this,body,outcome,record.details)
        if(outcome.phase==SchoolCheckPhase.FAILED)body.addView(Ui.text(this,record.result,13f,Ui.muted))
        if(record.details.isNotBlank())body.addView(Ui.textButton(this,"查看更新内容") {
            dialog=Ui.bottomSheet(this,"更新内容") {content,_->content.addView(Ui.text(this,record.details,15f).apply {setLineSpacing(dp(5).toFloat(),1f)})}
        }.apply {tag="school_check_details"})
    }
    private fun showDailyOptions(body: LinearLayout) {
        val state = session.syncState
        body.addView(android.widget.Switch(this).apply {
            tag = "school_sync_switch"; text = "每日 07:00 自动检查"; textSize = 15f
            setTextColor(Ui.ink); minimumHeight = dp(52)
            isChecked = state?.enabled == true; isEnabled = state != null && ready
            setOnCheckedChangeListener { _, enabled ->
                if (enabled) {
                    isChecked = false
                    dialog = AlertDialog.Builder(this@ScheduleTransferActivity).setTitle("开启每日检查？")
                        .setMessage("每天07:00检查，省电或断网时可能延后。登录并确认后，会在本机加密保存课表凭据供后台使用；关闭或退出登录即清除。只更新可安全修改的未来课程。")
                        .setNegativeButton("取消", null).setPositiveButton("开启") { _, _ -> changeDailyCheck(true) }.show()
                } else if (state?.enabled == true) changeDailyCheck(false)
            }
        }, LinearLayout.LayoutParams(-1, -2))
        if (state?.enabled == true && android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            body.addView(Ui.textButton(this, "开启更新通知") { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 20701) })
    }
    private fun changeDailyCheck(enabled: Boolean) {
        if (!enabled) DailySchoolSync.active?.set(true)
        val app = applicationContext
        session.work.submit("保存设置…", { DailySchoolSync.setEnabled(app, enabled); DailySyncPreferences(app).read() }, { result ->
            result.onSuccess { session.syncState = it; session.notice = "" }
                .onFailure { session.notice = "自动检查设置未能保存，请重试。" }
            render()
        })
    }
    private fun showPreview(body: LinearLayout) {
        val candidate = session.candidate ?: return
        if (session.schoolPreview) {
            val preview = session.checkPreview
            val difference = session.previewDifference
            if (session.warnings.isNotEmpty()) {
                body.addView(Ui.text(this, "学校课表部分内容无法识别，暂不更新。", 16f))
                session.warnings.forEach { body.addView(Ui.text(this, it, 14f, Ui.muted)) }
            } else if (preview != null && difference != null) {
                val changed = preview.plan.data != session.baseline
                body.addView(Ui.text(this, when {
                    !changed && preview.plan.conflicts > 0 -> "没有可安全更新的课程"
                    !changed -> "课表没有变化"
                    preview.initialImport -> "导入当前学期"
                    else -> "课表有更新"
                }, 22f, bold = true).apply { tag = "schedule.preview.summary" })
                body.addView(Ui.space(this, 10))
                if (changed) {
                    body.addView(Ui.text(this, preview.plan.changes.summary(), 15f).apply { tag = "schedule.diff.school" })
                    body.addView(Ui.text(this, "${session.previewDate} 起", 13f, Ui.muted).apply { tag = "schedule.diff.local" })
                }
                if (preview.plan.conflicts > 0) {
                    body.addView(Ui.space(this, 10))
                    body.addView(Ui.text(this, "${preview.plan.conflicts}组真实差异未应用，原因及本地/学校课程见下方。", 14f, Ui.muted))
                }
                val evidence = SchoolCheckHistory.details(preview.plan, session.previewDate ?: LocalDate.now(), applied=false)
                if (evidence.isNotBlank()) {
                    body.addView(Ui.space(this, 12))
                    body.addView(Ui.text(this, evidence, 14f).apply { tag="schedule.diff.details" })
                }
                body.addView(Ui.space(this, 20))
                if (changed) action(body, "更新课表", ready, true) { previewFuture() }
                if (session.syncState?.enabled == true) {
                    body.addView(Ui.textButton(this, "用于每日检查") { confirmBackgroundLogin() })
                }
                fun rows(label: String, values: List<ScheduleOccurrence>) {
                    if (values.isEmpty()) return
                    body.addView(Ui.space(this, 16))
                    body.addView(Ui.text(this, "$label · ${values.size}次", 16f, bold = true))
                    values.take(20).forEach { row ->
                        body.addView(Ui.space(this, 10))
                        body.addView(Ui.text(this, row.name, 15f, bold = true))
                        body.addView(Ui.text(this, "${row.date} · 第${row.startPeriod}–${row.endPeriod}节 · ${row.classroom}", 13f, Ui.muted))
                    }
                }
                rows("更新后", difference.onlySchool)
                rows("更新前", difference.onlyLocal)
            }
            action(body, "返回", true) { home() }
            return
        }
        body.addView(Ui.text(this, session.source, 20f, bold = true))
        body.addView(Ui.space(this, 10))
        body.addView(Ui.text(this, "${candidate.courses.size}个课程时段", 14f, Ui.muted))
        if (candidate.courses.isEmpty()) body.addView(Ui.text(this, "备份中没有课程，恢复会清空当前课表。", 15f))
        body.addView(Ui.space(this, 20))
        action(body, "确认保存到本机", ready, true) { confirmSave() }
        action(body, "取消，不修改原课表", true) { home() }
        candidate.courses.take(20).forEach { course ->
            body.addView(Ui.space(this, 12))
            body.addView(Ui.text(this, course.name, 16f, bold = true))
            body.addView(Ui.text(this, "周${course.weekday} · ${SchedulePresentation.periodRange(course)} · ${course.classroom}", 13f, Ui.muted))
        }
    }
    private fun previewFuture() {
        val snapshot = session.baselineSnapshot ?: return
        val school = session.candidate ?: return
        val preview = session.checkPreview ?: return
        if (!session.schoolPreview || session.warnings.isNotEmpty() || preview.plan.data == snapshot.data) return
        dialog = AlertDialog.Builder(this).setTitle("更新未来课程？")
            .setMessage("${preview.plan.changes.summary()}。只更新今天起可安全修改的学校课程，历史课程和手动修改会保留。")
            .setNegativeButton("取消", null).setPositiveButton("确认更新") { _, _ ->
                val today = session.previewDate ?: return@setPositiveButton
                val current = session; val app = applicationContext; val authorization = current.authorization
                var applicationRecord:SchoolCheckRecord?=null
                current.work.submit("正在更新课表…", {
                    try {
                    require(today == LocalDate.now()) { "日期已变化，请重新检查。" }
                    val fresh = SchoolUpdateCheck.preview(snapshot, school, today)
                    val applied = if (fresh.initialImport) {
                        check(current.store.loadSnapshot() == snapshot) { "课表已变化，请重新检查。" }
                        fresh.plan.copy(data=current.store.replaceWithBackup(snapshot.data, fresh.plan.data, schoolImport = true))
                    } else current.store.applyAutomatic(snapshot, school, today)
                    val saved=applied.data
                    val record=SchoolCheckRecord(System.currentTimeMillis(),"已检查，课表已更新",SchoolCheckHistory.details(applied,today,applied=true),SchoolCheckOutcome.completed(applied.changes,applied.conflicts))
                    applicationRecord=record
                    runCatching {SchoolCheckHistoryStore(app).write(record)}
                    DailySchoolSync.authorizeAfterImport(app, authorization, saved)
                    if (fresh.plan.conflicts == 0) runCatching { SchoolImportReminder.recordSuccess(app) }
                    Triple(saved,runCatching {current.store.previousImport()}.getOrNull(),record)
                    } catch(error:Exception) {
                        if(applicationRecord==null){
                            val failed=SchoolCheckRecord(System.currentTimeMillis(),"更新未完成，请重新检查", "",SchoolCheckOutcome.failed(untouched=false))
                            applicationRecord=failed;runCatching {SchoolCheckHistoryStore(app).write(failed)}
                        }
                        throw error
                    }
                }, { result ->
                    result.onSuccess { (saved,previous,history) ->
                        current.data=saved;current.previous=previous;current.checkHistory=history;current.notice="";home()
                    }.onFailure {
                        current.checkHistory=applicationRecord ?: current.checkHistory
                        current.notice=if(applicationRecord?.outcome?.applied==true)"课表已更新，后续设置未完成，请重新检查登录状态。"
                            else "更新未完成，请重新检查。"
                        home()
                    }
                })
            }.show()
    }
    private fun confirmSave() {
        if (session.schoolPreview) return
        val candidate = session.candidate ?: return
        val before = session.baseline ?: return
        dialog = AlertDialog.Builder(this).setTitle("替换当前本地课表？")
            .setMessage("恢复后会替换当前课表，包括手动课程和历史记录。当前课表将保留为一份本机副本。")
            .setNegativeButton("返回核对", null).setPositiveButton("确认替换") { _, _ ->
                val store = session.store
                session.work.submit("正在保存本地课表…", {
                    val saved = store.replaceWithBackup(before, candidate, schoolImport = false)
                    saved to runCatching { store.previousImport() }.getOrNull()
                }, { result ->
                    result.onSuccess { (saved, previous) -> session.data=saved; session.previous=previous; session.notice="课表已恢复。"; home() }
                        .onFailure { session.notice="恢复未完成，当前课表可能已变化，请重新预览。"; render() }
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
            .setMessage("在本机加密保存本次课表登录凭据，用于每日检查，不修改当前课表。关闭自动检查或退出学校登录后会清除。")
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
