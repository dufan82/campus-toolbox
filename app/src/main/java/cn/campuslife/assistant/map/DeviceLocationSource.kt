package cn.campuslife.assistant.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.LocationSource
import com.amap.api.maps.model.LatLng

/** Foreground only. Android fixes are WGS84; render/search use SDK GCJ02 coordinates. */
class DeviceLocationSource(context: Context,private val changed: (LocatedFix)->Unit,private val status: (String)->Unit): LocationSource,LocationListener {
    private val appContext=context.applicationContext
    private val manager=appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var consumer: LocationSource.OnLocationChangedListener?=null
    private var active=false

    override fun activate(listener: LocationSource.OnLocationChangedListener?) {
        deactivate()
        consumer=listener
        val fine=ContextCompat.checkSelfPermission(appContext,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val coarse=ContextCompat.checkSelfPermission(appContext,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!fine && !coarse) { status("尚未授权定位，仍可按地图中心搜索。");return }
        active=true
        var registered=false
        val providers=if(fine) listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER) else listOf(LocationManager.NETWORK_PROVIDER)
        providers.forEach { provider ->
            try {
                if(manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider,5000L,3f,this,Looper.getMainLooper())
                    registered=true
                    manager.getLastKnownLocation(provider)?.let { publish(it) }
                }
            } catch(_: SecurityException) { status("定位权限已变化，请重新点击我的位置。") }
              catch(_: IllegalArgumentException) { /* Provider is not available on every device. */ }
        }
        if(!registered) status("设备定位未开启或不可用，请开启定位后重试。")
    }

    override fun deactivate() {
        active=false
        consumer=null
        MapLifecycle.release({ manager.removeUpdates(this) })
    }

    override fun onLocationChanged(location: Location) { publish(location) }
    override fun onProviderDisabled(provider: String) { if(active) status("定位服务已关闭，可继续浏览地图。") }
    override fun onProviderEnabled(provider: String) {}
    @Deprecated("Legacy device callback") override fun onStatusChanged(provider: String?,value: Int,extras: Bundle?) {}

    private fun publish(location: Location) {
        if(!active || !location.hasAccuracy()) return
        val elapsed=location.elapsedRealtimeNanos/1_000_000
        val original=runCatching { GeoPoint(location.latitude,location.longitude) }.getOrNull() ?: return
        if(!LocatedFix(original,location.accuracy,elapsed).isFresh(SystemClock.elapsedRealtime())) return
        val converted=CoordinateConverter(appContext).from(CoordinateConverter.CoordType.GPS)
            .coord(LatLng(original.latitude,original.longitude)).convert()
        val point=runCatching { GeoPoint(converted.latitude,converted.longitude) }.getOrNull() ?: return
        val rendered=Location(location).apply { latitude=point.latitude;longitude=point.longitude }
        consumer?.onLocationChanged(rendered)
        changed(LocatedFix(point,location.accuracy,elapsed))
    }
}
