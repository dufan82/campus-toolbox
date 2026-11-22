package cn.campuslife.assistant.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cn.campuslife.assistant.BuildConfig
import cn.campuslife.assistant.map.CampusCoordinateStore
import cn.campuslife.assistant.map.DeviceLocationSource
import cn.campuslife.assistant.map.LocatedFix
import cn.campuslife.assistant.map.MapPreferences
import cn.campuslife.assistant.map.MapRules
import cn.campuslife.assistant.map.MapSdk

/** Reuses the map's foreground Android providers and its local coordinate conversion only. */
internal class CameraLocation(context: Context, private val changed: () -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mapPreferences = MapPreferences(app)
    private val campus by lazy { runCatching { CampusCoordinateStore(app).load() }.getOrNull() }
    private var mapPrepared = false
    private var fix: LocatedFix? = null
    private var label: String? = null
    private var running = false
    var message = ""
        private set
    private val timeout = Runnable {
        if (running && current() == null) { message = "暂时无法定位"; changed() }
    }
    private val source = DeviceLocationSource(app, ::receive, {
        if (running && current() == null) { message = "定位不可用"; changed() }
    }, minDistanceMeters = 0f, transform = { it })

    fun permitted() = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun current(): String? = label?.takeIf { permitted() && fix?.isFresh(SystemClock.elapsedRealtime()) == true }

    fun start(refresh: Boolean = false) {
        if (!permitted() || (running && !refresh)) return
        stop()
        running = true
        message = "正在定位…"
        main.postDelayed(timeout, 12_000)
        source.activate(null)
        changed()
    }

    fun stop() {
        running = false
        main.removeCallbacks(timeout)
        source.deactivate()
        fix = null
        label = null
        message = ""
    }

    private fun receive(value: LocatedFix) {
        if (!running || !permitted()) return
        val consent = mapPreferences.hasConsent()
        val canUseMap = consent && MapSdk.supportsDevice() && MapRules.canInitialize(consent, BuildConfig.AMAP_ANDROID_KEY)
        val now = SystemClock.elapsedRealtime()
        val previous = fix
        if (previous != null && now-previous.elapsedMillis < 20_000 && previous.accuracyMeters < value.accuracyMeters / 2) return
        val converted = if (canUseMap && value.accuracyMeters <= 60f) {
            if (!mapPrepared) mapPrepared = runCatching {
                MapSdk.initialize(app, BuildConfig.AMAP_ANDROID_KEY, consent)
            }.isSuccess
            if (mapPrepared) DeviceLocationSource.toMapPoint(app, value.point) else null
        } else null
        label = cameraLocationLabel(value, converted, if (converted == null) null else campus, now)
        fix = value
        message = ""
        main.removeCallbacks(timeout)
        changed()
    }
}
