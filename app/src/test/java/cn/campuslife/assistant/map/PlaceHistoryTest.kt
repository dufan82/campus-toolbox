package cn.campuslife.assistant.map

import org.junit.Assert.*
import org.junit.Test

class PlaceHistoryTest {
    @Test fun restoreAndSearchDisplayDoNotBecomeVisitsAndLatestEventWins() {
        val start=PlaceHistory().visit("dorm-1")
        assertEquals(start,start.visit("dorm-2",VisitReason.RESTORE))
        assertEquals(start,start.visit("dorm-2",VisitReason.SEARCH_DISPLAY))
        assertEquals(listOf("dorm-1","dorm-2"),start.visit("dorm-2").visit("dorm-1").recent)
    }
    @Test fun favoriteTimeIsRecordedOnlyForExplicitNewFavoriteAndRemovedWithIt() {
        val state=PlaceHistory().toggle("dorm-1",1234L)
        assertEquals(mapOf("dorm-1" to 1234L),state.favoriteTimes)
        assertTrue(state.removeFavorite("dorm-1").favoriteTimes.isEmpty())
    }
    @Test fun cancellingAnAlreadyRemovedFavoriteNeverAddsItBack() {
        assertTrue(PlaceHistory().removeFavorite("dorm-1").favorites.isEmpty())
        assertEquals(listOf("dorm-2"),PlaceHistory(listOf("dorm-1","dorm-2")).removeFavorite("dorm-1").favorites)
    }
    @Test fun favoritesToggleByIdAndDoNotChangeRecents() {
        val state=PlaceHistory().visit("dorm-1").toggle("dorm-1")
        assertEquals(listOf("dorm-1"),state.favorites)
        assertEquals(listOf("dorm-1"),state.recent)
        assertTrue(state.toggle("dorm-1").favorites.isEmpty())
    }
    @Test fun visitsDeduplicateAndKeepOnlyTwentyMostRecent() {
        var state=PlaceHistory()
        (1..24).forEach { state=state.visit("place-$it") }
        state=state.visit("place-8")
        assertEquals("place-8",state.recent.first())
        assertEquals(20,state.recent.size)
        assertEquals(20,state.recent.distinct().size)
        assertFalse("place-1" in state.recent)
    }
    @Test fun resolvingHiddenMissingAndDeletedNeverUsesAnOldCoordinate() {
        val data=CampusCoordinateData(GeoPoint(25.548,119.749),emptyList(),hiddenIds=setOf("dorm-1"))
        assertEquals(PlaceAvailability.HIDDEN,PlaceHistory.availability("dorm-1",data))
        assertEquals(PlaceAvailability.MISSING_POSITION,PlaceHistory.availability("dorm-2",data))
        assertEquals(PlaceAvailability.REMOVED,PlaceHistory.availability("custom-deleted",data))
    }
}
