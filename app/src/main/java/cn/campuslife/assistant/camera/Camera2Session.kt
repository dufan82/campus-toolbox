package cn.campuslife.assistant.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import java.io.File
import java.io.IOException
import kotlin.math.abs

internal data class CameraState(
    val frame: CameraSize? = null,
    val ready: Boolean = false,
    val canSwitch: Boolean = false,
    val hasLight: Boolean = false,
    val lightOn: Boolean = false,
    val error: String? = null
)

internal data class CameraJpeg(val file: File, val mirrored: Boolean)

/** Owns a foreground-only Camera2 session. Preview and JPEG streams are distinct surfaces. */
internal class Camera2Session(
    context: Context, private val holder: SurfaceHolder, private val changed: (CameraState) -> Unit
) : SurfaceHolder.Callback {
    private data class Lens(
        val id: String, val front: Boolean, val sensor: Int, val jpeg: CameraSize,
        val preview: CameraSize, val torch: Boolean, val continuousFocus: Boolean, val noAutoCrop: Boolean
    )

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val rawFolder = File(context.cacheDir, "watermark-camera")
    private val main = Handler(Looper.getMainLooper())
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private var active = false
    private var openingTicket: Int? = null
    private var generation = 0
    private var lenses: List<Lens>? = null
    private var lens: Lens? = null
    private var displayDegrees = 0
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var inFlight: ((Result<CameraJpeg>) -> Unit)? = null
    private var ready = false
    private var torch = false
    private var status: String? = null
    private val shotTimeout = Runnable { fail("拍摄超时，请重试") }
    private val openingTimeout = Runnable { if (!ready) fail("相机启动超时，请重试") }

    init { holder.addCallback(this) }

    fun start(front: Boolean, rotation: Int) {
        if (active && status == null && lens?.front == front && rotation == displayDegrees) return
        stop()
        try {
            val available = lenses ?: manager.cameraIdList.mapNotNull(::inspect)
                .distinctBy { it.front }.also { lenses = it }
            val chosen = available.firstOrNull { it.front == front } ?: available.firstOrNull()
                ?: return fail("此设备没有可用相机")
            lens = chosen
            displayDegrees = rotation
            active = true
            status = null
            // The SurfaceView rotates the buffer; set its native buffer dimensions explicitly.
            holder.setFixedSize(chosen.preview.width, chosen.preview.height)
            publish()
            main.postDelayed(openingTimeout, 8_000)
            openIfReady()
        } catch (_: Exception) { fail("无法打开相机，请确认权限或关闭其他相机应用") }
    }

    fun light(): Boolean {
        val current = lens ?: return false
        if (!ready || !current.torch || inFlight != null) return false
        val old = torch
        torch = !old
        try { repeatPreview() } catch (_: Exception) {
            torch = old
            publish()
            return false
        }
        publish()
        return true
    }

    fun capture(receive: (Result<CameraJpeg>) -> Unit): Boolean {
        val current = lens ?: return false
        val camera = device ?: return false
        val captureSession = session ?: return false
        val jpegSurface = reader?.surface ?: return false
        if (!ready || inFlight != null) return false
        val ticket = generation
        inFlight = receive
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpegSurface)
                cameraSettings(current)
                set(CaptureRequest.JPEG_QUALITY, 96.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, jpegRotation(current.sensor, displayDegrees, current.front))
                // Intentionally no JPEG_GPS_LOCATION: location is optional visible text only.
            }.build()
            captureSession.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    if (ticket == generation && inFlight != null) fail("未能拍摄，请重试")
                }
            }, main)
            main.postDelayed(shotTimeout, 8_000)
        } catch (_: Exception) { fail("未能拍摄，请重试") }
        return true
    }

    fun stop() {
        active = false
        ready = false
        torch = false
        generation++
        main.removeCallbacks(shotTimeout)
        main.removeCallbacks(openingTimeout)
        session?.close()
        session = null
        device?.close()
        device = null
        val oldReader = reader
        reader = null
        if (oldReader != null) imageHandler?.post {
            oldReader.setOnImageAvailableListener(null, null)
            oldReader.close()
        }
        val pending = inFlight
        inFlight = null
        pending?.invoke(Result.failure(IOException("拍摄已中断")))
    }

    fun close() {
        stop()
        holder.removeCallback(this)
        imageThread?.quitSafely()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { openIfReady() }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        openIfReady()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!active) return
        val oldLens = lens ?: return
        stop()
        // The next valid buffer, e.g. after resizing, opens a fresh session.
        lens = oldLens
        active = true
        publish()
        main.postDelayed(openingTimeout, 8_000)
    }

    private fun inspect(id: String): Lens? {
        val c = manager.getCameraCharacteristics(id)
        val facing = c[CameraCharacteristics.LENS_FACING]
        if (facing != CameraCharacteristics.LENS_FACING_BACK && facing != CameraCharacteristics.LENS_FACING_FRONT) return null
        val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: return null
        val previews = map.getOutputSizes(SurfaceHolder::class.java).orEmpty()
            .map { CameraSize(it.width, it.height) }
        if (previews.isEmpty()) return null
        val jpegs = map.getOutputSizes(ImageFormat.JPEG).orEmpty().map { CameraSize(it.width, it.height) }
        // Some camera HALs support disjoint ratios; prefer the largest matched still stream.
        val jpeg = jpegs.filter { photo -> previews.any { abs(it.ratio / photo.ratio - 1) < .015 } }
            .maxByOrNull { it.pixels } ?: jpegs.maxByOrNull { it.pixels } ?: return null
        return Lens(id, facing == CameraCharacteristics.LENS_FACING_FRONT, c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0,
            jpeg, choosePreviewSize(jpeg, previews), c[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true,
            c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true,
            Build.VERSION.SDK_INT >= 31 && c[CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES]
                ?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true)
    }

    // The Activity starts only after checking the foreground CAMERA permission.
    @SuppressLint("MissingPermission")
    private fun openIfReady() {
        val current = lens ?: return
        if (!active || openingTicket != null || device != null || !holder.surface.isValid ||
            holder.surfaceFrame.width() != current.preview.width || holder.surfaceFrame.height() != current.preview.height) return
        val ticket = generation
        openingTicket = ticket
        try {
            manager.openCamera(current.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (openingTicket == ticket) openingTicket = null
                    if (ticket != generation || !active) {
                        camera.close()
                        openIfReady()
                        return
                    }
                    device = camera
                    configure(camera, current, ticket)
                }
                override fun onDisconnected(camera: CameraDevice) = stopped(camera, "相机已断开，请重试")
                override fun onError(camera: CameraDevice, error: Int) = stopped(camera, "相机不可用，请重试")
                private fun stopped(camera: CameraDevice, message: String) {
                    if (openingTicket == ticket) openingTicket = null
                    camera.close()
                    if (ticket == generation) fail(message) else openIfReady()
                }
            }, main)
        } catch (_: Exception) {
            if (openingTicket == ticket) openingTicket = null
            fail("无法启动相机，请检查相机权限")
        }
    }

    @Suppress("DEPRECATION")
    private fun configure(camera: CameraDevice, current: Lens, ticket: Int) {
        try {
            val preview = holder.surface
            val io = imageHandler ?: Handler(HandlerThread("WatermarkJpeg").apply {
                start()
                imageThread = this
            }.looper).also { imageHandler = it }
            val imageReader = ImageReader.newInstance(current.jpeg.width, current.jpeg.height, ImageFormat.JPEG, 2)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ source -> storeJpeg(source, current.front, ticket) }, io)
            camera.createCaptureSession(listOf(preview, imageReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(created: CameraCaptureSession) {
                    if (ticket != generation || !active) { created.close(); return }
                    session = created
                    try { repeatPreview() } catch (_: Exception) { fail("未能开始取景，请重试") }
                }
                override fun onConfigureFailed(created: CameraCaptureSession) {
                    created.close()
                    if (ticket == generation) fail("相机不支持当前画幅，请重试或从相册选择")
                }
            }, main)
        } catch (_: Exception) { fail("未能配置相机，请重试") }
    }

    private fun repeatPreview() {
        val current = checkNotNull(lens)
        val captureSession = checkNotNull(session)
        val ticket = generation
        val request = checkNotNull(device).createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(holder.surface)
            cameraSettings(current)
        }.build()
        val firstFrame = if (ready) null else object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (ticket != generation || ready || !active) return
                // Only wake the UI on the first frame, not for every preview frame.
                try { session.setRepeatingRequest(request, null, null) }
                catch (_: Exception) { fail("取景已中断，请重试"); return }
                ready = true
                main.removeCallbacks(openingTimeout)
                publish()
            }
        }
        captureSession.setRepeatingRequest(request, firstFrame, main)
    }

    private fun CaptureRequest.Builder.cameraSettings(current: Lens) {
        if (current.continuousFocus) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        if (current.torch) set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        if (Build.VERSION.SDK_INT >= 31 && current.noAutoCrop) {
            set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
        }
    }

    private fun storeJpeg(source: ImageReader, mirrored: Boolean, ticket: Int) {
        var file: File? = null
        val result = runCatching {
            val image = source.acquireNextImage() ?: return
            image.use {
                check(rawFolder.isDirectory || rawFolder.mkdirs()) { "无法创建相机缓存" }
                val target = File.createTempFile("camera_", ".jpg", rawFolder).also { file = it }
                target.outputStream().use { output ->
                    val buffer = it.planes[0].buffer
                    require(buffer.remaining() in 1..(64 * 1024 * 1024)) { "原照片为空或超过 64 MB" }
                    while (buffer.hasRemaining()) output.channel.write(buffer)
                }
                CameraJpeg(target, mirrored)
            }
        }
        if (result.isFailure) file?.delete()
        main.post {
            val deliver = inFlight
            if (ticket != generation || deliver == null) {
                result.getOrNull()?.file?.delete()
                return@post
            }
            inFlight = null
            stop()
            if (result.isFailure) {
                status = "未能读取相机照片，请重试"
                publish()
            }
            deliver(result)
        }
    }

    private fun fail(message: String) {
        status = message
        stop()
        publish()
    }

    private fun publish() {
        val frame = lens?.let { it.jpeg.upright(jpegRotation(it.sensor, displayDegrees, it.front)) }
        changed(CameraState(frame, ready, (lenses?.size ?: 0) > 1, lens?.torch == true, torch, status))
    }
}

internal fun cameraDisplayDegrees(rotation: Int) = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}
