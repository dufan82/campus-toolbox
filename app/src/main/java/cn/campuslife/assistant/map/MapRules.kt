package cn.campuslife.assistant.map

import java.util.Locale
import kotlin.math.*

data class GeoPoint(val latitude: Double,val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

data class LocatedFix(val point: GeoPoint,val accuracyMeters: Float,val elapsedMillis: Long) {
    fun isFresh(nowMillis: Long): Boolean = nowMillis>=elapsedMillis && nowMillis-elapsedMillis<=60_000 &&
        accuracyMeters.isFinite() && accuracyMeters>0 && accuracyMeters<=5_000
}

enum class SearchScope { CAMPUS,NEARBY }
enum class SearchOrigin { CAMPUS,LOCATION,MAP_CENTER }
enum class MapCategory(val label: String,val keyword: String) {
    ALL("全部",""),FOOD("食堂","食堂"),STUDY("教学","教学楼"),DORM("宿舍","宿舍"),
    EXPRESS("快递","快递驿站"),SPORT("运动","体育场"),SHOP("超市","超市"),TRANSIT("公交","公交站"),SERVICE("服务","服务中心")
}
data class SearchPlan(val keyword: String,val center: GeoPoint,val radiusMeters: Int,val origin: SearchOrigin)

/** Pure rules: no SDK, Android, user profile or network dependency. */
object MapRules {
    const val SCHOOL_NAME = "福建信息职业技术学院"
    const val CAMPUS_QUERY = "福建信息职业技术学院平潭校区"
    private val whitespace = Regex("\\s+")
    private val keyPattern = Regex("[a-fA-F0-9]{32}")

    fun normalize(raw: String): String {
        val text=raw.trim().replace(whitespace," ").take(80)
        Regex("(?:生|生公|学生公寓|)([1-9]|10)(?:号宿舍|号楼|号学生公寓|号公寓|号宿舍楼)").matchEntire(text)?.let {
            return "学生公寓${it.groupValues[1]}"
        }
        Regex("生([1-9]|10)").matchEntire(text)?.let { return "学生公寓${it.groupValues[1]}" }
        return when(text) {
            "实训一南"->"实训1南"
            "实训一北"->"实训1北"
            "一食堂","一餐"->"第一食堂"
            "二食堂","二餐"->"第二食堂"
            else->text
        }
    }

    fun plan(scope: SearchScope,text: String,category: MapCategory,campus: GeoPoint?,fix: LocatedFix?,viewport: GeoPoint?,nowMillis: Long): SearchPlan? {
        val keyword=normalize(text).ifEmpty {
            if(scope==SearchScope.NEARBY && category==MapCategory.FOOD) "餐饮" else category.keyword
        }
        if(keyword.isEmpty()) return null
        return when(scope) {
            SearchScope.CAMPUS->campus?.let { SearchPlan(keyword,it,1500,SearchOrigin.CAMPUS) }
            SearchScope.NEARBY->when {
                fix?.isFresh(nowMillis)==true->SearchPlan(keyword,fix.point,3000,SearchOrigin.LOCATION)
                viewport!=null->SearchPlan(keyword,viewport,3000,SearchOrigin.MAP_CENTER)
                else->null
            }
        }
    }

    fun isPingtanCampus(title: String,address: String): Boolean {
        val cleaned=title.replace(" ","").replace('（','(').replace('）',')')
        return (cleaned==SCHOOL_NAME || cleaned=="$SCHOOL_NAME(平潭校区)" || cleaned==CAMPUS_QUERY) &&
            (cleaned.contains("平潭") || address.contains("平潭"))
    }

    fun canInitialize(consent: Boolean,key: String): Boolean = consent && keyPattern.matches(key)

    fun distanceMeters(a: GeoPoint,b: GeoPoint): Double {
        val lat1=Math.toRadians(a.latitude);val lat2=Math.toRadians(b.latitude)
        val dLat=lat2-lat1;val dLon=Math.toRadians(b.longitude-a.longitude)
        val h=(sin(dLat/2).pow(2)+cos(lat1)*cos(lat2)*sin(dLon/2).pow(2)).coerceIn(0.0,1.0)
        return 6_371_000*2*atan2(sqrt(h),sqrt(1-h))
    }

    fun distanceLabel(meters: Double): String = if(meters<1000) "直线约 ${meters.roundToInt()} 米"
        else String.format(Locale.CHINA,"直线约 %.1f 公里",meters/1000)
}

/** One generation per intent: a slow response cannot replace a newer query. */
class RequestGate {
    private var generation=0L
    fun begin(): Long = ++generation
    fun accepts(ticket: Long): Boolean = ticket==generation
    fun complete(ticket: Long): Boolean {
        if(!accepts(ticket)) return false
        generation++
        return true
    }
    fun invalidate() { generation++ }
}
