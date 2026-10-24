package cn.campuslife.assistant.map

import org.junit.Assert.*
import org.junit.Test

class CampusPlacesTest {
    private val center=GeoPoint(25.550861,119.752883)
    private val custom=CampusLandmark("custom-training-2-north","实训2楼北门",MapCategory.STUDY)

    @Test fun customPlaceAppearsInSearchWithAnIndependentUnlocatedId() {
        val data=CampusCoordinateData(center,emptyList()).withCustomPlace(custom)
        assertEquals(listOf("custom-training-2-north"),data.search("实训二楼北门",MapCategory.ALL).map { it.id })
        assertEquals(listOf(custom),data.customPlaces)
        assertNull(data.points[custom.id])
    }

    @Test fun exactCustomNumericNameWinsBeforeTheDormNumberShortcut() {
        for(number in 1..10) {
            val place=custom.copy(name=number.toString())
            val data=CampusCoordinateData(center,emptyList()).withCustomPlace(place)
            assertEquals(listOf(place.id),data.search(number.toString(),MapCategory.ALL).map { it.id })
            assertEquals(listOf("dorm-$number"),data.search(number.toString(),MapCategory.DORM).map { it.id })
        }
    }

    @Test fun renamingCustomPlaceKeepsItsCoordinateAndCategorySearch() {
        val point=GeoPoint(25.5502,119.7514)
        val before=CampusCoordinateData(center,emptyList()).withCustomPlace(custom).withPosition(custom.id,point)
        val after=before.withCustomPlace(custom.copy(name="实训2楼南门",category=MapCategory.SERVICE))
        assertEquals(point,after.points[custom.id])
        assertTrue(after.search("实训2楼北门",MapCategory.ALL).isEmpty())
        assertEquals(custom.id,after.search("实训二楼南门",MapCategory.SERVICE).single().id)
        assertTrue(after.search("实训二楼南门",MapCategory.STUDY).isEmpty())
    }

    @Test fun movingAPointPreservesOtherPositionsCustomNamesAndVisibility() {
        val original=CampusCoordinateData(center,listOf(CampusCoordinate("canteen-1",center,"review",true)))
            .withCustomPlace(custom).withHiddenIds(setOf("coffee")).withPosition(custom.id,GeoPoint(25.5502,119.7514))
        val moved=original.withPosition(custom.id,GeoPoint(25.5503,119.7515))
        assertEquals(GeoPoint(25.5503,119.7515),moved.points[custom.id])
        assertEquals(center,moved.points["canteen-1"])
        assertEquals(listOf(custom),moved.customPlaces)
        assertEquals(setOf("coffee"),moved.hiddenIds)
        assertEquals(GeoPoint(25.5502,119.7514),original.points[custom.id])
    }

    @Test fun customDeletionRemovesOnlyItsNameCoordinateAndHiddenReference() {
        val data=CampusCoordinateData(center,listOf(CampusCoordinate("canteen-1",center,"review",true)))
            .withCustomPlace(custom).withPosition(custom.id,center).withHiddenIds(setOf(custom.id,"coffee"))
        val next=data.withoutCustomPlace(custom.id)
        assertTrue(next.customPlaces.isEmpty())
        assertFalse(next.points.containsKey(custom.id))
        assertEquals(center,next.points["canteen-1"])
        assertEquals(setOf("coffee"),next.hiddenIds)
        assertThrows(IllegalArgumentException::class.java) { data.withoutCustomPlace("canteen-1") }
    }

    @Test fun deletingOnlyTheCustomCoordinateKeepsItsNameAvailableToRemark() {
        val data=CampusCoordinateData(center,emptyList()).withCustomPlace(custom).withPosition(custom.id,center)
        val next=data.without(custom.id)
        assertEquals(custom,next.search("实训2楼北门",MapCategory.ALL).single())
        assertNull(next.points[custom.id])
    }

    @Test fun hiddenPlacesLeaveNormalSearchWithoutLosingTheirCoordinates() {
        val data=CampusCoordinateData(center,listOf(CampusCoordinate("coffee",center,"review",true))).withHiddenIds(setOf("coffee"))
        assertTrue(data.search("书语",MapCategory.ALL).isEmpty())
        assertEquals(center,data.points["coffee"])
        assertTrue(data.catalog.any { it.id=="coffee" })
        assertEquals("coffee",data.withHiddenIds(emptySet()).search("书语",MapCategory.ALL).single().id)
        assertThrows(IllegalArgumentException::class.java) { data.withHiddenIds(setOf("unknown")) }
    }

    @Test fun invalidOrDuplicateNamesAndReservedIdsAreRejected() {
        val data=CampusCoordinateData(center,emptyList()).withCustomPlace(custom)
        for(place in listOf(custom.copy(name=" "),custom.copy(name="x".repeat(41)),custom.copy(name="test\nname"),
            custom.copy(id="canteen-1"),custom.copy(category=MapCategory.ALL),custom.copy(id="custom-duplicate",name="实训2楼北门"),
            custom.copy(name="学生宿舍一号楼"))) {
            assertThrows("Invalid custom place: ${place.id}",IllegalArgumentException::class.java) { data.withCustomPlace(place) }
        }
    }

    @Test fun unknownAndDistantCoordinatesCannotBeAdded() {
        val data=CampusCoordinateData(center,emptyList()).withCustomPlace(custom)
        assertThrows(IllegalArgumentException::class.java) { data.withPosition("other",center) }
        assertThrows(IllegalArgumentException::class.java) { data.withPosition(custom.id,GeoPoint(26.0,120.0)) }
    }
}
