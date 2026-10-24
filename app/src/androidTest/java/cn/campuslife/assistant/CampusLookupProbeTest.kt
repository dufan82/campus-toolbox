package cn.campuslife.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.map.*
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.core.PoiItemV2
import com.amap.api.services.poisearch.PoiSearchV2
import com.amap.api.services.poisearch.PoiResultV2
import com.amap.api.services.poisearch.VisualSearchResult
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, opt-in, low-volume developer probe. Never runs in normal regression/CI. */
@RunWith(AndroidJUnit4::class)
class CampusLookupProbeTest {
    @Test fun collectPublicCampusCandidatesOnlyWhenExplicitlyRequested() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("collectCampusCandidates")=="true")
        assumeTrue(BuildConfig.AMAP_ANDROID_KEY.isNotBlank())
        val context=instrumentation.targetContext
        instrumentation.runOnMainSync {
            ServiceSettings.updatePrivacyShow(context,true,true)
            ServiceSettings.updatePrivacyAgree(context,true)
            ServiceSettings.getInstance().apply { setApiKey(BuildConfig.AMAP_ANDROID_KEY);setConnectionTimeOut(8000);setSoTimeOut(8000) }
        }
        fun request(action: ((PlaceResult)->Unit)->Unit): PlaceResult {
            val latch=CountDownLatch(1)
            var response: PlaceResult?=null
            instrumentation.runOnMainSync { action { response=it;latch.countDown() } }
            assertTrue("Public campus lookup timed out",latch.await(20,TimeUnit.SECONDS))
            return requireNotNull(response)
        }
        fun json(result: PlaceResult)=JSONObject().put("error",result.error ?: JSONObject.NULL).put("places",JSONArray().apply {
            result.places.forEach { place -> put(JSONObject().put("id",place.id).put("name",place.title).put("address",place.address)
                .put("latitude",place.point.latitude).put("longitude",place.point.longitude)) }
        })
        val service=MapSdk(context)
        val campus=request { service.findCampus(it) }
        val output=JSONObject().put("school",json(campus))
        if(InstrumentationRegistry.getArguments().getString("collectCampusChildren")=="true") {
            val latch=CountDownLatch(1)
            instrumentation.runOnMainSync {
                val query=PoiSearchV2.Query(MapRules.CAMPUS_QUERY,"","平潭县").apply {
                    cityLimit=true;pageSize=10
                    showFields=PoiSearchV2.ShowFields(PoiSearchV2.ShowFields.CHILDREN)
                }
                PoiSearchV2(context,query).apply {
                    setOnPoiSearchListener(object: PoiSearchV2.OnPoiSearchListener {
                        override fun onPoiSearched(result: PoiResultV2?,code: Int) {
                            val children=JSONArray()
                            result?.pois.orEmpty().filter { MapRules.isPingtanCampus(it.title.orEmpty(),it.adName.orEmpty()) }.forEach { parent ->
                                parent.subPois.orEmpty().forEach { child ->
                                    val point=child.latLonPoint
                                    children.put(JSONObject().put("name",child.title).put("id",child.poiId)
                                        .put("latitude",point?.latitude).put("longitude",point?.longitude))
                                }
                            }
                            output.put("childrenCode",code).put("children",children);latch.countDown()
                        }
                        override fun onPoiItemSearched(item: PoiItemV2?,code: Int) {}
                        override fun onVisualSearched(result: VisualSearchResult?,code: Int) {}
                    });searchPOIAsyn()
                }
            }
            assertTrue("Campus child POI lookup timed out",latch.await(20,TimeUnit.SECONDS))
        }
        campus.places.firstOrNull()?.let { school ->
            output.put("dorm2",json(request { service.search(SearchPlan("学生公寓2",school.point,1500,SearchOrigin.CAMPUS),it) }))
            if(InstrumentationRegistry.getArguments().getString("collectAllCampusCandidates")=="true") {
                val candidates=JSONObject()
                for(place in CampusCatalog.entries) {
                    val keyword=when {
                        place.id.startsWith("dorm-")->place.id.removePrefix("dorm-")+"号楼"
                        place.id.startsWith("teacher-")->"教师公寓"+place.id.removePrefix("teacher-")
                        place.id=="staff-1"->"员工宿舍"
                        else->place.name
                    }
                    val result=request { service.search(SearchPlan(keyword,school.point,1000,SearchOrigin.CAMPUS),it) }
                    candidates.put(place.id,json(result))
                    output.put("candidates",candidates)
                    File(context.filesDir,"campus-lookup-probe.json").writeText(output.toString(2))
                    if(result.error?.let { it.contains("额度") || it.contains("鉴权") || it.contains("暂时无法连接") }==true) break
                    // Single query per requested place; bounded spacing avoids a burst of requests.
                    Thread.sleep(400)
                }
                output.put("candidates",candidates)
            }
        }
        File(context.filesDir,"campus-lookup-probe.json").writeText(output.toString(2))
        assertNull("Public lookup failed: ${campus.error}",campus.error)
        assertTrue("School lookup returned no confirmed Pingtan campus",campus.places.isNotEmpty())
    }
}
