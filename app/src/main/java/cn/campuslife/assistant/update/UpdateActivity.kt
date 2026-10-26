package cn.campuslife.assistant.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import cn.campuslife.assistant.BuildConfig
import cn.campuslife.assistant.ui.RetainedWork
import cn.campuslife.assistant.ui.Ui
import java.util.Locale

class UpdateActivity : Activity() {
    private class Session(context: Context) {
        val repository = UpdateRepository(context)
        val downloads = UpdateDownloads(context)
        val work = RetainedWork<UpdateActivity>({ Handler(Looper.getMainLooper()).post(it) })
        var started = false
        var info: UpdatePackage? = null
        var download = UpdateDownloadState()
        var message = "正在检查更新…"
    }
    private lateinit var session: Session
    private lateinit var page: LinearLayout
    private lateinit var body: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var foreground = false
    private var permissionOpened = false
    private var prompt: AlertDialog? = null
    private val polling = Runnable { refreshDownload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = lastNonConfigurationInstance as? Session ?: Session(applicationContext)
        permissionOpened = savedInstanceState?.getBoolean("update.permission") ?: false
        page = Ui.page(this)
        page.addView(Ui.header(this, "应用更新"))
        body = Ui.column(this).apply { setPadding(dp(20), 0, dp(20), dp(24)) }
        page.addView(Ui.scroll(this, body), LinearLayout.LayoutParams(-1, 0, 1f))
        session.work.bind(this) { _, _ -> render() }
        if (!session.started) {
            session.started = true
            check(intent.getBooleanExtra("manual", true))
        }
    }
    override fun onRetainNonConfigurationInstance(): Any = session
    override fun onResume() {
        super.onResume(); foreground = true
        if (permissionOpened) { permissionOpened = false; session.message = "如已允许安装，请再次点击“安装更新”；取消不影响当前版本。" }
        render()
    }
    override fun onPause() { foreground = false; handler.removeCallbacks(polling); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("update.permission", permissionOpened); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); prompt?.dismiss(); prompt = null
        session.work.detach(this)
        if (!isChangingConfigurations) session.work.close()
        super.onDestroy()
    }

    private fun check(manual: Boolean) {
        val repository = session.repository; val downloads = session.downloads
        session.work.submit("正在检查更新…", { repository.check(manual) to downloads.state() }, { result ->
            result.onSuccess { (check, state) ->
                session.download = state
                session.info = if (state.phase in active + UpdatePhase.READY) state.info else check.update
                session.message = state.message.ifBlank { check.message }
                if (manual && check.offer && state.phase !in active && state.phase != UpdatePhase.READY) {
                    showOffer(requireNotNull(check.update))
                }
            }.onFailure { session.message = it.message?.take(240) ?: "更新信息暂不可用，请稍后再试" }
            render(); maybeVerify()
        })
    }

    private fun render() {
        body.removeAllViews()
        body.addView(Ui.text(this, "当前版本 ${BuildConfig.VERSION_NAME}", 14f, Ui.muted))
        body.addView(Ui.space(this, 16))
        val info = session.info
        val state = session.download
        if (info != null) {
            body.addView(Ui.text(this, "新版本 ${info.versionName}", 24f, bold = true))
            body.addView(Ui.space(this, 8))
            body.addView(Ui.text(this, "安装包 ${size(info.size)}", 13f, Ui.muted))
            body.addView(Ui.space(this, 18))
            body.addView(Ui.text(this, "更新内容", 15f, bold = true))
            body.addView(Ui.space(this, 8))
            body.addView(Ui.text(this, info.notes.ifBlank { "此版本未填写更新说明" }, 15f))
            body.addView(Ui.space(this, 22))
        }
        val message = if (session.work.busy) session.work.label else session.message.ifBlank { state.message }
        body.addView(Ui.text(this, message, 14f, Ui.muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; tag = "update_status" })
        if (state.phase in active) {
            body.addView(Ui.space(this, 12))
            body.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; isIndeterminate = state.total <= 0
                progress = if (state.total > 0) ((state.downloaded * 1000 / state.total).coerceIn(0, 1000)).toInt() else 0
                contentDescription = "安装包下载进度"
            }, LinearLayout.LayoutParams(-1, dp(8)))
            body.addView(Ui.space(this, 6))
            body.addView(Ui.text(this, "${size(state.downloaded)} / ${size(state.total)}", 13f, Ui.muted))
        }
        body.addView(Ui.space(this, 18))
        if (info != null && (state.phase !in active || state.phase == UpdatePhase.DOWNLOADED)) {
            val ready = state.phase == UpdatePhase.READY
            body.addView(Ui.button(this, if (ready) "安装更新" else if (state.phase == UpdatePhase.DOWNLOADED) "重试校验" else if (state.phase == UpdatePhase.FAILED) "重新下载" else "下载更新", true) {
                if (ready) install() else if (state.phase == UpdatePhase.DOWNLOADED) maybeVerify() else download(info)
            }.apply { isEnabled = !session.work.busy; tag = "update_primary" }, LinearLayout.LayoutParams(-1, -2))
            body.addView(Ui.space(this, 10))
        }
        if (state.phase in active || state.phase == UpdatePhase.READY) {
            body.addView(Ui.button(this, "取消本次更新") { cancel() }.apply { isEnabled = !session.work.busy }, LinearLayout.LayoutParams(-1, -2))
            body.addView(Ui.space(this, 10))
        }
        body.addView(Ui.button(this, "重新检查") { check(true) }.apply { isEnabled = !session.work.busy && state.phase !in active }, LinearLayout.LayoutParams(-1, -2))
        body.addView(Ui.space(this, 16))
        body.addView(Ui.text(this, "仅从本仓库的正式Release获取更新。安装需要你在系统界面确认，不会卸载当前应用。", 12f, Ui.muted))
        handler.removeCallbacks(polling)
        if (foreground && state.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.PAUSED)) handler.postDelayed(polling, 1000)
    }

    private fun refreshDownload() {
        if (!foreground) return
        if (session.work.busy) { handler.postDelayed(polling, 1000); return }
        val downloads = session.downloads
        session.work.submit("正在读取下载状态…", { downloads.state() }, { result ->
            result.onSuccess { session.download = it; session.info = it.info ?: session.info; session.message = it.message.ifBlank { "正在下载更新" } }
                .onFailure { session.message = "下载状态暂不可用，可返回后重试" }
            render(); maybeVerify()
        })
    }
    private fun maybeVerify() {
        if (session.download.phase != UpdatePhase.DOWNLOADED || session.work.busy) return
        val downloads = session.downloads
        session.work.submit("下载完成，正在校验安装包…", {
            val verified = runCatching { downloads.verifyDownloaded() }
            verified to downloads.state()
        }, { result ->
            result.onSuccess { (verified, state) ->
                session.download = state
                session.message = verified.exceptionOrNull()?.message?.take(240) ?: "安装包已校验"
            }.onFailure { session.message = it.message?.take(240) ?: "安装包校验失败，请重新下载" }
            render()
        })
    }
    private fun download(info: UpdatePackage) {
        val downloads = session.downloads
        session.work.submit("正在创建下载任务…", { downloads.start(info); downloads.state() }, { result ->
            result.onSuccess { session.download = it; session.message = "正在下载更新" }.onFailure { session.message = it.message?.take(240) ?: "下载未能开始，请重试" }
            render(); maybeVerify()
        })
    }
    private fun cancel() {
        val downloads = session.downloads
        session.work.submit("正在取消下载…", { downloads.cancel(); downloads.state() }, { result ->
            result.onSuccess { session.download = it; session.message = "已取消本次更新" }.onFailure { session.message = "取消未完成，请稍后再试" }
            render()
        })
    }
    private fun install() {
        if (!packageManager.canRequestPackageInstalls()) {
            prompt?.dismiss()
            prompt = AlertDialog.Builder(this).setTitle("允许安装更新")
                .setMessage("系统需要你允许校园工具箱安装应用。只在你主动更新时使用；取消不影响其他功能。")
                .setNegativeButton("取消", null).setPositiveButton("去设置") { _, _ ->
                    runCatching { startActivity(UpdateInstaller.permissionIntent(this)); permissionOpened = true }
                        .onFailure { session.message = "无法打开系统安装权限设置"; render() }
                }.show()
            return
        }
        val downloads = session.downloads
        session.work.submit("正在重新核对安装包…", {
            val verified = runCatching { downloads.verifyDownloaded() }
            verified to downloads.state()
        }, { result ->
            result.onSuccess { (verified, state) ->
                session.download = state
                verified.onSuccess { file ->
                    runCatching { startActivity(UpdateInstaller.installIntent(this, file)) }
                        .onSuccess { session.message = "请在系统界面确认安装；取消后仍可继续使用当前版本。" }
                        .onFailure { session.message = "系统未能打开安装界面，请稍后重试" }
                }.onFailure { session.message = it.message?.take(240) ?: "安装包未通过校验，请重新下载" }
            }.onFailure { session.message = it.message?.take(240) ?: "安装包未通过校验，请重新下载" }
            render()
        })
    }
    private fun showOffer(info: UpdatePackage) {
        prompt?.dismiss()
        prompt = UpdatePrompts.show(this, info, {
            val repository = session.repository
            session.work.submit("正在保存选择…", { repository.ignore(info) }, { result ->
                session.message = if (result.isSuccess) "已暂缓此版本，可随时手动检查" else "选择未能保存，请稍后再试"
                render()
            })
        }, { session.info = info; render() })
    }
    private fun dp(value: Int) = Ui.dp(this, value)
    companion object {
        private val active = setOf(UpdatePhase.DOWNLOADING, UpdatePhase.PAUSED, UpdatePhase.DOWNLOADED)
        private fun size(bytes: Long) = String.format(Locale.ROOT, "%.1f MiB", bytes.coerceAtLeast(0) / (1024.0 * 1024.0))
    }
}

internal object UpdatePrompts {
    fun show(activity: Activity, info: UpdatePackage, later: () -> Unit, accept: () -> Unit): AlertDialog =
        AlertDialog.Builder(activity).setTitle("发现新版本")
            .setMessage("当前版本：${BuildConfig.VERSION_NAME}\n最新版本：${info.versionName}\n\n${info.notes.ifBlank { "此版本未填写更新说明" }}")
            .setNegativeButton("稍后再说") { _, _ -> later() }.setPositiveButton("立即更新") { _, _ -> accept() }.show()
}
