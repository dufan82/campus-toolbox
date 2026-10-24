package cn.campuslife.assistant

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.map.*
import cn.campuslife.assistant.ui.MainActivity
import cn.campuslife.assistant.ui.Ui
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CampusMinimalMapTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    @Test fun mainMapDoesNotExposeScopeOrCategoryFilters() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val screen=MapScreen(activity)
            val removed=setOf("校园","附近","全部","食堂","宿舍","教学","快递","运动","服务")
            assertTrue("Scope/category controls must not occupy the map",all(screen.root).filterIsInstance<Button>().none { it.text.toString() in removed })
            assertNotNull(screen.query)
        } }
    }

    @Test fun searchChoicesAreAboveTheMapAndBottomShowsOnlyTheChosenNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val screen=MapScreen(activity)
            val data=CampusCoordinateStore(activity).load()
            val items=CampusCatalog.located(data.search("食堂",MapCategory.ALL),data.points)
            screen.query.setText("食堂")
            screen.campusResults(items,null,null,"")
            assertEquals(View.GONE,screen.root.findViewWithTag<View>("map_results").visibility)
            val row=screen.root.findViewWithTag<View>("campus_place_canteen-2")
            assertNotNull("Search choices should remain selectable",row)
            val controls=screen.query.parent.parent as ViewGroup
            assertTrue("Search choices must live with the search box, not below the map",all(controls).contains(row))
            var chosen: MapPlace?=null
            screen.onChoose={ place -> chosen=place;screen.campusResults(items,place,null,"") }
            row.performClick()
            assertEquals("canteen-2",chosen?.id)
            assertNull("Choices should collapse after selection",screen.root.findViewWithTag<View>("campus_place_canteen-1"))
            val bottom=screen.root.findViewWithTag<View>("map_results")
            assertEquals(View.VISIBLE,bottom.visibility)
            val navigation=all(bottom).filterIsInstance<Button>()
            assertEquals(1,navigation.size)
            var destination: MapPlace?=null
            screen.onNavigate={ destination=it };navigation.single().performClick()
            assertEquals("canteen-2",destination?.id)
        } }
    }

    @Test fun emptyQueryDoesNotOpenAnAllPlacesListOrNavigationCard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val screen=MapScreen(activity);val data=CampusCoordinateStore(activity).load()
            screen.campusResults(CampusCatalog.located(data.visiblePlaces,data.points),null,null,"")
            assertEquals(View.GONE,screen.root.findViewWithTag<View>("map_results").visibility)
            assertNull(screen.root.findViewWithTag<View>("campus_place_dorm-1"))
        } }
    }

    @Test fun maintenanceMenuKeepsCustomEditingButDropsListClearAndExport() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> val store=CampusCoordinateStore(activity);CampusMapMaintenance(activity,store,store.load(),{},{}).menu() }
            instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(150,5000)
            val root=instrumentation.uiAutomation.rootInActiveWindow!!
            fun texts(node: android.view.accessibility.AccessibilityNodeInfo): List<String> = listOfNotNull(node.text?.toString())+(0 until node.childCount).flatMap { node.getChild(it)?.let(::texts).orEmpty() }
            val shown=texts(root)
            assertTrue(shown.contains("新增自定义地点"));assertTrue(shown.contains("管理自定义地点"))
            assertTrue(shown.none { it.startsWith("从名单选择") || it=="删除已标位置" || it=="导出公共地点表" })
        }
    }

    @Test fun latestBundleKeepsCustomEntrancesAndDoesNotReviveClearedCoordinates() {
        val context=instrumentation.targetContext
        val data=context.assets.open("pingtan-coordinates.json").bufferedReader().use { CampusCoordinateStore.decode(it.readText()) }
        assertEquals(35,data.records.size)
        assertEquals(setOf("实训2北门","实训2南门"),data.customPlaces.map { it.name }.toSet())
        assertEquals(GeoPoint(25.54852253457128,119.74984798610771),data.points["custom-3545700a-0ca8-4738-9b1f-49ea3e46ea34"])
        assertEquals(GeoPoint(25.547945376640424,119.7498748081974),data.points["custom-33c8a79f-1cf5-4b1a-95c0-adc669ea7123"])
        assertNull(data.points["training-2"]);assertNull(data.points["mental-health"])
        assertEquals(9,data.hiddenIds.size)
    }

    @Test fun selectedNavigationStaysReachableInBothOrientations() {
        for(orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.requestedOrientation=orientation }
                val expected=if(orientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                val deadline=SystemClock.uptimeMillis()+5000
                var ready=false
                do {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { ready=it.resources.configuration.orientation==expected && it.window.decorView.hasWindowFocus() }
                    if(!ready) SystemClock.sleep(50)
                } while(!ready && SystemClock.uptimeMillis()<deadline)
                assertTrue("Rotation did not settle",ready)
                lateinit var screen: MapScreen
                scenario.onActivity { activity ->
                    screen=MapScreen(activity);screen.onMaintenance={};screen.addMapControls()
                    screen.viewport.addView(Ui.text(activity,"地图显示区（仅布局检查）",14f,Ui.muted),FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
                    screen.query.setText("实训2北门")
                    screen.results(MapPlace("layout-fixture","实训2北门","",GeoPoint(25.5485,119.7498)),null,"")
                }
                instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(150,5000)
                scenario.onActivity {
                    val navigation=screen.root.findViewWithTag<Button>("map_navigate")
                    if(orientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                        var parent=navigation.parent
                        while(parent is View) {
                            if(parent is ScrollView) parent.fullScroll(View.FOCUS_DOWN)
                            parent=parent.parent
                        }
                    }
                }
                instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(150,5000)
                scenario.onActivity {
                    val navigation=screen.root.findViewWithTag<Button>("map_navigate")
                    val bounds=Rect()
                    assertTrue("Navigation must remain visible",navigation.getGlobalVisibleRect(bounds))
                    assertEquals("Navigation is clipped",navigation.height,bounds.height())
                    assertTrue(navigation.height>=Ui.dp(it,48))
                    assertTrue("Map lost its usable area",screen.viewport.height>=Ui.dp(it,120))
                }
                if(InstrumentationRegistry.getArguments().getString("captureMinimalMap")=="true") {
                    val suffix=if(orientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) "landscape" else "portrait"
                    val bitmap=instrumentation.uiAutomation.takeScreenshot()
                    File(instrumentation.targetContext.getExternalFilesDir("verification"),"minimal-map-"+suffix+".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
            }
        }
    }
}
