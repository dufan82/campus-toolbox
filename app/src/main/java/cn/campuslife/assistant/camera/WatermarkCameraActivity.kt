package cn.campuslife.assistant.camera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.image.PhotoExporter
import cn.campuslife.assistant.image.PhotoLoader
import cn.campuslife.assistant.ui.RetainedWork
import cn.campuslife.assistant.ui.ToolGlyph
import cn.campuslife.assistant.ui.Ui
import cn.campuslife.watermark.CampusProfile
import cn.campuslife.watermark.FieldKey
import cn.campuslife.watermark.Position
import cn.campuslife.watermark.Viewport
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import kotlin.math.roundToInt

class WatermarkCameraActivity : Activity() {
    companion object {
        const val PICK_FROM_ALBUM = RESULT_FIRST_USER + 1
        private const val CAMERA_PERMISSION = 401
        private const val LOCATION_PERMISSION = 402
        private const val SAVE_DOCUMENT = 403
        private val chrome = 0xff111f35.toInt()
        private val muted = 0xffbecbda.toInt()
    }

    private data class Review(val file: File, val image: Bitmap, val width: Int, val height: Int)

    private class Session {
        private val main = Handler(Looper.getMainLooper())
        val work = RetainedWork<WatermarkCameraActivity>({ main.post { it() } })
        var review: Review? = null
        var saved = false
        var choosingFile = false
        @Volatile var closed = false
        private var owned: File? = null

        @Synchronized fun own(file: File): Boolean {
            if (closed) { remove(file); return false }
            owned = file
            return true
        }

        @Synchronized fun workingFile(): File? = owned

        @Synchronized fun discard() {
            owned?.let(::remove)
            owned = null
            review = null
            saved = false
        }

        @Synchronized fun close() { closed = true; work.close(); discard() }

        private fun remove(file: File) { file.delete(); file.parentFile?.delete() }
    }

