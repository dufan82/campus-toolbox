package cn.campuslife.assistant

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.map.*
import cn.campuslife.assistant.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test

class CampusCustomPlaceFlowTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    private fun find(text: String,description: Boolean=false): AccessibilityNodeInfo {
        fun locate(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(node==null) return null
            if(node.isVisibleToUser && (if(description) node.contentDescription else node.text)?.toString()==text) return node
            for(index in 0 until node.childCount) locate(node.getChild(index))?.let { return it }
            return null
        }
        val deadline=SystemClock.uptimeMillis()+5000
        do {
            instrumentation.waitForIdleSync()
            locate(instrumentation.uiAutomation.rootInActiveWindow)?.let { return it }
            SystemClock.sleep(40)
        } while(SystemClock.uptimeMillis()<deadline)
        fun details(node: AccessibilityNodeInfo?): String {
            if(node==null) return "no root"
            return node.text.toString()+" "+node.contentDescription+" "+(0 until node.childCount).joinToString { details(node.getChild(it)) }
        }
        throw AssertionError("Missing custom-place control: $text; ${details(instrumentation.uiAutomation.rootInActiveWindow)}")
    }
    private fun tap(text: String) {
        instrumentation.uiAutomation.waitForIdle(150,5000)
        val bounds=Rect();find(text).getBoundsInScreen(bounds)
        assertFalse("Empty action bounds: $text",bounds.isEmpty)
        val time=SystemClock.uptimeMillis()
        val down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0)
        val up=MotionEvent.obtain(time,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0)
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(down,true));assertTrue(instrumentation.uiAutomation.injectInputEvent(up,true)) }
        finally { down.recycle();up.recycle() }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(150,5000)
    }
    private fun name(value: String) {
        assertTrue(find("地点名称",true).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        }))
        instrumentation.waitForIdleSync()
    }
    private fun scene(onUpdated: (CampusCoordinateData)->Unit = {},block: (ActivityScenario<MainActivity>,CampusMapMaintenance,CampusCoordinateStore)->Unit) {
        val prefs=context.getSharedPreferences("campus_coordinate_draft",0)
        val old=prefs.getString("public_coordinates_v1",null)
        try {
            prefs.edit().remove("public_coordinates_v1").commit()
            val store=CampusCoordinateStore(context)
            val bundled=store.load()
            assertTrue(store.save(bundled.copy(records=bundled.records.filterNot { it.id.startsWith("custom-") },customPlaces=emptyList())))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var maintenance: CampusMapMaintenance
                scenario.onActivity { activity -> maintenance=CampusMapMaintenance(activity,store,store.load(),onUpdated,{});maintenance.menu() }
                block(scenario,maintenance,store)
            }
        } finally { prefs.edit().apply { if(old==null) remove("public_coordinates_v1") else putString("public_coordinates_v1",old) }.commit() }
    }

    @Test fun addMarkRenameAndDeleteACustomEntranceThroughRealControls() = scene { scenario,maintenance,store ->
        tap("新增自定义地点");name("实训2楼北门");tap("保存并标位置")
        val id=store.load().customPlaces.single().id
        assertEquals(id,maintenance.target?.id)
        assertNull(store.load().points[id])
        val point=GeoPoint(25.5502,119.7514)
        scenario.onActivity { maintenance.review(point) };tap("确认并保存")
        assertEquals(point,store.load().points[id])
        scenario.onActivity { maintenance.menu() };tap("管理自定义地点");tap("实训2楼北门");tap("修改名称和分类")
        name("实训2楼南门");tap("保存修改")
        assertEquals(point,store.load().points[id])
        assertEquals(id,store.load().search("实训二楼南门",MapCategory.ALL).single().id)
        scenario.onActivity { maintenance.menu() };tap("管理自定义地点");tap("实训2楼南门");tap("删除自定义地点");tap("取消")
        assertEquals(1,store.load().customPlaces.size)
        scenario.onActivity { maintenance.menu() };tap("管理自定义地点");tap("实训2楼南门");tap("删除自定义地点");tap("确认删除")
        assertTrue(store.load().customPlaces.isEmpty())
        assertFalse(store.load().points.containsKey(id))
        assertTrue(store.load().points.containsKey("canteen-1"))
    }

    @Test fun duplicateNameShowsAnInlineErrorAndCancelWritesNothing() = scene { _,_,store ->
        val before=store.load()
        tap("新增自定义地点");name("学生宿舍一号楼");tap("保存并标位置")
        assertNotNull(find("地点名称",true).error)
        assertEquals(before,store.load())
        tap("取消")
        assertEquals(before,store.load())
    }

    @Test fun cancellingAMoveKeepsTheOldPointAndConfirmedMovePreservesMetadata() = scene { scenario,maintenance,store ->
        tap("新增自定义地点");name("实训3楼北门");tap("保存并标位置")
        val id=store.load().customPlaces.single().id
        val original=GeoPoint(25.5502,119.7514)
        scenario.onActivity { maintenance.review(original) };tap("确认并保存")
        scenario.onActivity { maintenance.menu() };tap("管理自定义地点");tap("实训3楼北门");tap("调整位置")
        assertEquals("Moving must select this custom ID",id,maintenance.target?.id)
        val moved=GeoPoint(25.5503,119.7515)
        scenario.onActivity { maintenance.review(moved) };tap("取消")
        assertEquals(original,store.load().points[id])
        assertEquals("Cancelling must keep the review target",id,maintenance.target?.id)
        scenario.onActivity { maintenance.review(moved) };tap("确认并保存")
        assertEquals(moved,store.load().points[id])
        assertEquals("实训3楼北门",store.load().customPlaces.single().name)
        assertEquals(9,store.load().hiddenIds.size)
    }

    @Test fun aMapRefreshFailureDoesNotUndoASuccessfullyStoredCustomPlace() = scene(onUpdated={ throw IllegalStateException("Injected map refresh failure") }) { _,maintenance,store ->
        tap("新增自定义地点");name("实训6楼北门");tap("保存并标位置")
        val saved=store.load().customPlaces.single()
        assertEquals("Saved metadata must still enter marking mode",saved.id,maintenance.target?.id)
    }

    @Test fun editorStateRestoresTypedNameWithoutPrematureSaving() = scene { scenario,maintenance,store ->
        tap("新增自定义地点");name("体育馆北门")
        val state=Bundle()
        scenario.onActivity { maintenance.saveState(state) }
        tap("取消")
        scenario.onActivity { maintenance.restoreState(state) }
        assertEquals("体育馆北门",find("地点名称",true).text.toString())
        assertTrue(store.load().customPlaces.isEmpty())
        tap("取消")
    }
}
