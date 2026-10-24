package cn.campuslife.assistant.map

import org.junit.Assert.*
import org.junit.Test

class CampusReferenceTest {
    @Test fun hiddenOriginalDiagramPointsCannotBeChosenByTappingTheirOldAnchor() {
        val transform=ReferenceTransform.fit(400.0,600.0)
        val position=transform.screen(CampusReference.points.getValue("coffee"))
        assertEquals("coffee",CampusReference.hit(transform,position.x,position.y,1.0))
        assertNull(CampusReference.hit(transform,position.x,position.y,1.0,CampusReference.points.keys-"coffee"))
    }
    @Test fun deletingPositionPreservesThePlaceNameOtherPointsAndOriginalSnapshot() {
        val data=CampusCoordinateData(GeoPoint(25.550861,119.752883),listOf(
            CampusCoordinate("dorm-2",GeoPoint(25.5526,119.751),"manual-map-review",true),
            CampusCoordinate("canteen-1",GeoPoint(25.55155,119.751397),"reviewed POI",true)))
        val next=data.without("dorm-2")
        assertFalse(next.points.containsKey("dorm-2"))
        assertEquals(GeoPoint(25.55155,119.751397),next.points["canteen-1"])
        assertEquals(2,data.records.size)
        assertEquals(listOf("dorm-2"),CampusCatalog.search("生2",MapCategory.ALL).map { it.id })
    }

    @Test fun referenceIndexUsesImageAnchorsNotGeographicCoordinatesOrInventedPlaza() {
        assertEquals(42,CampusReference.points.size)
        assertEquals(538.0/1778,CampusReference.points.getValue("dorm-2").x,0.000001)
        assertEquals(378.0/1279,CampusReference.points.getValue("dorm-2").y,0.000001)
        assertNull(CampusReference.points["sunken-plaza"])
        assertTrue(CampusReference.points.keys.all { id -> CampusCatalog.entries.any { it.id==id } })
    }

    @Test fun zoomKeepsTheTouchedImagePointFixedAndPanCannotLoseTheImage() {
        val fit=ReferenceTransform.fit(400.0,600.0)
        val center=ImagePoint(0.5,0.5)
        val screen=fit.screen(center)
        assertEquals(200.0,screen.x,0.000001)
        assertEquals(300.0,screen.y,0.000001)
        val zoomed=fit.zoomAt(2.0,200.0,300.0)
        assertEquals(screen,zoomed.screen(center))
        assertEquals(center,zoomed.imageAt(screen.x,screen.y))
        val dragged=zoomed.pan(1e6,-1e6)
        assertTrue(dragged.left<=0 && dragged.left+dragged.imageWidth>=400)
        assertTrue(dragged.top<=300 && dragged.top+dragged.imageHeight>=300)
        assertNull(fit.imageAt(0.0,0.0))
    }

    @Test fun hitTestingUsesTransformedCoordinatesAndNeverChoosesFarAwayPoints() {
        val transform=ReferenceTransform.fit(400.0,600.0).zoomAt(3.0,200.0,300.0)
        val point=transform.screen(CampusReference.points.getValue("dorm-2"))
        assertEquals("dorm-2",CampusReference.hit(transform,point.x,point.y,5.0))
        assertNull(CampusReference.hit(transform,-1000.0,-1000.0,24.0))
    }
}