    private val main = Handler(Looper.getMainLooper())
    private val local by lazy { LocalStore(this) }
    private val choices by lazy { CameraSettingsStore(this) }
    private lateinit var model: Session
    private lateinit var options: CameraSettings
    private lateinit var profile: CampusProfile
    private lateinit var driver: Camera2Session
    private lateinit var location: CameraLocation
    private lateinit var frame: FrameLayout
    private lateinit var live: FrameLayout
    private lateinit var overlay: CameraStampView
    private lateinit var result: ImageView
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var empty: LinearLayout
    private lateinit var emptyMessage: TextView
    private lateinit var retry: Button
    private lateinit var light: Button
    private lateinit var lens: Button
    private lateinit var place: Button
    private lateinit var shutter: Button
    private lateinit var settingsButton: Button
    private lateinit var album: Button
    private lateinit var shootControls: LinearLayout
    private lateinit var reviewControls: LinearLayout
    private lateinit var again: Button
    private lateinit var save: Button
    private var state = CameraState()
    private var foreground = false
    private var front = false
    private var rotation = 0
    private var shooting = false
    private var askedCamera = false
    private var shownImage: Bitmap? = null
    private val displayManager by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            val display = window.decorView.display ?: return
            if (!foreground || display.displayId != id) return
            val next = cameraDisplayDegrees(display.rotation)
            if (next != rotation && !shooting) beginPreview()
        }
    }
    private val clock = object : Runnable {
        override fun run() {
            if (!foreground) return
            refreshStamp()
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        model = lastNonConfigurationInstance as? Session ?: Session()
        options = choices.read()
        profile = local.profile()
        front = state?.getBoolean("front") ?: false
        askedCamera = state?.getBoolean("asked_camera") ?: false
        location = CameraLocation(applicationContext) { refreshStamp() }
        buildScreen()
        model.work.bind(this) { _, _ -> refresh() }
        if (lastNonConfigurationInstance == null) restoreReview(state)
        refresh()
    }

    private fun px(dp: Int) = Ui.dp(this, dp)

    private fun buildScreen() {
        val page = Ui.column(this).apply { setBackgroundColor(chrome); tag = "watermark_camera_screen" }
        setContentView(page)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = chrome
        window.navigationBarColor = chrome
        WindowCompat.getInsetsController(window, page).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(page) { target, insets ->
            val bounds = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            target.setPadding(bounds.left, bounds.top, bounds.right, bounds.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(page)
        val top = Ui.row(this).apply { setPadding(px(8), 0, px(12), 0) }
        top.addView(icon("back", "返回") { onBackPressed() }, LinearLayout.LayoutParams(px(48), px(48)))
        title = Ui.text(this, "水印相机", 19f, Color.WHITE, true)
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        light = icon("flash", "开启补光") {
            if (!driver.light()) Ui.toast(this, "这支镜头暂不能开启补光")
        }.apply { tag = "camera_light" }
        lens = icon("flip_camera", "切换前后摄像头") {
            front = !front
            beginPreview()
        }.apply { tag = "camera_flip" }
        top.addView(light, LinearLayout.LayoutParams(px(48), px(48)))
        top.addView(lens, LinearLayout.LayoutParams(px(48), px(48)))
        page.addView(top, LinearLayout.LayoutParams(-1, -2))

        frame = FrameLayout(this).apply { setBackgroundColor(0xff070d16.toInt()); tag = "camera_viewfinder" }
        live = FrameLayout(this)
        val surface = SurfaceView(this)
        live.addView(surface, FrameLayout.LayoutParams(-1, -1))
        overlay = CameraStampView(this).apply {
            tag = "camera_stamp"
            onMove = { x, y, done ->
                if (!shooting && !model.work.busy) {
                    options = options.copy(position = Position.CUSTOM, anchorX = x, anchorY = y)
                    if (done) choices.save(options)
                    refreshStamp()
                }
            }
        }
        live.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        frame.addView(live, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        result = ImageView(this).apply {
            tag = "camera_review"; scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "已合成的水印照片"
        }
        frame.addView(result, FrameLayout.LayoutParams(-1, -1))
        empty = Ui.column(this).apply {
            gravity = Gravity.CENTER
            setPadding(px(22), px(16), px(22), px(16))
        }
        emptyMessage = Ui.text(this, "", 14f, muted).apply { gravity = Gravity.CENTER }
        empty.addView(emptyMessage)
        retry = Ui.button(this, "重试") { retryCamera() }.apply { tag = "camera_retry" }
        empty.addView(retry, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(12) })
        frame.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        frame.addOnLayoutChangeListener { _, left, topY, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right-left != oldRight-oldLeft || bottom-topY != oldBottom-oldTop) fitViewfinder()
        }
        page.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        place = darkButton("地点", "pin") { chooseLocation() }.apply {
            tag = "camera_location"; maxLines = 1
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(px(6), px(6), px(6), px(6))
            compoundDrawablePadding = px(9)
        }
        page.addView(place, LinearLayout.LayoutParams(-1, -2).apply { marginStart = px(18); marginEnd = px(18) })
        detail = Ui.text(this, "", 12f, muted).apply {
            setPadding(px(18), px(6), px(18), px(3)); gravity = Gravity.CENTER
            tag = "camera_detail"
        }
        page.addView(detail)

        shootControls = Ui.row(this).apply { setPadding(px(12), px(5), px(12), px(12)) }
        settingsButton = darkButton("水印设置", "sliders", stacked = true) {
            showCameraSettings(this, options, profile, ::saveOptions)
        }.apply { tag = "camera_settings" }
        album = darkButton("相册", "image", stacked = true) {
            setResult(PICK_FROM_ALBUM)
            finish()
        }.apply { tag = "camera_album" }
        shutter = Ui.button(this, "", true) { shoot() }.apply {
            tag = "camera_shutter"; contentDescription = "拍摄"
            background = Ui.shape(context, Color.WHITE, 40, Ui.teal)
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x4435b6a0),
                null, Ui.shape(context, Color.WHITE, 40)
            )
            setPadding(px(16), px(16), px(16), px(16))
            val glyph = ToolGlyph(context, "camera", Ui.ink).apply { setBounds(0, 0, px(27), px(27)) }
            setCompoundDrawables(null, glyph, null, null)
        }
        shootControls.addView(settingsButton, LinearLayout.LayoutParams(0,-2,1f))
        shootControls.addView(shutter, LinearLayout.LayoutParams(px(67),px(67)).apply {
            marginStart = px(12); marginEnd = px(12)
        })
        shootControls.addView(album, LinearLayout.LayoutParams(0,-2,1f))
        page.addView(shootControls)

        reviewControls = Ui.row(this).apply { setPadding(px(16), px(8), px(16), px(14)) }
        again = darkButton("重拍", "refresh") { retake() }.apply { tag = "camera_retake" }
        reviewControls.addView(again, LinearLayout.LayoutParams(0, -2, 1f))
        save = Ui.button(this, "保存到相册", true) { savePhoto() }.apply {
            setTextColor(Ui.ink)
            background = Ui.shape(context, Ui.teal, 13)
            tag = "camera_save"
        }
        reviewControls.addView(save, LinearLayout.LayoutParams(0, -2, 1.65f).apply { marginStart = px(12) })
        page.addView(reviewControls)

        driver = Camera2Session(applicationContext, surface.holder) {
            state = it
            fitViewfinder()
            refresh()
        }
    }

    private fun darkButton(text: String, glyph: String, stacked: Boolean = false, clicked: () -> Unit): Button =
        Ui.textButton(this, text, clicked).apply {
            setTextColor(Color.WHITE)
            val image = ToolGlyph(context, glyph, muted).apply { setBounds(0,0,px(18),px(18)) }
            if (stacked) {
                setCompoundDrawablesRelative(null, image, null, null)
                setPadding(px(4), px(8), px(4), px(8))
            } else setCompoundDrawablesRelative(image, null, null, null)
            compoundDrawablePadding = px(5)
        }

    private fun icon(glyph: String, label: String, clicked: () -> Unit) =
        Ui.iconButton(this, glyph, label, clicked).apply {
            setCompoundDrawablesWithIntrinsicBounds(ToolGlyph(context, glyph, Color.WHITE), null, null, null)
        }

    private fun fitViewfinder() {
        val size = state.frame ?: return
        if (frame.width <= 0 || frame.height <= 0) return
        val fit = Viewport.fit(size.width.toDouble(), size.height.toDouble(), frame.width.toDouble(), frame.height.toDouble())
        val w = (size.width * fit.scale).roundToInt()
        val h = (size.height * fit.scale).roundToInt()
        val params = live.layoutParams as FrameLayout.LayoutParams
        if (params.width != w || params.height != h) {
            params.width = w; params.height = h
            live.layoutParams = params
        }
    }

    private fun hasCamera() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun beginPreview() {
        if (!foreground || !hasCamera() || shooting || model.review != null || model.work.busy) return
        rotation = cameraDisplayDegrees(window.decorView.display?.rotation ?: 0)
        driver.start(front, rotation)
    }

    private fun refresh() {
        val review = model.review
        val preview = review == null
        val busy = shooting || model.work.busy || model.choosingFile
        val allowed = hasCamera()
        title.text = if (preview) "水印相机" else "照片预览"
        live.visibility = if (preview) View.VISIBLE else View.GONE
        result.visibility = if (preview) View.GONE else View.VISIBLE
        if (shownImage !== review?.image) {
            shownImage = review?.image
            result.setImageBitmap(shownImage)
        }
        shootControls.visibility = if (preview) View.VISIBLE else View.GONE
        reviewControls.visibility = if (preview) View.GONE else View.VISIBLE
        place.visibility = if (preview) View.VISIBLE else View.GONE
        light.visibility = if (preview && state.hasLight) View.VISIBLE else View.GONE
        light.contentDescription = if (state.lightOn) "关闭补光" else "开启补光"
        light.isSelected = state.lightOn
        light.setCompoundDrawablesWithIntrinsicBounds(ToolGlyph(this, "flash", if (state.lightOn) Ui.teal else Color.WHITE), null, null, null)
        lens.visibility = if (preview && state.canSwitch) View.VISIBLE else View.GONE
        configureControl(light, state.ready && !busy)
        configureControl(lens, state.ready && !busy)
        configureControl(shutter, foreground && allowed && state.ready && !busy)
        configureControl(settingsButton, !busy)
        configureControl(album, !busy)
        configureControl(place, !busy)
        configureControl(again, !busy)
        configureControl(save, !busy && !model.saved)
        save.text = if (model.saved) "已保存" else "保存到相册"
        again.text = if (model.saved) "再拍一张" else "重拍"
        val issue = if (preview && !busy) when {
            !allowed -> "允许相机后即可拍摄，也可以从相册选图"
            state.error != null -> state.error
            !state.ready -> "正在打开相机…"
            else -> null
        } else null
        empty.visibility = if (issue == null) View.GONE else View.VISIBLE
        emptyMessage.text = issue.orEmpty()
        retry.visibility = if (!allowed || state.error != null) View.VISIBLE else View.GONE
        retry.text = if (allowed) "重试" else "允许相机"
        val note = when {
            shooting -> "正在拍摄…"
            model.work.busy -> model.work.label
            review != null && model.saved -> "已保存照片"
            review != null -> "${review.width} × ${review.height} px · 原尺寸"
            else -> ""
        }
        detail.text = note
        detail.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
        refreshStamp()
    }

    private fun configureControl(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else .45f
    }

    private fun refreshStamp() {
        if (model.review != null) return
        val auto = currentPlace()
        val label = when {
            CameraField.PLACE !in options.fields -> "地点 · 已隐藏"
            options.manualPlace.isNotBlank() -> "手填 · " + options.manualPlace
            auto != null -> "定位 · " + auto
            !options.automaticPlace -> "添加地点"
            !location.permitted() -> "定位未授权"
            location.message.isNotBlank() -> location.message
            else -> "等待定位"
        }
        if (place.text.toString() != label) place.text = label
        if (!shooting && !model.work.busy) {
            overlay.show(state.frame, options.stamp(ZonedDateTime.now(), profile, auto))
        }
    }

    private fun currentPlace() = if (options.automaticPlace) location.current() else null

    private fun syncLocation(refresh: Boolean = false) {
        if (foreground && model.review == null && !shooting && !model.work.busy &&
            options.automaticPlace && options.manualPlace.isEmpty() && CameraField.PLACE in options.fields) {
            location.start(refresh)
        } else location.stop()
        refreshStamp()
    }

    private fun retryCamera() {
        if (hasCamera()) {
            beginPreview()
        } else if (askedCamera && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: Exception) { Ui.toast(this, "请在系统设置中允许校伴使用相机") }
        } else askCamera()
    }

    private fun askCamera() {
        askedCamera = true
        requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
    }

    private fun chooseLocation() {
        val summary = when {
            options.manualPlace.isNotBlank() -> "手填地点：" + options.manualPlace
            currentPlace() != null -> "自动定位：" + currentPlace()
            else -> "可自动获取当前位置，也可手填。不开启定位也能拍照。"
        }
        AlertDialog.Builder(this).setTitle("拍摄地点").setMessage(summary)
            .setPositiveButton(if (options.automaticPlace && options.manualPlace.isEmpty()) "刷新定位" else "自动定位") { _, _ ->
                options = options.copy(manualPlace = "", automaticPlace = true, fields = options.fields + CameraField.PLACE)
                choices.save(options)
                if (location.permitted()) syncLocation(true) else requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_PERMISSION
                )
            }
            .setNeutralButton("手动填写") { _, _ -> enterPlace() }
            .setNegativeButton("隐藏地点") { _, _ ->
                saveOptions(options.copy(fields = options.fields - CameraField.PLACE), emptyMap())
            }.show()
    }

    private fun enterPlace() {
        val field = Ui.input(this, options.manualPlace, "例如：实训楼") {}.apply {
            tag = "camera_manual_location"
            filters = arrayOf(InputFilter.LengthFilter(72))
        }
        val body = Ui.column(this).apply {
            setPadding(px(20), px(4), px(20), px(4))
            addView(field)
        }
        AlertDialog.Builder(this).setTitle("手填地点").setView(body).setNegativeButton("取消", null)
            .setPositiveButton("完成") { _, _ ->
                val typed = cameraText(field.text.toString(), 72)
                saveOptions(options.copy(manualPlace = typed, automaticPlace = false,
                    fields = if (typed.isEmpty()) options.fields - CameraField.PLACE else options.fields + CameraField.PLACE), emptyMap())
            }.show()
    }

    private fun saveOptions(next: CameraSettings, updated: Map<FieldKey, String>): Boolean = try {
        val latest = local.profile()
        profile = if (updated.isEmpty()) latest else latest.copy(values = latest.values + updated).also(local::saveProfile)
        val restartLocation = next.automaticPlace != options.automaticPlace || next.manualPlace != options.manualPlace
        options = next
        choices.save(next)
        syncLocation(restartLocation)
        true
    } catch (_: Exception) {
        Ui.toast(this, "未能保存设置，请检查存储空间")
        false
    }

    private fun shoot() {
        if (!foreground || !hasCamera() || !state.ready || model.work.busy || model.review != null || shooting) return
        val stamp = options.stamp(ZonedDateTime.now(), profile, currentPlace())
        overlay.show(state.frame, stamp)
        shooting = true
        refresh()
        if (!driver.capture { receipt ->
            shooting = false
            receipt.fold({ jpeg -> renderPhoto(jpeg, stamp) }) { error ->
                if (foreground) Ui.toast(this, error.message ?: "未能拍摄，请重试")
                refresh()
            }
        }) {
            shooting = false
            refresh()
        }
    }

    private fun renderPhoto(jpeg: CameraJpeg, stamp: CameraStamp) {
        location.stop()
        val app = applicationContext
        val owner = model
        owner.work.submit("正在合成水印…", {
            try {
                val loader = PhotoLoader(app)
                val input = loader.loadFile(jpeg.file, jpeg.mirrored)
                input.preview.recycle()
                val drawing = if (stamp.isEmpty) null else CameraStampDrawing(CameraSize(input.width, input.height), stamp)
                val file = PhotoExporter(app).cameraToCache(input) { canvas -> drawing?.draw(canvas, stamp) }
                if (!owner.own(file)) throw IOException("预览已关闭")
                val actual = loader.loadFile(file)
                Review(file, actual.preview, actual.width, actual.height)
            } finally { jpeg.file.delete() }
        }, {
            it.fold({ value ->
                model.review = value
                model.saved = false
            }) { error ->
                model.discard()
                if (foreground) Ui.toast(this, if (error is OutOfMemoryError) "内存不足，未降低照片分辨率" else
                    (error.message ?: "合成失败，请重拍"))
                state = state.copy(ready = false, error = "照片未生成，可以重试")
            }
            refresh()
        })
    }

    private fun savePhoto() {
        val photo = model.review ?: return
        if (model.saved || model.work.busy || model.choosingFile) return
        if (Build.VERSION.SDK_INT >= 29) {
            val app = applicationContext
            model.work.submit("正在保存…", { PhotoExporter(app).saveToGallery(photo.file) }, { savedResult(it, true) })
        } else {
            try {
                model.choosingFile = true
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_TITLE, photo.file.name)
                }, SAVE_DOCUMENT)
            } catch (_: Exception) {
                model.choosingFile = false
                Ui.toast(this, "未能打开保存位置选择器")
            }
            refresh()
        }
    }

    private fun savedResult(receipt: Result<*>, gallery: Boolean) {
        receipt.fold({
            model.saved = true
            Ui.toast(this, if (gallery) "照片已保存到相册" else "照片已保存")
        }) { Ui.toast(this, "保存失败，照片仍在预览中，请重试") }
        refresh()
    }

    @Deprecated("Uses the platform document picker.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SAVE_DOCUMENT) return
        model.choosingFile = false
        val file = model.review?.file ?: model.workingFile()
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null && file != null && !model.saved) {
            val app = applicationContext
            model.work.submit("正在保存…", { PhotoExporter(app).copyToDocument(file, uri) },
                { savedResult(it, false) }, enqueue = true)
        }
        refresh()
    }

    private fun retake() {
        if (model.work.busy || model.choosingFile) return
        result.setImageDrawable(null)
        model.discard()
        refresh()
        beginPreview()
        syncLocation()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        displayManager.registerDisplayListener(displayListener, main)
        profile = local.profile()
        refresh()
        beginPreview()
        syncLocation()
        main.post(clock)
        if (!hasCamera() && model.review == null && !askedCamera) askCamera()
    }

    override fun onPause() {
        foreground = false
        main.removeCallbacks(clock)
        displayManager.unregisterDisplayListener(displayListener)
        driver.stop()
        state = state.copy(ready = false, lightOn = false)
        location.stop()
        refresh()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION -> { refresh(); beginPreview() }
            LOCATION_PERMISSION -> syncLocation()
        }
    }

    private fun restoreReview(state: Bundle?) {
        val path = state?.getString("review_path") ?: return
        val file = File(cacheDir, path)
        val parent = file.parentFile ?: return
        if (!file.isFile || !file.name.matches(Regex("水印相机_[0-9]{8}_[0-9]{6}_[0-9a-f]{6}\\.jpg")) ||
            !parent.name.matches(Regex("job_[0-9a-f-]{36}")) ||
            parent.canonicalFile.parentFile != File(cacheDir, "share").canonicalFile) return
        val app = applicationContext
        model.own(file)
        model.saved = state.getBoolean("review_saved")
        model.choosingFile = state.getBoolean("choosing_file")
        model.work.submit("正在恢复照片…", {
            val photo = PhotoLoader(app).loadFile(file)
            Review(file, photo.preview, photo.width, photo.height)
        }, {
            model.review = it.getOrNull()
            if (model.review == null) { model.discard(); model.choosingFile = false }
            refresh()
            if (model.review == null) main.post { beginPreview() }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("front", front)
        outState.putBoolean("asked_camera", askedCamera)
        outState.putBoolean("review_saved", model.saved)
        outState.putBoolean("choosing_file", model.choosingFile)
        model.review?.file?.let { outState.putString("review_path", "share/" + it.parentFile!!.name + "/" + it.name) }
        super.onSaveInstanceState(outState)
    }

    override fun onRetainNonConfigurationInstance(): Any = model

    @Deprecated("Uses the platform Activity back stack.")
    override fun onBackPressed() {
        if (model.work.busy || shooting || model.choosingFile) return
        if (model.review == null) finish()
        else if (model.saved) retake()
        else AlertDialog.Builder(this).setTitle("放弃这张照片？")
            .setNegativeButton("继续预览", null).setPositiveButton("放弃") { _, _ -> retake() }.show()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        driver.close()
        location.stop()
        model.work.detach(this)
        if (!isChangingConfigurations) model.close()
        super.onDestroy()
    }
}
