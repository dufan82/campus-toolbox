package cn.campuslife.assistant.map

import org.junit.Assert.*
import org.junit.Test

class CampusCoordinatesTest {
    private val center=GeoPoint(25.550861,119.752883)
    private val record=CampusCoordinate("canteen-1",GeoPoint(25.55155,119.751397),"reviewed public POI",true)

    @Test fun onlyReviewedNamedInCampusCoordinatesCanProduceMapPoints() {
        assertEquals(mapOf("canteen-1" to record.point),CampusCoordinateRules.validate("GCJ-02",center,listOf(record)))
        for(bad in listOf(record.copy(id="unknown"),record.copy(reviewed=false),record.copy(source=""),record.copy(point=GeoPoint(26.0,119.3)))) {
            assertThrows(IllegalArgumentException::class.java) { CampusCoordinateRules.validate("GCJ-02",center,listOf(bad)) }
        }
    }

    @Test fun wrongCoordinateSystemAndDuplicateIdsCannotSilentlyMoveBuildings() {
        assertThrows(IllegalArgumentException::class.java) { CampusCoordinateRules.validate("WGS84",center,listOf(record)) }
        assertThrows(IllegalArgumentException::class.java) { CampusCoordinateRules.validate("GCJ-02",center,listOf(record,record.copy(point=center))) }
    }
}
