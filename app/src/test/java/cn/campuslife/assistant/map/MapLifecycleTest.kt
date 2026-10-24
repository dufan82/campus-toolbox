package cn.campuslife.assistant.map

import org.junit.Assert.*
import org.junit.Test

class MapLifecycleTest {
    @Test fun nativeAndRuntimeCleanupFailuresDoNotBlockRemainingCleanup() {
        val events=mutableListOf<String>()
        MapLifecycle.release(
            { events.add("map");throw UnsatisfiedLinkError("test-only") },
            { events.add("location");throw SecurityException("test-only") },
            { events.add("surface") }
        )
        assertEquals(listOf("map","location","surface"),events)
    }

    @Test fun consentIsPersistedBeforeAnyProviderCleanupAndFailuresStillFinish() {
        var consent=true
        val events=mutableListOf<String>()
        val saved=MapLifecycle.withdraw(
            { consent=false;events.add("persist");true },
            { assertFalse(consent);events.add("dispose");throw LinkageError("test-only") },
            { events.add("provider");throw IllegalStateException("test-only") },
            { events.add("finish") }
        )
        assertTrue(saved)
        assertEquals(listOf("persist","dispose","provider","finish"),events)
    }

    @Test fun storageFailureStillStopsProvidersAndIsReportedAsNotSaved() {
        var stopped=false
        assertFalse(MapLifecycle.withdraw({ throw IllegalStateException("test-only") },{ stopped=true }))
        assertTrue(stopped)
    }
}
