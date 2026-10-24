package cn.campuslife.assistant.map

import org.junit.Assert.*
import org.junit.Test

class MapRulesTest {
    private val campus = GeoPoint(25.5,119.8)
    private val viewport = GeoPoint(25.6,119.7)
    private val outside = GeoPoint(26.0,119.3)

    @Test fun invalidCoordinatesAreRejectedRatherThanRendered() {
        listOf(Double.NaN,Double.POSITIVE_INFINITY,91.0,-91.0).forEach { latitude ->
            assertThrows(IllegalArgumentException::class.java) { GeoPoint(latitude,119.8) }
        }
        assertThrows(IllegalArgumentException::class.java) { GeoPoint(25.5,181.0) }
        assertEquals(90.0,GeoPoint(90.0,180.0).latitude,0.0)
    }

    @Test fun blankAllCategoryDoesNotSpendAnOnlineSearchRequest() {
        assertNull(MapRules.plan(SearchScope.CAMPUS,"  ",MapCategory.ALL,campus,null,viewport,100_000))
    }

    @Test fun campusSearchKeepsCampusCenterEvenWhenUserIsElsewhere() {
        val fix = LocatedFix(outside,12f,99_000)
        val plan = MapRules.plan(SearchScope.CAMPUS,"快递",MapCategory.ALL,campus,fix,viewport,100_000)!!
        assertEquals(campus,plan.center)
        assertEquals(SearchOrigin.CAMPUS,plan.origin)
        assertEquals("快递",plan.keyword)
    }

    @Test fun freshNearbyFixIsUsedWithoutSnappingItToSchool() {
        val plan = MapRules.plan(SearchScope.NEARBY,"超市",MapCategory.ALL,campus,LocatedFix(outside,12f,99_000),viewport,100_000)!!
        assertEquals(outside,plan.center)
        assertEquals(SearchOrigin.LOCATION,plan.origin)
    }

    @Test fun staleFutureAndInvalidAccuracyFixesUseExplicitMapCenterOrigin() {
        listOf(LocatedFix(outside,10f,1),LocatedFix(outside,10f,100_001),LocatedFix(outside,Float.NaN,99_000),LocatedFix(outside,0f,99_000)).forEach { fix ->
            val plan = MapRules.plan(SearchScope.NEARBY,"食堂",MapCategory.ALL,campus,fix,viewport,100_000)!!
            assertEquals(viewport,plan.center)
            assertEquals(SearchOrigin.MAP_CENTER,plan.origin)
        }
    }

    @Test fun missingCampusAnchorCannotSearchAnInventedCampusLocation() {
        assertNull(MapRules.plan(SearchScope.CAMPUS,"食堂",MapCategory.ALL,null,null,viewport,100_000))
    }

    @Test fun categorySuppliesKeywordOnlyWhenTextIsEmpty() {
        assertEquals("快递驿站",MapRules.plan(SearchScope.CAMPUS,"",MapCategory.EXPRESS,campus,null,viewport,1)!!.keyword)
        assertEquals("菜鸟",MapRules.plan(SearchScope.CAMPUS,"菜鸟",MapCategory.EXPRESS,campus,null,viewport,1)!!.keyword)
    }

    @Test fun studentDormAliasAndWhitespaceAreNormalized() {
        assertEquals("学生公寓4",MapRules.normalize("  生4  "))
        assertEquals("实训1南",MapRules.normalize("实训一南"))
        assertEquals("长名称 食堂",MapRules.normalize("长名称\n  食堂"))
        assertTrue(MapRules.normalize("楼".repeat(500)).length<=80)
    }

    @Test fun distanceUsesRealCoordinatesAndIsLabelledAsStraightLine() {
        assertEquals(0.0,MapRules.distanceMeters(GeoPoint(0.0,0.0),GeoPoint(0.0,0.0)),0.001)
        assertEquals(111_195.0,MapRules.distanceMeters(GeoPoint(0.0,0.0),GeoPoint(0.0,1.0)),2.0)
        assertEquals("直线约 120 米",MapRules.distanceLabel(120.4))
        assertEquals("直线约 1.2 公里",MapRules.distanceLabel(1240.0))
    }

    @Test fun obsoleteCallbacksCannotReplaceLatestSearchOrDestroyedPage() {
        val gate = RequestGate()
        val old = gate.begin()
        val current = gate.begin()
        assertFalse(gate.accepts(old))
        assertTrue(gate.accepts(current))
        gate.invalidate()
        assertFalse(gate.accepts(current))
    }

    @Test fun completedRequestCannotLaterTimeoutOrCompleteTwice() {
        val gate=RequestGate();val ticket=gate.begin()
        assertTrue(gate.complete(ticket))
        assertFalse(gate.accepts(ticket))
        assertFalse(gate.complete(ticket))
    }

    @Test fun completingAnOldRequestDoesNotCancelCurrentRequest() {
        val gate=RequestGate();val old=gate.begin();val current=gate.begin()
        assertFalse(gate.complete(old))
        assertTrue(gate.accepts(current))
    }

    @Test fun onlyValidPrivateConfigurationCanEnableSdk() {
        assertFalse(MapRules.canInitialize(false,"a".repeat(32)))
        assertFalse(MapRules.canInitialize(true,""))
        assertFalse(MapRules.canInitialize(true,"not-a-key"))
        assertTrue(MapRules.canInitialize(true,"a".repeat(32)))
    }

    @Test fun invalidFixDoesNotBecomeCurrentLocationAfterClockReset() {
        assertFalse(LocatedFix(campus,15f,5000).isFresh(4000))
        assertTrue(LocatedFix(campus,500f,4000).isFresh(5000))
        assertFalse(LocatedFix(campus,15f,4000).isFresh(70_000))
    }

    @Test fun schoolLookupCannotAcceptOtherCampusOrAnUnrelatedShop() {
        assertTrue(MapRules.isPingtanCampus("福建信息职业技术学院(平潭校区)",""))
        assertTrue(MapRules.isPingtanCampus("福建信息职业技术学院","平潭县麒麟大道"))
        assertFalse(MapRules.isPingtanCampus("福建信息职业技术学院(龙腰校区)","福州市"))
        assertFalse(MapRules.isPingtanCampus("福建信息职业技术学院旁便利店","平潭县"))
    }
}
