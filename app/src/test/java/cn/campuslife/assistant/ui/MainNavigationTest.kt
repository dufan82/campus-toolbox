package cn.campuslife.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** NAV-001: physical tab order is home (0), schedule (1), settings (2), not history. */
class MainNavigationTest {
    @Test fun homeToScheduleEntersFromRight() {
        assertEquals(MainNavigation.Direction.FROM_RIGHT,MainNavigation.direction("home","schedule"))
    }
    @Test fun homeToSettingsEntersFromRight() {
        assertEquals(MainNavigation.Direction.FROM_RIGHT,MainNavigation.direction("home","settings"))
    }
    @Test fun scheduleToSettingsEntersFromRight() {
        assertEquals(MainNavigation.Direction.FROM_RIGHT,MainNavigation.direction("schedule","settings"))
    }
    @Test fun settingsToScheduleEntersFromLeft() {
        assertEquals(MainNavigation.Direction.FROM_LEFT,MainNavigation.direction("settings","schedule"))
    }
    @Test fun settingsToHomeEntersFromLeftEvenWhenHomeAlreadyExists() {
        assertEquals(MainNavigation.Direction.FROM_LEFT,MainNavigation.direction("settings","home"))
    }
    @Test fun scheduleToHomeEntersFromLeft() {
        assertEquals(MainNavigation.Direction.FROM_LEFT,MainNavigation.direction("schedule","home"))
    }
    @Test fun selectingTheCurrentDestinationDoesNotAnimate() {
        for(id in listOf("home","schedule","settings")) {
            assertEquals(id,MainNavigation.Direction.NONE,MainNavigation.direction(id,id))
        }
    }
    @Test fun unknownIdsNeverAcquireADirectionOrFallBackToSettings() {
        val unknown=listOf("","unknown","Home","my"," home","schedule ","watermark")
        val all=listOf("home","schedule","settings")+unknown
        for(id in unknown) for(other in all) {
            assertEquals("$id -> $other",MainNavigation.Direction.NONE,MainNavigation.direction(id,other))
            assertEquals("$other -> $id",MainNavigation.Direction.NONE,MainNavigation.direction(other,id))
        }
    }
}
