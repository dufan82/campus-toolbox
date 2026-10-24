package cn.campuslife.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.map.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampusCoordinateStoreTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bundledDataLinksOnlyRequestedNamesAndReviewedDormCoordinates() {
        val data=context.assets.open("pingtan-coordinates.json").bufferedReader().use { CampusCoordinateStore.decode(it.readText()) }
        assertEquals(GeoPoint(25.551808,119.75372),data.points["canteen-2"])
        assertEquals(GeoPoint(25.552941,119.75376),data.points["sunken-plaza"])
        assertEquals(GeoPoint(25.552603699916343,119.75096914945675),data.points["dorm-2"])
        assertTrue(data.records.all { record -> data.catalog.any { it.id==record.id } })
    }

    @Test fun exportRoundTripAndRestartKeepPublicDraftWithoutTouchingStudentData() {
        val prefs=context.getSharedPreferences("campus_coordinate_draft",0)
        val old=prefs.getString("public_coordinates_v1",null)
        val profile=context.getSharedPreferences("campus_local_v1",0).all
        try {
            val data=CampusCoordinateData(GeoPoint(25.550861,119.752883),listOf(
                CampusCoordinate("dorm-2",GeoPoint(25.5526,119.751),"manual-map-review",true)))
            assertTrue(CampusCoordinateStore(context).save(data))
            val restored=CampusCoordinateStore(context).load()
            assertEquals(GeoPoint(25.5526,119.751),restored.points["dorm-2"])
            val exported=CampusCoordinateStore.encode(restored)
            assertEquals(GeoPoint(25.5526,119.751),CampusCoordinateStore.decode(exported).points["dorm-2"])
            val json=JSONObject(exported)
            assertEquals(setOf("version","coordinateSystem","campus","locations","catalog","reference","customPlaces","hiddenIds"),json.keys().asSequence().toSet())
            assertEquals(45,json.getJSONArray("catalog").length())
            assertEquals(profile,context.getSharedPreferences("campus_local_v1",0).all)
        } finally {
            prefs.edit().apply { if(old==null) remove("public_coordinates_v1") else putString("public_coordinates_v1",old) }.commit()
        }
    }

    @Test fun invalidPublicJsonIsRejectedInsteadOfRenderingWrongMarkers() {
        val invalid="""{"version":1,"coordinateSystem":"WGS84","campus":{"latitude":25.550861,"longitude":119.752883},"locations":[]}"""
        assertThrows(IllegalArgumentException::class.java) { CampusCoordinateStore.decode(invalid) }
    }

    @Test fun exportIncludesReferencePairsWithoutMistakingPixelsForLatitude() {
        val data=context.assets.open("pingtan-coordinates.json").bufferedReader().use { CampusCoordinateStore.decode(it.readText()) }
        val reference=JSONObject(CampusCoordinateStore.encode(data)).getJSONObject("reference")
        assertEquals("normalized-image",reference.getString("coordinateSystem"))
        assertEquals(42,reference.getJSONArray("points").length())
        assertEquals("pingtan-reference.png",reference.getString("asset"))
        val points=reference.getJSONArray("points")
        val dorm=(0 until points.length()).map { points.getJSONObject(it) }.single { it.getString("id")=="dorm-2" }
        assertEquals(538.0/1778,dorm.getDouble("x"),0.000001)
        assertFalse(dorm.has("latitude"))
        assertEquals(data,CampusCoordinateStore.decode(CampusCoordinateStore.encode(data)))
    }
}
