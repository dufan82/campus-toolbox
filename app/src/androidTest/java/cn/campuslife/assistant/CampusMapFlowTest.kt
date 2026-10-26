package cn.campuslife.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.ui.MainActivity
import cn.campuslife.assistant.map.MapScreen
import cn.campuslife.assistant.map.MapSdk
import cn.campuslife.assistant.map.MapPlace
import cn.campuslife.assistant.map.GeoPoint
import cn.campuslife.assistant.map.RequestGate
import cn.campuslife.assistant.map.SearchScope
import cn.campuslife.assistant.map.CampusCatalog
import cn.campuslife.assistant.map.MapCategory
import cn.campuslife.assistant.map.CampusCoordinateStore
import cn.campuslife.assistant.map.CampusCoordinateData
import cn.campuslife.assistant.map.CampusMapMaintenance
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampusMapFlowTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get()=context.getSharedPreferences("campus_map",0)

    @Test fun campusSearchListsUnlocatedGateWithoutRequestingOnlineService() = withNativeMapState { activity,screen ->
        setField(activity,"sdk",null)
        screen.query.setText("南门")
        activity.javaClass.getDeclaredMethod("search").apply { isAccessible=true }.invoke(activity)
        assertNotNull("Gate is missing when SDK/network is absent",activity.window.decorView.findViewWithTag<View>("campus_place_campus-gate-south"))
        assertTrue(screen.status.text.contains("待核对"))
        var selected=false
        screen.onChoose={ selected=true }
        activity.window.decorView.findViewWithTag<View>("campus_place_campus-gate-south").performClick()
        assertFalse("Unlocated dorm must not navigate to a guessed point",selected)
    }

    @Test fun campusSearchUsesCustomNamesAndTheirOwnCoordinates() = withNativeMapState { activity,screen ->
        val data=CampusCoordinateData(GeoPoint(25.550861,119.752883),emptyList())
            .withCustomPlace(cn.campuslife.assistant.map.CampusLandmark("custom-entry","实训2楼北门",MapCategory.STUDY))
            .withPosition("custom-entry",GeoPoint(25.5502,119.7514))
        setField(activity,"coordinateData",data);setField(activity,"sdk",null)
        screen.query.setText("实训二楼北门")
        activity.javaClass.getDeclaredMethod("search").apply { isAccessible=true }.invoke(activity)
        val selected=field(activity,"selected") as MapPlace
        assertEquals("custom-entry",selected.id)
        assertEquals(GeoPoint(25.5502,119.7514),selected.point)
        assertNotNull(activity.window.decorView.findViewWithTag<View>("map_navigate"))
    }

    @Test fun hiddenNamesDoNotLeakBackIntoCampusSearchResults() = withNativeMapState { activity,screen ->
        val data=CampusCoordinateStore(activity).load().withHiddenIds(setOf("coffee"))
        setField(activity,"coordinateData",data);setField(activity,"sdk",null)
        screen.query.setText("书语")
        activity.javaClass.getDeclaredMethod("search").apply { isAccessible=true }.invoke(activity)
        assertNull(activity.window.decorView.findViewWithTag<View>("campus_place_coffee"))
    }

    @Test fun reviewHintRemainsVisibleAfterRefreshingCampusResults() = withNativeMapState { activity,screen ->
        val store=CampusCoordinateStore(activity)
        val maintenance=CampusMapMaintenance(activity,store,store.load(),{}, {})
        setField(activity,"maintenance",maintenance)
        maintenance.select("canteen-2");screen.query.setText("食堂2")
        activity.javaClass.getDeclaredMethod("searchCampus").apply { isAccessible=true }.invoke(activity)
        activity.javaClass.getDeclaredMethod("maintenanceTargetChanged").apply { isAccessible=true }.invoke(activity)
        assertTrue(screen.status.text.contains("食堂2"))
        assertTrue(screen.status.text.contains("长按"))
        assertEquals(View.VISIBLE,(screen.status.parent as View).visibility)
    }

    @Test fun endingReviewRestoresTheSingleDestinationNavigation() = withNativeMapState { activity,screen ->
        val store=CampusCoordinateStore(activity)
        val maintenance=CampusMapMaintenance(activity,store,store.load(),{}, {})
        setField(activity,"maintenance",maintenance)
        maintenance.select("canteen-2");screen.query.setText("食堂2")
        activity.javaClass.getDeclaredMethod("searchCampus").apply { isAccessible=true }.invoke(activity)
        assertNull(field(activity,"selected"))
        maintenance.stop()
        activity.javaClass.getDeclaredMethod("maintenanceTargetChanged").apply { isAccessible=true }.invoke(activity)
        assertEquals("canteen-2",(field(activity,"selected") as? MapPlace)?.id)
        assertNotNull(screen.root.findViewWithTag<View>("map_navigate"))
    }

    @Test fun referenceSelectionClearsPreviousSearchBeforeStartingCoordinateReview() = withNativeMapState { activity,screen ->
        val store=CampusCoordinateStore(activity)
        val maintenance=CampusMapMaintenance(activity,store,store.load(),{}, {})
        setField(activity,"maintenance",maintenance)
        screen.query.setText("生1")
        activity.javaClass.getDeclaredMethod("showReference",String::class.java).apply { isAccessible=true }.invoke(activity,"canteen-2")
        val dialog=activity.javaClass.getDeclaredField("referenceDialog").apply { isAccessible=true }.get(activity) as cn.campuslife.assistant.map.CampusReferenceDialog
        dialog.window!!.decorView.findViewWithTag<Button>("ref_mark").performClick()
        assertEquals("食堂2",screen.query.text.toString())
        assertEquals("canteen-2",maintenance.target?.id)
        assertNull(field(activity,"selected"))
    }

    // Mount only native views: these cases submit no online request and never initialize SDK.
    private fun withNativeMapState(block: (android.app.Activity,MapScreen)->Unit) {
        val old=prefs.getBoolean("privacy_consent_v1",false)
        prefs.edit().putBoolean("privacy_consent_v1",false).commit()
        try {
            val intent=Intent().setClassName(context,"cn.campuslife.assistant.map.CampusMapActivity")
            ActivityScenario.launch<android.app.Activity>(intent).use { scenario -> scenario.onActivity { activity ->
                val screen=MapScreen(activity)
                setField(activity,"screen",screen)
                setField(activity,"sdk",MapSdk(activity))
                block(activity,screen)
            } }
        } finally { prefs.edit().putBoolean("privacy_consent_v1",old).commit() }
    }

    private fun setField(activity: android.app.Activity,name: String,value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible=true }.set(activity,value)
    }

    private fun field(activity: android.app.Activity,name: String): Any? = activity.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(activity)

    @Test fun emptySubmissionCancelsPendingRequestAndBusyState() = withNativeMapState { activity,screen ->
        val gate=field(activity,"gate") as RequestGate
        val previous=gate.begin()
        screen.busy(true,"test pending")
        activity.javaClass.getDeclaredMethod("search").apply { isAccessible=true }.invoke(activity)
        assertFalse("Empty submission must invalidate the old query",gate.accepts(previous))
        assertFalse(screen.status.text.contains("test pending"))
        val searchButton=(screen.query.parent as ViewGroup).getChildAt(1)
        assertTrue(searchButton.isEnabled)
    }

    @Test fun clearingSearchClosesTheOldDestinationAndRestoresAllCampusPoints() = withNativeMapState { activity,screen ->
        val place=MapPlace("fixture","测试地点","测试地址",GeoPoint(25.5,119.8))
        setField(activity,"places",listOf(place));setField(activity,"selected",place)
        setField(activity,"resultOrigin",place.point);setField(activity,"resultOriginLabel","我的位置")
        screen.results(place,place.point,"我的位置")
        val gate=field(activity,"gate") as RequestGate;val previous=gate.begin()
        screen.query.setText("")
        activity.javaClass.getDeclaredMethod("search").apply { isAccessible=true }.invoke(activity)
        assertFalse(gate.accepts(previous))
        assertEquals(CampusCoordinateStore(activity).load().records.size,(field(activity,"places") as List<*>).size)
        assertNull(field(activity,"selected"));assertNull(field(activity,"resultOrigin"))
        assertEquals(View.GONE,activity.window.decorView.findViewWithTag<View>("map_results").visibility)
    }

    @Test fun oldNearbyAndCategoryStateCannotRestoreRemovedFilters() {
        val old=prefs.getBoolean("privacy_consent_v1",false)
        prefs.edit().putBoolean("privacy_consent_v1",false).commit()
        try {
            val intent=Intent().setClassName(context,"cn.campuslife.assistant.map.CampusMapActivity")
            ActivityScenario.launch<android.app.Activity>(intent).use { scenario ->
                scenario.onActivity { activity -> setField(activity,"scope",SearchScope.NEARBY);setField(activity,"category",MapCategory.FOOD) }
                scenario.recreate()
                scenario.onActivity { activity -> assertEquals(SearchScope.CAMPUS,field(activity,"scope"));assertEquals(MapCategory.ALL,field(activity,"category")) }
            }
        } finally { prefs.edit().putBoolean("privacy_consent_v1",old).commit() }
    }

    @Test fun unsupportedOrUnconfiguredMapCanRevokeConsentWithoutLocationPermission() {
        assumeTrue(BuildConfig.AMAP_ANDROID_KEY.isEmpty() || Build.SUPPORTED_ABIS.none { it in listOf("arm64-v8a","armeabi-v7a") })
        val old=prefs.getBoolean("privacy_consent_v1",false)
        val permissionBefore=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        prefs.edit().putBoolean("privacy_consent_v1",false).commit()
        try {
            val intent=Intent().setClassName(context,"cn.campuslife.assistant.map.CampusMapActivity")
            ActivityScenario.launch<android.app.Activity>(intent).use { scenario ->
                scenario.onActivity { activity -> activity.window.decorView.findViewWithTag<View>("map_accept").performClick() }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue(prefs.getBoolean("privacy_consent_v1",false))
                    assertNotNull(activity.window.decorView.findViewWithTag<View>("map_unavailable"))
                    assertNull(activity.window.decorView.findViewWithTag<View>("map_surface"))
                    fun findWithdraw(view: View): Button? {
                        if(view is Button && view.text=="撤回地图同意") return view
                        if(view is ViewGroup) for(index in 0 until view.childCount) findWithdraw(view.getChildAt(index))?.let { return it }
                        return null
                    }
                    val withdraw=findWithdraw(activity.window.decorView)
                    assertNotNull(withdraw)
                    withdraw!!.performClick()
                }
                assertFalse(prefs.getBoolean("privacy_consent_v1",false))
                assertEquals(permissionBefore,context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            }
        } finally { prefs.edit().putBoolean("privacy_consent_v1",old).commit() }
    }

    @Test fun mapControlsDoNotClipTheirLabelsWithLargeText() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> MapScreen(activity).addMapControls() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                fun descendants(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                val buttons=descendants(activity.window.decorView).filterIsInstance<Button>().filter { it.text.toString() in listOf("搜索","我的位置","查看全校") }
                assertEquals(3,buttons.size)
                for(button in buttons) {
                    val layout=button.layout
                    assertNotNull(layout)
                    val available=button.height-button.compoundPaddingTop-button.compoundPaddingBottom
                    assertTrue("${button.text} text is clipped at font scale ${activity.resources.configuration.fontScale}",layout.height<=available)
                    for(line in 0 until layout.lineCount) assertEquals(0,layout.getEllipsisCount(line))
                }
            }
        }
    }

    @Test fun homeKeepsThreeExpressShortcutsAndAddsNativeMapEntry() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val until=SystemClock.uptimeMillis()+5000
            var ready=false
            while(!ready && SystemClock.uptimeMillis()<until) {
                scenario.onActivity { ready=it.window.decorView.findViewWithTag<View>("open_campus_map")!=null }
                if(!ready) SystemClock.sleep(30)
            }
            scenario.onActivity { activity ->
            val root=activity.window.decorView
            assertNotNull(root.findViewWithTag<View>("shortcut_pdd_packages"))
            assertNotNull(root.findViewWithTag<View>("shortcut_pdd_code"))
            assertNotNull(root.findViewWithTag<View>("shortcut_douyin_orders"))
            assertNotNull("Native map entry is missing",root.findViewWithTag<View>("open_campus_map"))
        } }
    }

    @Test fun privacyRefusalDoesNotRequestLocationAndDoesNotDisableOtherTools() {
        val old=prefs.getBoolean("privacy_consent_v1",false)
        prefs.edit().putBoolean("privacy_consent_v1",false).commit()
        try {
            val intent=Intent().setClassName(context,"cn.campuslife.assistant.map.CampusMapActivity")
            ActivityScenario.launch<android.app.Activity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertNotNull(activity.window.decorView.findViewWithTag<View>("map_privacy"))
                    assertNull(activity.window.decorView.findViewWithTag<View>("map_surface"))
                    activity.window.decorView.findViewWithTag<View>("map_decline").performClick()
                }
                SystemClock.sleep(100)
                assertFalse(prefs.getBoolean("privacy_consent_v1",false))
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity {
                assertNotNull(it.window.decorView.findViewWithTag<View>("shortcut_pdd_code"))
            } }
        } finally { prefs.edit().putBoolean("privacy_consent_v1",old).commit() }
    }

    @Test fun unacceptedPrivacySurvivesRotationWithoutInitializingMap() {
        val old=prefs.getBoolean("privacy_consent_v1",false)
        prefs.edit().putBoolean("privacy_consent_v1",false).commit()
        try {
            val intent=Intent().setClassName(context,"cn.campuslife.assistant.map.CampusMapActivity")
            ActivityScenario.launch<android.app.Activity>(intent).use { scenario ->
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertNotNull(activity.window.decorView.findViewWithTag<View>("map_privacy"))
                    assertNull(activity.window.decorView.findViewWithTag<View>("map_surface"))
                }
            }
            val requested=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            assertFalse(requested.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        } finally { prefs.edit().putBoolean("privacy_consent_v1",old).commit() }
    }
}
