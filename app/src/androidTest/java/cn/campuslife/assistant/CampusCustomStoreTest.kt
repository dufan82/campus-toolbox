package cn.campuslife.assistant

import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.map.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CampusCustomStoreTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun customNamesCoordinatesAndVisibilitySurviveExportAndRestart() {
        val prefs=context.getSharedPreferences("campus_coordinate_draft",0)
        val old=prefs.getString("public_coordinates_v1",null)
        val profile=context.getSharedPreferences("campus_local_v1",0).all
        try {
            val data=CampusCoordinateData(GeoPoint(25.550861,119.752883),emptyList())
                .withCustomPlace(CampusLandmark("custom-north-2","实训2楼北门",MapCategory.STUDY))
                .withPosition("custom-north-2",GeoPoint(25.5502,119.7514)).withHiddenIds(setOf("coffee"))
            assertTrue(CampusCoordinateStore(context).save(data))
            val restored=CampusCoordinateStore(context).load()
            assertEquals(data,restored)
            val exported=CampusCoordinateStore.encode(restored)
            assertEquals(2,JSONObject(exported).getInt("version"))
            assertEquals(data,CampusCoordinateStore.decode(exported))
            val removed=restored.withoutCustomPlace("custom-north-2")
            assertTrue(CampusCoordinateStore(context).save(removed))
            assertTrue(CampusCoordinateStore(context).load().customPlaces.isEmpty())
            assertEquals(profile,context.getSharedPreferences("campus_local_v1",0).all)
        } finally { prefs.edit().apply { if(old==null) remove("public_coordinates_v1") else putString("public_coordinates_v1",old) }.commit() }
    }

    @Test fun legacyDraftKeepsDeletedCoordinatesDeletedAndInheritsOnlyDefaultVisibility() {
        val prefs=context.getSharedPreferences("campus_coordinate_draft",0)
        val old=prefs.getString("public_coordinates_v1",null)
        try {
            val legacy="""{"version":1,"coordinateSystem":"GCJ-02","campus":{"latitude":25.550861,"longitude":119.752883},"locations":[{"id":"dorm-2","latitude":25.5526,"longitude":119.751,"source":"manual-map-review","reviewed":true}]}"""
            prefs.edit().putString("public_coordinates_v1",legacy).commit()
            val data=CampusCoordinateStore(context).load()
            assertEquals(1,data.records.size)
            assertFalse(data.points.containsKey("canteen-1"))
            assertEquals(GeoPoint(25.5526,119.751),data.points["dorm-2"])
            assertEquals(9,data.hiddenIds.size)
            assertTrue(data.search("书语咖啡",MapCategory.ALL).isEmpty())
            assertTrue(CampusCoordinateStore(context).save(data.withHiddenIds(emptySet())))
            assertTrue(CampusCoordinateStore(context).load().hiddenIds.isEmpty())
        } finally { prefs.edit().apply { if(old==null) remove("public_coordinates_v1") else putString("public_coordinates_v1",old) }.commit() }
    }

    @Test fun bundledPublicMapIncludesLatestReviewedPointsAndHidesNineUnusedPlaces() {
        val data=context.assets.open("pingtan-coordinates.json").bufferedReader().use { CampusCoordinateStore.decode(it.readText()) }
        assertEquals(35,data.records.size)
        assertEquals(9,data.hiddenIds.size)
        assertEquals(GeoPoint(25.552603699916343,119.75096914945675),data.points["dorm-2"])
        assertEquals(GeoPoint(25.550445164316756,119.74984798610771),data.points["training-1-north"])
        assertEquals(38,data.visiblePlaces.size)
        assertTrue(data.search("校史馆",MapCategory.ALL).isEmpty())
        assertEquals("campus-gate-north",data.search("北门",MapCategory.ALL).single().id)
        assertNotNull(data.points["campus-gate-north"])
        assertNull(data.points["campus-gate-south"])
    }

    @Test fun customMetadataCannotBypassCoordinateOrIdentityValidation() {
        val json="""{"version":2,"coordinateSystem":"GCJ-02","campus":{"latitude":25.550861,"longitude":119.752883},"locations":[],"hiddenIds":[],"customPlaces":[{"id":"canteen-1","name":"新地点","category":"STUDY","aliases":[]}]}"""
        assertThrows(IllegalArgumentException::class.java) { CampusCoordinateStore.decode(json) }
    }
}
