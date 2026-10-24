package cn.campuslife.assistant.map

import android.content.Context
import android.os.Build
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItemV2
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.poisearch.PoiResultV2
import com.amap.api.services.poisearch.PoiSearchV2
import com.amap.api.services.poisearch.VisualSearchResult
import com.amap.apis.utils.core.api.AMapUtilCoreApi

data class MapPlace(val id: String,val title: String,val address: String,val point: GeoPoint)
data class PlaceResult(val places: List<MapPlace>,val error: String? = null)

/** Called only after the map privacy screen has been accepted. */
class MapSdk(private val context: Context) {
    companion object {
        fun supportsDevice(): Boolean = Build.SUPPORTED_ABIS.any { it=="arm64-v8a" || it=="armeabi-v7a" }

        fun initialize(context: Context,key: String,consent: Boolean) {
            require(MapRules.canInitialize(consent,key)) { "Map configuration or privacy choice is missing." }
            MapsInitializer.updatePrivacyShow(context,true,true)
            MapsInitializer.updatePrivacyAgree(context,true)
            ServiceSettings.updatePrivacyShow(context,true,true)
            ServiceSettings.updatePrivacyAgree(context,true)
            MapsInitializer.setApiKey(key)
            MapsInitializer.setProtocol(MapsInitializer.HTTPS)
            MapsInitializer.setTileRequestLogLevel(MapsInitializer.TILE_REQUEST_LOG_OFF)
            MapsInitializer.setAlcLogConfig(0L,false)
            AMapUtilCoreApi.setCollectInfoEnable(false)
            AMapUtilCoreApi.setCollectIPEnable(false)
            ServiceSettings.getInstance().apply { setApiKey(key);setConnectionTimeOut(8000);setSoTimeOut(8000) }
        }

        fun errorMessage(code: Int): String = when(code) {
            10001,10002,10005,10006,10008,10009,10012,10013->"地图服务鉴权失败，请检查 Android Key、包名与安装包签名。"
            10003,10004,10010,10014,10019,10020,10021,10029->"地图服务额度或访问受限，请查看高德控制台；本工具不会自动购买额度。"
            1802,1804,1806->"地图服务暂时无法连接，请检查网络后重试。"
            else->"地图服务未能完成请求（代码 $code），请稍后重试。"
        }
    }

    fun findCampus(callback: (PlaceResult)->Unit) {
        val query=PoiSearchV2.Query(MapRules.CAMPUS_QUERY,"","平潭县").apply { pageSize=10;pageNum=1;cityLimit=true }
        execute(query,null) { result ->
            callback(if(result.error!=null) result else PlaceResult(result.places.filter {
                MapRules.isPingtanCampus(it.title,it.address)
            }))
        }
    }

    fun search(plan: SearchPlan,callback: (PlaceResult)->Unit) {
        val center=LatLonPoint(plan.center.latitude,plan.center.longitude)
        val query=PoiSearchV2.Query(plan.keyword,"","").apply { pageSize=10;pageNum=1;location=center;isDistanceSort=true }
        execute(query,PoiSearchV2.SearchBound(center,plan.radiusMeters,true),callback)
    }

    private fun execute(query: PoiSearchV2.Query,bound: PoiSearchV2.SearchBound?,callback: (PlaceResult)->Unit) {
        try {
            val search=PoiSearchV2(context.applicationContext,query)
            bound?.let { search.bound=it }
            search.setOnPoiSearchListener(object: PoiSearchV2.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResultV2?,code: Int) {
                    if(code!=1000) { callback(PlaceResult(emptyList(),errorMessage(code)));return }
                    val places=result?.pois.orEmpty().mapNotNull { poi ->
                        val coordinate=poi.latLonPoint ?: return@mapNotNull null
                        val point=runCatching { GeoPoint(coordinate.latitude,coordinate.longitude) }.getOrNull() ?: return@mapNotNull null
                        MapPlace(poi.poiId.orEmpty(),poi.title.orEmpty(),listOf(poi.adName.orEmpty(),poi.snippet.orEmpty()).filter { it.isNotBlank() }.distinct().joinToString(" "),point)
                    }.distinctBy { it.id.ifBlank { "${it.title}:${it.point}" } }
                    callback(PlaceResult(places))
                }
                override fun onPoiItemSearched(item: PoiItemV2?,code: Int) {}
                override fun onVisualSearched(result: VisualSearchResult?,code: Int) {}
            })
            search.searchPOIAsyn()
        } catch(error: AMapException) {
            callback(PlaceResult(emptyList(),errorMessage(error.errorCode)))
        } catch(_: Exception) {
            callback(PlaceResult(emptyList(),"地图服务初始化失败，请检查配置与网络。"))
        }
    }
}
