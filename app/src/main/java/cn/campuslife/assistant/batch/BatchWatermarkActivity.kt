package cn.campuslife.assistant.batch

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import cn.campuslife.assistant.ui.ContentPanel
import cn.campuslife.assistant.ui.FieldChips
import cn.campuslife.assistant.ui.ProfileActivity
import cn.campuslife.assistant.ui.Ui
import cn.campuslife.assistant.ui.WatermarkPreview
import cn.campuslife.watermark.*

/** Native, local-only batch editor. The application session owns all I/O and processing. */
class BatchWatermarkActivity : Activity() {
    private lateinit var session: BatchSession
    private lateinit var status: TextView
    private lateinit var currentPhoto: TextView
    private lateinit var previewStatus: TextView
    private lateinit var locked: TextView
    private lateinit var progress: ProgressBar
    private lateinit var editorHost: LinearLayout
    private lateinit var contentPanel: ContentPanel
    private lateinit var preview: WatermarkPreview
    private lateinit var choose: Button
    private lateinit var start: Button
    private lateinit var pause: Button
    private lateinit var cancel: Button
    private lateinit var retry: Button
    private lateinit var details: Button
    private lateinit var directory: Button
    private lateinit var format: Button
    private lateinit var size: Button
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var reload: Button
    private var editorBound = false
    private var boundJob: String? = null
    private var replacementIndex: Int? = null
    private var pendingSelection: List<Uri>? = null
    private var pendingFlags = 0
    private var pendingTree: Uri? = null
    private var treeFlags = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = BatchSessions.acquire(applicationContext)
        replacementIndex = savedInstanceState?.getInt("replacement", -1)?.takeIf { it >= 0 }
        pendingSelection = savedInstanceState?.getStringArrayList("selection")?.map(Uri::parse)
        pendingFlags = savedInstanceState?.getInt("selectionFlags") ?: 0
        pendingTree = savedInstanceState?.getString("tree")?.let(Uri::parse)
        treeFlags = savedInstanceState?.getInt("treeFlags") ?: 0
        buildPage()
    }
    override fun onStart() {
        super.onStart()
        session.bind { render() }
        if (session.loaded && !session.busy && session.preview == null && session.job != null)
            session.requestPreview(session.selectedIndex)
    }
    override fun onResume() { super.onResume(); session.refreshProfile() }
    override fun onStop() {
        session.unbind()
        if (!isChangingConfigurations) session.background()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("replacement", replacementIndex ?: -1)
        pendingSelection?.let { outState.putStringArrayList("selection", ArrayList(it.map(Uri::toString))) }
        outState.putInt("selectionFlags", pendingFlags)
        pendingTree?.let { outState.putString("tree", it.toString()) }
        outState.putInt("treeFlags", treeFlags)
        super.onSaveInstanceState(outState)
    }

    private fun buildPage() {
        val root = Ui.page(this)
        root.addView(Ui.header(this, "批量照片水印", "同一套水印，逐张处理并另存为新图片"))
        val body = Ui.column(this).apply { setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), Ui.dp(context, 20)) }
        root.addView(Ui.scroll(this, body), LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(Ui.text(this, "每批 1–50 张 JPEG / PNG；单张最多 64 MiB、8000 万像素。只保留列表与当前预览，不一次加载整批原图。", 12f, Ui.muted))
        body.addView(Ui.space(this, 8))
        choose = Ui.button(this, "选择多张照片") { chooseNewBatch() }.apply { tag = "batch_choose" }
        body.addView(choose)
        editorHost = Ui.column(this)
        body.addView(editorHost)
        locked = Ui.text(this, "", 12f, Ui.muted)
        body.addView(locked)
        val output = Ui.row(this)
        format = Ui.button(this, "JPEG") { chooseFormat() }.apply { tag = "batch_format" }
        size = Ui.button(this, "原始尺寸") { chooseSize() }.apply { tag = "batch_size" }
        output.addView(format, weighted()); output.addView(size, weighted())
        body.addView(output)
        directory = Ui.button(this, "选择批量保存目录") { chooseDirectory() }.apply {
            tag = "batch_directory"; visibility = if (Build.VERSION.SDK_INT <= 28) View.VISIBLE else View.GONE
        }
        body.addView(directory)
        body.addView(Ui.text(this, if (Build.VERSION.SDK_INT >= 29) "保存至相册 Pictures/校园生活助手；每张新建，文件名附序号与防重标记。"
            else "Android 8/9 请先选择一个目录；整批逐张新建文件。权限失效时可重新选择目录。", 12f, Ui.muted))
        status = Ui.text(this, "正在读取本机任务…", 13f).apply { tag = "batch_status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body.addView(Ui.space(this, 8)); body.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { tag = "batch_progress" }
        body.addView(progress, LinearLayout.LayoutParams(-1, Ui.dp(this, 8)))
        start = Ui.button(this, "开始导出", true) { startExport() }.apply { tag = "batch_start" }
        body.addView(start)
        val running = Ui.row(this)
        pause = Ui.button(this, "暂停") { session.pause() }.apply { tag = "batch_pause" }
        cancel = Ui.button(this, "取消剩余") { session.pause(true) }.apply { tag = "batch_cancel" }
        running.addView(pause, weighted()); running.addView(cancel, weighted()); body.addView(running)
        retry = Ui.button(this, "将失败项加入重试") { session.retryFailures() }.apply { tag = "batch_retry" }
        body.addView(retry)
        details = Ui.button(this, "查看明细 / 重新选择 / 跳过") { showDetails() }.apply { tag = "batch_details" }
        body.addView(details)
        reload = Ui.button(this, "重新读取任务记录") { session.load() }.apply { tag = "batch_reload" }
        body.addView(reload)
        body.addView(Ui.space(this, 10))
        currentPhoto = Ui.text(this, "尚未选择照片", 14f, bold = true).apply { tag = "batch_current" }
        body.addView(currentPhoto)
        val navigation = Ui.row(this)
        previous = Ui.button(this, "上一张") { session.requestPreview(session.selectedIndex - 1) }
        next = Ui.button(this, "下一张") { session.requestPreview(session.selectedIndex + 1) }
        navigation.addView(previous, weighted()); navigation.addView(next, weighted()); body.addView(navigation)
        previewStatus = Ui.text(this, "", 12f, Ui.muted).apply { tag = "batch_preview_status" }
        body.addView(previewStatus)
        preview = WatermarkPreview(this).apply { tag = "batch_preview"; minimumHeight = Ui.dp(context, 260) }
        preview.onMoved = { x, y -> if (session.editable) {
            configure(session.options.copy(config = session.options.config.copy(style = session.options.config.style.copy(
                position = Position.CUSTOM, anchorX = x, anchorY = y))))
            contentPanel.refreshPosition()
        } }
        body.addView(preview, LinearLayout.LayoutParams(-1, Ui.dp(this, 320)))
        render()
    }
    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(this@BatchWatermarkActivity, 4) }

    private fun bindEditor() {
        editorHost.removeAllViews()
        val options = session.options
        contentPanel = ContentPanel(this, options.details, options.config,
            { configure(session.options.copy(details = it)) }, { configure(session.options.copy(config = it)) },
            { startActivity(Intent(this, ProfileActivity::class.java)) }, { session.options.config })
        editorHost.addView(contentPanel)
        editorHost.addView(CheckBox(this).apply {
            text = "统一使用上面填写的拍摄时间"; textSize = 14f; setTextColor(Ui.ink)
            minHeight = Ui.dp(context, 48); tag = "batch_unified_time"; isChecked = options.unifiedTime
            setOnCheckedChangeListener { _, checked -> configure(session.options.copy(unifiedTime = checked)) }
        })
        editorHost.addView(Ui.text(this, "默认读取每张照片自己的 EXIF 拍摄时间；没有则留空。勾选统一时间后才使用手填值。", 12f, Ui.muted))
        editorHost.addView(Ui.space(this, 8))
        editorHost.addView(Ui.text(this, "文件名字段（按点选顺序）", 14f, bold = true))
        editorHost.addView(FieldChips(this, defaultOrder, options.namingFields, "batch_naming", "文件名包含") {
            configure(session.options.copy(namingFields = it))
        })
        editorBound = true; boundJob = session.job?.id
    }
    private fun configure(options: BatchOptions) { session.configure(options); render() }

    private fun render() {
        if (!::preview.isInitialized) return
        val job = session.job
        if (session.loaded && (!editorBound || boundJob != job?.id)) bindEditor()
        if (editorBound) Ui.enabled(editorHost, session.editable)
        choose.isEnabled = session.loaded && !session.busy && !session.blocked
        format.isEnabled = session.editable; size.isEnabled = session.editable
        format.text = session.options.format.name
        size.text = session.options.maxEdge?.let { "最长边 $it 像素" } ?: "原始尺寸"
        directory.isEnabled = session.loaded && !session.busy && !session.blocked
        directory.text = if (session.treeUri == null) "选择批量保存目录" else "重新选择保存目录"
        val counts = job?.counts()
        val active = session.busy && job?.state == BatchRunState.RUNNING
        progress.isIndeterminate = session.busy && !active
        progress.max = job?.items?.size ?: 1
        progress.progress = counts?.let { it.success + it.failed + it.skipped } ?: 0
        progress.visibility = if (job != null || session.busy) View.VISIBLE else View.GONE
        val summary = counts?.let { "成功 ${it.success} · 失败 ${it.failed} · 跳过 ${it.skipped} · 未处理 ${it.unprocessed}" +
            if (it.review > 0) "\n其中 ${it.review} 项输出需核对，请打开明细" else "" }
        val phase = when (job?.state) {
            BatchRunState.READY -> "待开始"
            BatchRunState.RUNNING -> "正在处理第 ${session.selectedIndex + 1}/${job.items.size} 张"
            BatchRunState.PAUSED -> "已暂停，点击继续处理未完成项"
            BatchRunState.CANCELLED -> "已取消剩余处理；已完成图片保留，可继续未处理项"
            BatchRunState.COMPLETED -> "本批处理结束"
            null -> if (session.loaded) "先选择需要加水印的照片" else "正在读取本机任务…"
        }
        status.text = listOfNotNull(phase, summary, session.message).joinToString("\n")
        locked.text = if (session.editable) "选好字段、位置、文件名与尺寸后开始。更改仅用于批量模块。"
            else if (job != null) "本任务已固定启动时的资料与设置；新设置请重新选择照片开始新批次。" else ""
        start.text = if (job?.state == BatchRunState.READY) "开始导出" else "继续未处理项"
        start.isEnabled = session.loaded && !session.busy && !session.blocked && counts != null && counts.unprocessed > 0
        pause.visibility = if (active) View.VISIBLE else View.GONE
        cancel.visibility = if (job != null && (active || (counts?.unprocessed ?: 0) > 0)) View.VISIBLE else View.GONE
        pause.isEnabled = active; cancel.isEnabled = active || (!session.busy && !session.blocked)
        retry.visibility = if (job?.items?.any { it.state == BatchItemState.FAILED } == true) View.VISIBLE else View.GONE
        retry.isEnabled = !session.busy && !session.blocked
        details.isEnabled = job != null && !session.blocked
        reload.visibility = if (session.blocked) View.VISIBLE else View.GONE
        reload.isEnabled = !session.busy
        previous.isEnabled = job != null && !session.busy && !session.blocked && session.selectedIndex > 0
        next.isEnabled = job != null && !session.busy && !session.blocked && session.selectedIndex < job.items.lastIndex
        val item = job?.items?.getOrNull(session.selectedIndex)
        currentPhoto.text = item?.let { "${session.selectedIndex + 1}/${job.items.size}  ${it.input.displayName.take(80)}" } ?: "尚未选择照片"
        previewStatus.text = when {
            session.previewBusy -> "正在读取当前照片预览…"
            session.previewError != null -> session.previewError
            active -> "逐张处理，预览暂时释放以留出内存"
            session.preview != null -> "按原图坐标预览，可拖动水印；导出使用原图或你明确选择的尺寸"
            else -> "选择照片，或在明细中选择一张预览"
        }
        preview.isEnabled = session.editable
        preview.exportFormat = session.options.format
        preview.update(session.preview, formattedFields(session.options.profile,
            session.options.detailsFor(session.preview?.capturedAt), session.options.config), session.options.config)

        if (session.loaded && !session.busy && !session.blocked) {
            pendingSelection?.let { selection -> pendingSelection = null; session.select(selection, pendingFlags, replacementIndex); replacementIndex = null; return }
            pendingTree?.let { tree -> pendingTree = null; session.setTree(tree, treeFlags); return }
            if (session.needsContinue) {
                session.needsContinue = false
                AlertDialog.Builder(this).setTitle("发现上次的批量任务")
                    .setMessage("是否继续未完成项目？已成功项不会再次导出。写入中断项需先在明细中核对；配置沿用上次快照。")
                    .setPositiveButton("继续") { _, _ -> startExport() }.setNegativeButton("稍后", null).show()
            }
        }
    }

    private fun chooseNewBatch() {
        if (session.busy || session.blocked) return
        val old = session.job
        if (old?.hasUnresolvedOutputs() == true) {
            AlertDialog.Builder(this).setTitle("先处理未完成输出")
                .setMessage("上次有图片尚未确认保存。请继续保存，或在明细中核对、处理后再选择新批次，避免遗留未完成文件。")
                .setPositiveButton("查看明细") { _, _ -> showDetails() }.setNegativeButton("返回", null).show()
            return
        }
        if (old != null && old.state != BatchRunState.READY && old.items.any { it.state !in setOf(BatchItemState.SUCCESS, BatchItemState.SKIPPED) })
            AlertDialog.Builder(this).setTitle("重新选择一批照片？")
                .setMessage("选择成功后替换当前任务列表；已创建的图片保留。输出待核对项请先在明细中核对。")
                .setPositiveButton("选择新批次") { _, _ -> pickPhotos() }.setNegativeButton("返回", null).show()
        else pickPhotos()
    }
    private fun pickPhotos(index: Int? = null) {
        replacementIndex = index
        try { startActivityForResult(BatchIntents.photos(index == null), PHOTOS) }
        catch (_: Exception) { Ui.toast(this, "没有可用的文件选择器，请检查系统文件应用") }
    }
    private fun chooseDirectory() {
        try { startActivityForResult(BatchIntents.directory(), TREE) }
        catch (_: Exception) { Ui.toast(this, "无法打开目录选择器，请检查系统文件应用") }
    }
    private fun startExport() {
        if (Build.VERSION.SDK_INT <= 28 && session.treeUri == null) { chooseDirectory(); return }
        session.start()
    }
    private fun chooseFormat() {
        val formats = ExportFormat.entries
        AlertDialog.Builder(this).setTitle("导出格式").setSingleChoiceItems(arrayOf("JPEG（透明处铺白）", "PNG（保留透明）"),
            formats.indexOf(session.options.format)) { dialog, index -> configure(session.options.copy(format = formats[index])); dialog.dismiss() }
            .setNegativeButton("取消", null).show()
    }
    private fun chooseSize() {
        val sizes = listOf(null, 4096, 2048, 1280)
        AlertDialog.Builder(this).setTitle("输出尺寸（不会放大或自动降质）")
            .setSingleChoiceItems(arrayOf("原始尺寸", "最长边 4096 像素", "最长边 2048 像素", "最长边 1280 像素"), sizes.indexOf(session.options.maxEdge)) { dialog, index ->
                configure(session.options.copy(maxEdge = sizes[index])); dialog.dismiss()
            }.setNegativeButton("取消", null).show()
    }
    @Deprecated("Platform result API used by native Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == PHOTOS) {
            val uris = data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }
                ?: listOfNotNull(data.data)
            pendingSelection = uris.distinct(); pendingFlags = data.flags
        } else if (requestCode == TREE) { pendingTree = data.data; treeFlags = data.flags }
        render()
    }
    private fun showDetails() {
        val job = session.job ?: return
        AlertDialog.Builder(this).setTitle("本批明细 · ${job.items.size} 张")
            .setItems(job.items.mapIndexed { index, item -> "${index + 1}. ${label(item.state)}  ${item.input.displayName.take(60)}" }.toTypedArray()) { _, index -> showItem(index) }
            .setNegativeButton("关闭", null).show()
    }
    private fun showItem(index: Int) {
        val item = session.job?.items?.getOrNull(index) ?: return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (!session.busy) actions += "预览此张" to { session.requestPreview(index) }
        item.outputUri?.let { uri -> actions += "打开已创建的输出" to {
            try { startActivity(BatchIntents.view(Uri.parse(uri), session.options.format)) }
            catch (_: Exception) { Ui.toast(this, "暂时无法打开输出；请到相册或保存目录核对文件名") }
        } }
        if (!session.busy && item.state in setOf(BatchItemState.PENDING, BatchItemState.FAILED)) {
            actions += "重新选择这张照片" to { pickPhotos(index) }
            actions += "跳过此张" to { session.skip(index) }
        }
        if (!session.busy && item.state in setOf(BatchItemState.REVIEW, BatchItemState.WRITTEN)) {
            if (item.outputUri != null) {
                actions += "已查看，保留已发布输出并跳过" to { session.preserve(index) }
                if (Build.VERSION.SDK_INT <= 28) actions += "已手动处理目录文件，仅结束此项" to {
                    AlertDialog.Builder(this).setTitle("确认结束此项？")
                        .setMessage("请先在保存目录核对文件：可保留、手动删除残缺文件，或确认文件已经不存在。此操作只跳过本项并保留输出地址记录，不读取或删除任何文件；之后可选择新批次。")
                        .setPositiveButton("已核对，结束此项") { _, _ -> session.acknowledgeDocument(index) }
                        .setNegativeButton("返回核对", null).show()
                }
            }
            if (item.outputUri == null || Build.VERSION.SDK_INT >= 29) {
                fun confirmResolution(retry: Boolean) {
                    AlertDialog.Builder(this).setTitle(if (retry) "处理未完成输出并重试？" else "处理未完成输出并跳过？")
                        .setMessage(if (item.outputUri == null) "上次创建时中断，没有取得文件地址。请先到相册或目录核对文件名；不会扫描或删除任何文件。确认后${if (retry) "重新导出，可能出现副本" else "跳过此张"}。"
                            else "仅移除这项由本任务创建、尚未发布到相册的未完成文件，然后${if (retry) "重新加入导出" else "跳过此张"}。原图和已保存图片不会删除；无法确认归属时保留原记录。")
                        .setPositiveButton("确认") { _, _ -> session.resolve(index, retry) }.setNegativeButton("取消", null).show()
                }
                actions += (if (item.outputUri == null) "核对后跳过" else "移除未完成输出并跳过") to { confirmResolution(false) }
                actions += (if (item.outputUri == null) "核对后重新导出" else "移除未完成输出并重试") to { confirmResolution(true) }
            }
        }
        val info = listOfNotNull(label(item.state), item.error, if (!item.input.persisted) "原照片为临时授权；失效后请重新选择" else null,
            item.outputName?.let { "输出文件：$it" }).joinToString("\n")
        // The details and action list share one scrollable dialog, so long names/errors remain accessible.
        val content = Ui.column(this).apply { setPadding(Ui.dp(context, 18), Ui.dp(context, 12), Ui.dp(context, 18), Ui.dp(context, 12)) }
        content.addView(Ui.text(this, info, 14f))
        val dialog = AlertDialog.Builder(this).setTitle("第 ${index + 1} 张").setView(Ui.scroll(this, content)).setNegativeButton("关闭", null).create()
        actions.forEach { (name, action) -> content.addView(Ui.button(this, name) { dialog.dismiss(); action() }) }
        dialog.show()
    }
    private fun label(state: BatchItemState) = when (state) {
        BatchItemState.PENDING -> "未处理"
        BatchItemState.PROCESSING -> "读取 / 渲染中"
        BatchItemState.CREATING -> "创建输出中"
        BatchItemState.WRITING -> "写入中"
        BatchItemState.WRITTEN -> "写入完成，待确认保存"
        BatchItemState.SUCCESS -> "成功"
        BatchItemState.FAILED -> "失败，可重试"
        BatchItemState.SKIPPED -> "已跳过"
        BatchItemState.REVIEW -> "输出待核对"
    }
    private companion object { const val PHOTOS = 701; const val TREE = 702 }
}
