package cn.campuslife.assistant.map

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.content.ContextCompat
import cn.campuslife.assistant.BuildConfig
import cn.campuslife.assistant.ui.Ui
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.*

class CampusMapActivity: Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private val gate=RequestGate()
    private lateinit var preferences: MapPreferences
    private var screen: MapScreen?=null
    private var mapView: MapView?=null
    private var map: AMap?=null
    private var sdk: MapSdk?=null
    private var locationSource: DeviceLocationSource?=null
    private var foreground=false
    private var wantsLocation=false
    private var centerOnNextFix=false
    private var fix: LocatedFix?=null
    private var campus: MapPlace?=null
    private var places=emptyList<MapPlace>()
    private var selected: MapPlace?=null
    private var scope=SearchScope.CAMPUS
    private var category=MapCategory.ALL
    private var queryText=""
    private var mapShown=false
    private var resultOrigin: GeoPoint?=null
    private var resultOriginLabel="校区中心"
    private var markerLayer: CampusMarkerLayer?=null
    private var mapLoaded=false
    private var coordinateData: CampusCoordinateData?=null
    private var coordinateError=false
    private var campusMatches=emptyList<LocatedLandmark>()
    private var maintenance: CampusMapMaintenance?=null
    private var referenceDialog: CampusReferenceDialog?=null
    private var locationStyle: MyLocationStyle?=null
    private var pendingPlaceId: String?=null
    private var pendingPlaceReason=VisitReason.USER_SELECTION
    private val staleFixCheck=object: Runnable {
        override fun run() {
            if(!foreground) return
            if(fix!=null && fix?.isFresh(SystemClock.elapsedRealtime())!=true) {
                fix=null
                locationStyle?.showMyLocation(false)?.let { map?.myLocationStyle=it }
                screen?.locationMessage("定位暂未更新，可继续浏览校园地图。")
            }
            handler.postDelayed(this,10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences=MapPreferences(this)
        pendingPlaceId=if(savedInstanceState!=null) savedInstanceState.getString("pending_place_id") else intent.getStringExtra("campus_place_id")
        pendingPlaceReason=if(savedInstanceState!=null) VisitReason.entries.find { it.name==savedInstanceState.getString("pending_place_reason") } ?: VisitReason.RESTORE else VisitReason.USER_SELECTION
        try { coordinateData=CampusCoordinateStore(this).load() }
        catch(_: Exception) { coordinateError=true }
        savedInstanceState?.let { saved ->
            queryText=saved.getString("map_query","")
            wantsLocation=saved.getBoolean("map_wants_location",false)
        }
        if(preferences.hasConsent()) openMap(savedInstanceState) else privacyPage()
        if(savedInstanceState?.getBoolean("map_reference_open")==true) showReference(savedInstanceState.getString("map_reference_selected"))
    }

    private fun privacyPage() {
        val root=Ui.page(this)
        root.addView(Ui.header(this,"校园地图"))
        val content=Ui.column(this).apply { tag="map_privacy";setPadding(Ui.dp(context,20),Ui.dp(context,8),Ui.dp(context,20),Ui.dp(context,24)) }
        val card=Ui.card(this)
        card.addView(Ui.text(this,"找到校园，也找到自己",23f,bold=true));card.addView(Ui.space(this,16))
        card.addView(Ui.text(this,"地图需要联网，定位由你决定",16f,bold=true));card.addView(Ui.space(this,10))
        card.addView(Ui.text(this,"底图由高德开放平台提供（北京高德图强科技有限公司），运行必要的设备/网络信息由高德处理。校园地点名称在本机搜索，不向高德提交搜索词；启用定位后，高德还会处理位置信息。",14f,Ui.muted))
        card.addView(Ui.space(this,12));card.addView(Ui.text(this,"只有点击“我的位置”后才申请系统定位权限，仅在地图页面前台使用，不记录行走轨迹。照片、学号和水印资料不会交给地图服务。拒绝不影响快递和水印工具。",14f,Ui.muted))
        card.addView(Ui.space(this,12));card.addView(Ui.text(this,"本版本为短期少量测试。校园楼栋和道路以现场为准，定位可能有误差，不提供室内楼层导航。",13f,Ui.muted))
        card.addView(Ui.space(this,12));card.addView(Ui.button(this,"查看高德隐私政策") { openPolicy("https://lbs.amap.com/pages/privacy/") })
        card.addView(Ui.space(this,8));card.addView(Ui.button(this,"查看高德服务协议") { openPolicy("https://lbs.amap.com/home/terms/") })
        card.addView(Ui.space(this,16));card.addView(Ui.button(this,"同意并使用地图",true) {
            if(preferences.setConsent(true)) openMap(null) else {
                preferences.setConsent(false)
                Ui.toast(this,"隐私选择未能保存，请检查设备存储后重试。")
            }
        }.apply { tag="map_accept" },LinearLayout.LayoutParams(-1,-2))
        card.addView(Ui.space(this,8));card.addView(Ui.button(this,"暂不使用地图") { finish() }.apply { tag="map_decline" },LinearLayout.LayoutParams(-1,-2))
        content.addView(card);root.addView(Ui.scroll(this,content),LinearLayout.LayoutParams(-1,0,1f))
    }

    private fun unavailable(message: String) {
        val root=Ui.page(this);root.addView(Ui.header(this,"校园地图"))
        val body=Ui.column(this).apply { gravity=Gravity.CENTER;setPadding(Ui.dp(context,24),Ui.dp(context,24),Ui.dp(context,24),Ui.dp(context,24)) }
        body.addView(Ui.text(this,"地图暂不可用",23f,bold=true));body.addView(Ui.space(this,16));body.addView(Ui.text(this,message,15f,Ui.muted).apply { tag="map_unavailable" })
        body.addView(Ui.space(this,24));body.addView(Ui.button(this,"返回工具箱",true) { finish() })
        body.addView(Ui.space(this,8));body.addView(Ui.button(this,"查看校园原图") { showReference(null) })
        body.addView(Ui.space(this,8));body.addView(Ui.button(this,"撤回地图同意") { withdrawConsent() })
        root.addView(Ui.scroll(this,body),LinearLayout.LayoutParams(-1,0,1f))
    }

    private fun openMap(saved: Bundle?) {
        if(!MapRules.canInitialize(preferences.hasConsent(),BuildConfig.AMAP_ANDROID_KEY)) {
            unavailable("此安装包尚未配置 Android 地图 Key。开发者需在本机 local.properties 配置后重新构建。水印和快递功能仍可使用。");return
        }
        if(!MapSdk.supportsDevice()) {
            unavailable("当前高德 SDK 仅支持 ARM 手机，此设备为不支持的处理器或模拟器。请在 Android 8.0+ 的 ARM 手机体验；这里不显示虚假的定位图。");return
        }
        try {
            MapSdk.initialize(applicationContext,BuildConfig.AMAP_ANDROID_KEY,preferences.hasConsent())
            sdk=MapSdk(applicationContext)
            val views=MapScreen(this);screen=views
            views.query.setText(queryText)
            views.onSearch={ search() }
            views.onLocate={ locate() };views.onCampus={
                queryText="";views.query.setText("")
                if(campus==null) lookupCampus() else showCampus()
            }
            views.onPrivacy={ privacyOptions() };views.onChoose={ selectPlace(it) };views.onNavigate={ navigate(it) }
            views.onReference={ showReference(maintenance?.target?.id ?: selected?.id) }
            val surface=MapView(this).apply { tag="map_surface";visibility=View.INVISIBLE }
            mapView=surface;views.viewport.addView(surface,FrameLayout.LayoutParams(-1,-1));surface.onCreate(saved)
            map=surface.map.apply {
                uiSettings.apply { isZoomControlsEnabled=false;isMyLocationButtonEnabled=false;isScaleControlsEnabled=true;isTiltGesturesEnabled=false;isRotateGesturesEnabled=false;isIndoorSwitchEnabled=false }
                showBuildings(false)
                setOnMapLoadedListener { mapLoaded=true;applyMapTextMode();renderMarkers(places) }
                setOnCameraChangeListener(object: AMap.OnCameraChangeListener {
                    override fun onCameraChange(position: CameraPosition) {}
                    override fun onCameraChangeFinish(position: CameraPosition) { runOnUiThread {
                        markerLayer?.layout(screen?.viewport?.width ?: 0,screen?.viewport?.height ?: 0)
                    } }
                })
                setOnMarkerClickListener { marker -> (marker.`object` as? MapPlace)?.let { selectPlace(it) };true }
                setOnPOIClickListener { poi ->
                    if(scope!=SearchScope.NEARBY) return@setOnPOIClickListener
                    val point=runCatching { GeoPoint(poi.coordinate.latitude,poi.coordinate.longitude) }.getOrNull()
                    if(point!=null) selectPlace(MapPlace(poi.poiId.orEmpty(),poi.name.orEmpty(),"地图地点",point))
                }
                setOnMapLongClickListener { point -> maintenance?.review(GeoPoint(point.latitude,point.longitude)) }
            }
            if(BuildConfig.LOCAL_MAP_EDITING) coordinateData?.let { current ->
                maintenance=CampusMapMaintenance(this,CampusCoordinateStore(this),current,{ updated ->
                    coordinateData=updated;searchCampus()
                },{ _ -> maintenanceTargetChanged() },{ id -> startReview(id) }).also { it.restoreState(saved) }
                views.onMaintenance={ maintenance?.menu() }
            }
            views.addMapControls()
            locationStyle=MyLocationStyle().myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
                .radiusFillColor(0x225995ec).strokeColor(0x665995ec).strokeWidth(1f)
            map?.myLocationStyle=locationStyle
            locationSource=DeviceLocationSource(applicationContext,{ newFix ->
                if(foreground) {
                    fix=newFix
                    locationStyle?.showMyLocation(true)?.let { map?.myLocationStyle=it }
                    views.locationMessage(if(newFix.accuracyMeters>100) "大致位置 · 误差约 ${newFix.accuracyMeters.toInt()} 米" else "已定位 · 误差约 ${newFix.accuracyMeters.toInt()} 米")
                    if(centerOnNextFix) { centerOnNextFix=false;showPoint(newFix.point,17f) }
                }
            },{ message -> if(foreground) views.locationMessage(message) })
            map?.setLocationSource(locationSource)
            if(foreground) surface.onResume()
            coordinateData?.let { data ->
                campus=MapPlace("campus-pingtan",MapRules.CAMPUS_QUERY,"麒麟大道西段1号",data.center)
                showPoint(data.center,16.4f)
                searchCampus()
                pendingPlaceId?.let { id -> pendingPlaceId=null;openPlaceId(id,pendingPlaceReason) }
            } ?: run { searchCampus();lookupCampus() }
        } catch(_: Exception) { unavailable("地图未能启动，请检查网络和地图配置后重新进入。");disposeMap() }
          catch(_: LinkageError) { unavailable("地图原生库未能加载，此设备暂不兼容。其他工具仍可使用。");disposeMap() }
    }

    private fun lookupCampus() {
        val views=screen ?: return
        val service=sdk ?: return
        val ticket=gate.begin();views.busy(true,"正在查找平潭校区…")
        timeout(ticket)
        service.findCampus { result -> runOnUiThread {
            if(!canDeliver(ticket) || !gate.complete(ticket)) return@runOnUiThread
            views.busy(false,result.error ?: if(result.places.isEmpty()) "未找到平潭校区，点击“查看全校”重试；也可先定位。" else "校园地点可搜索；楼栋与道路以现场为准")
            campus=result.places.firstOrNull()
            campus?.let { showCampus() }
        } }
    }

    private fun showCampus() {
        val school=campus ?: return
        showPoint(school.point,16.4f)
        searchCampus()
    }

    private fun search() {
        val views=screen ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(views.query.windowToken,0)
        views.query.clearFocus()
        gate.invalidate()
        searchCampus()
    }

    private fun searchCampus() {
        val views=screen ?: return
        gate.invalidate()
        queryText=views.query.text.toString()
        val names=coordinateData?.search(queryText,category) ?: CampusCatalog.search(queryText,category)
        campusMatches=CampusCatalog.located(names,coordinateData?.points.orEmpty())
        places=campusMatches.mapNotNull { item -> item.point?.let { MapPlace(item.landmark.id,item.landmark.name,item.landmark.category.label,it) } }
        selected=if(queryText.isNotBlank() && campusMatches.size==1 && maintenance?.target==null) places.singleOrNull() else null
        resultOrigin=fix?.takeIf { it.isFresh(SystemClock.elapsedRealtime()) }?.point ?: campus?.point
        resultOriginLabel=if(fix?.isFresh(SystemClock.elapsedRealtime())==true) "我的位置" else "校区中心"
        val reviewTarget=maintenance?.target
        views.busy(false,when {
            coordinateError->"坐标表读取失败，仍可搜名称；暂不可定位。"
            reviewTarget!=null->"正在核对 ${reviewTarget.name}：请在地图上长按真实位置"
            queryText.isNotBlank() && campusMatches.isEmpty()->"没有匹配的校园地点，试试楼名或别名。"
            queryText.isNotBlank() && campusMatches.size==1 && places.isEmpty()->"位置待核对，暂不可导航。"
            else->""
        })
        applyMapTextMode();renderMarkers(places)
        views.campusResults(campusMatches,selected,resultOrigin,resultOriginLabel,maintenance?.target==null)
        selected?.let { showPoint(it.point,17.5f) }
    }

    private fun applyMapTextMode() {
        if(mapLoaded) map?.showMapText(maintenance?.target!=null)
    }

    private fun maintenanceTargetChanged() { searchCampus() }



    private fun timeout(ticket: Long) {
        handler.postDelayed({ if(canDeliver(ticket)) { gate.invalidate();screen?.busy(false,"查询超时，请检查网络后重试；不会自动重复请求。") } },12_000)
    }

    private fun canDeliver(ticket: Long): Boolean = !isFinishing && !isDestroyed && gate.accepts(ticket)

    private fun renderMarkers(items: List<MapPlace>) {
        val surface=map ?: return
        val layer=markerLayer ?: CampusMarkerLayer(this).also { markerLayer=it }
        layer.render(surface,items,scope,selected?.id,screen?.viewport?.width ?: 0,screen?.viewport?.height ?: 0)
    }

    private fun selectPlace(place: MapPlace,reason: VisitReason=VisitReason.USER_SELECTION) {
        selected=place;showPoint(place.point,17.5f)
        markerLayer?.select(place.id,screen?.viewport?.width ?: 0,screen?.viewport?.height ?: 0)
        val origin=resultOrigin ?: fix?.takeIf { it.isFresh(SystemClock.elapsedRealtime()) }?.point ?: campus?.point
        val label=if(resultOrigin!=null) resultOriginLabel else if(fix?.isFresh(SystemClock.elapsedRealtime())==true) "我的位置" else "校区中心"
        screen?.campusResults(campusMatches,place,origin,label)
    }

    private fun openPlaceId(id: String,reason: VisitReason=VisitReason.USER_SELECTION) {
        val data=coordinateData
        if(data==null || PlaceHistory.availability(id,data)!=PlaceAvailability.AVAILABLE) { Ui.toast(this,"地点已隐藏、删除或尚无坐标，暂不可定位");return }
        val item=data.visiblePlaces.single { it.id==id }
        screen?.query?.setText("");queryText="";searchCampus()
        selectPlace(MapPlace(id,item.name,item.category.label,data.points.getValue(id)),reason)
    }

    private fun showPoint(point: GeoPoint,zoom: Float) {
        mapShown=true;mapView?.visibility=View.VISIBLE
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude,point.longitude),zoom))
    }

    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED

    private fun locate() {
        wantsLocation=true;centerOnNextFix=true
        screen?.locationMessage("正在获取位置…")
        if(hasLocationPermission()) enableLocation() else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),403)
    }

    private fun enableLocation() {
        if(!foreground || !hasLocationPermission()) return
        map?.isMyLocationEnabled=false
        map?.isMyLocationEnabled=true
    }

    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==403) {
            if(hasLocationPermission()) enableLocation() else {
                wantsLocation=false;centerOnNextFix=false;fix=null
                screen?.locationMessage("未授予定位，仍可搜索和浏览校园地点。")
            }
        }
    }

    private fun navigate(place: MapPlace) {
        val uri=Uri.Builder().scheme("amapuri").authority("route").appendPath("plan")
            .appendQueryParameter("sourceApplication","CampusToolbox").appendQueryParameter("dlat",place.point.latitude.toString())
            .appendQueryParameter("dlon",place.point.longitude.toString()).appendQueryParameter("dname",place.title)
            .appendQueryParameter("dev","0").appendQueryParameter("t","2").build()
        try { startActivity(Intent(Intent.ACTION_VIEW,uri).setPackage("com.autonavi.minimap")) }
        catch(_: Exception) { Ui.toast(this,"未能打开高德地图。请先安装官方 App，或继续在此查看地点。") }
    }

    private fun privacyOptions() {
        AlertDialog.Builder(this).setTitle("地图与隐私").setMessage("校园名称在本机搜索，缺失坐标不提供导航。底图和可选前台定位由高德提供，不与照片或校园身份资料关联。自定义地点只保存在本机。")
            .setPositiveButton("知道了",null).setNeutralButton("隐私政策") { _,_->openPolicy("https://lbs.amap.com/pages/privacy/") }
            .setNegativeButton("撤回地图同意") { _,_->withdrawConsent() }.show()
    }

    private fun showReference(id: String?) {
        referenceDialog?.dismiss()
        val dialog=CampusReferenceDialog(this,id,BuildConfig.LOCAL_MAP_EDITING && maintenance!=null,
            coordinateData?.visiblePlaces ?: CampusCatalog.entries) { selectedId -> startReview(selectedId) }
        referenceDialog=dialog
        dialog.setOnDismissListener { if(referenceDialog===dialog) referenceDialog=null }
        dialog.show()
    }

    private fun startReview(id: String) {
        val item=(coordinateData?.catalog ?: CampusCatalog.entries).firstOrNull { it.id==id } ?: return
        maintenance?.select(id);screen?.query?.setText(item.name)
        searchCampus()
        val point=coordinateData?.points?.get(id)
        if(point!=null) showPoint(point,17.5f) else campus?.let { showPoint(it.point,16.4f) }
    }

    private fun withdrawConsent() {
        wantsLocation=false;gate.invalidate()
        val providerWasEnabled=preferences.hasConsent() && MapSdk.supportsDevice() && MapRules.canInitialize(true,BuildConfig.AMAP_ANDROID_KEY)
        val saved=MapLifecycle.withdraw({ preferences.setConsent(false) },
            { disposeMap() },
            { if(providerWasEnabled) com.amap.api.maps.MapsInitializer.updatePrivacyAgree(applicationContext,false) },
            { if(providerWasEnabled) com.amap.api.services.core.ServiceSettings.updatePrivacyAgree(applicationContext,false) },
            { finish() }
        )
        if(!saved) Ui.toast(this,"地图已停止，但撤回选择未能保存；请检查存储后重试。")
    }

    private fun openPolicy(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
        catch(_: Exception) { Ui.toast(this,"未找到可用浏览器，请在高德官网查看对应政策。") }
    }



    override fun onResume() {
        super.onResume();foreground=true;mapView?.onResume()
        if(wantsLocation && hasLocationPermission()) enableLocation()
        handler.removeCallbacks(staleFixCheck);handler.postDelayed(staleFixCheck,10_000)
    }

    override fun onPause() {
        foreground=false;gate.invalidate();screen?.busy(false,"")
        MapLifecycle.release({ map?.isMyLocationEnabled=false },{ locationSource?.deactivate() },{ mapView?.onPause() })
        handler.removeCallbacksAndMessages(null);super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_place_id",pendingPlaceId ?: selected?.id)
        outState.putString("pending_place_reason",if(pendingPlaceId!=null) pendingPlaceReason.name else VisitReason.RESTORE.name)
        outState.putString("map_query",screen?.query?.text?.toString() ?: queryText)
        outState.putInt("map_scope",scope.ordinal);outState.putInt("map_category",category.ordinal)
        outState.putBoolean("map_wants_location",wantsLocation)
        maintenance?.saveState(outState)
        outState.putBoolean("map_reference_open",referenceDialog?.isShowing==true)
        outState.putString("map_reference_selected",referenceDialog?.selectedId)
        mapView?.onSaveInstanceState(outState);super.onSaveInstanceState(outState)
    }

    private fun disposeMap() {
        gate.invalidate();handler.removeCallbacksAndMessages(null)
        val previousMap=map;val previousSource=locationSource;val previousView=mapView
        maintenance?.close();markerLayer?.close();markerLayer=null
        // Drop references before provider calls, so partial failure/re-entry is safe.
        map=null;mapView=null;locationSource=null;sdk=null;screen=null;fix=null;locationStyle=null;mapShown=false;mapLoaded=false;maintenance=null
        MapLifecycle.release({ previousMap?.isMyLocationEnabled=false },{ previousSource?.deactivate() },{ previousView?.onDestroy() })
    }

    override fun onDestroy() { referenceDialog?.dismiss();referenceDialog=null;disposeMap();super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory();mapView?.onLowMemory() }
}
