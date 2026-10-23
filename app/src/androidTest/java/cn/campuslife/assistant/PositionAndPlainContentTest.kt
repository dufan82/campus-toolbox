package cn.campuslife.assistant

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.ui.*
import cn.campuslife.watermark.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PositionAndPlainContentTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    private fun legacyStore(name: String,style: WatermarkStyle): LocalStore {
        val store=LocalStore(context,name)
        store.saveConfig(EditorConfig(style=style))
        val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
        val json=JSONObject(prefs.getString("config",null)!!).put("version",1)
        assertTrue(prefs.edit().putString("config",json.toString()).commit())
        return LocalStore(context,name)
    }
    @Test fun legacyUntouchedDefaultMovesRightButExplicitNewLeftStaysLeft() {
        val legacy=legacyStore("position_legacy_default",WatermarkStyle(position=Position.BOTTOM_LEFT))
        assertEquals(Position.BOTTOM_RIGHT,legacy.config().style.position)
        val left=legacy.config().copy(style=legacy.config().style.copy(position=Position.BOTTOM_LEFT))
        legacy.saveConfig(left)
        assertEquals(Position.BOTTOM_LEFT,LocalStore(context,"position_legacy_default").config().style.position)
    }
    @Test fun legacyDraggedOrRestyledPositionsAreNotReset() {
        val dragged=WatermarkStyle(position=Position.CUSTOM,anchorX=.27,anchorY=.62)
        assertEquals(dragged,legacyStore("position_legacy_custom",dragged).config().style)
        val styled=WatermarkStyle(position=Position.BOTTOM_LEFT,backgroundOpacity=.31)
        assertEquals(styled,legacyStore("position_legacy_style",styled).config().style)
    }
    @Test fun legacyTemplatesKeepTheirOriginalPosition() {
        val name="position_legacy_template"
        val store=legacyStore(name,WatermarkStyle(position=Position.BOTTOM_LEFT))
        val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
        val config=JSONObject(prefs.getString("config",null)!!)
        val templates=org.json.JSONArray().put(JSONObject().put("id","legacy").put("name","旧模板").put("config",config))
        assertTrue(prefs.edit().putString("templates",templates.toString()).commit())
        assertEquals(Position.BOTTOM_LEFT,store.templates().single().config.style.position)
        assertEquals(Position.BOTTOM_RIGHT,store.config().style.position)
    }
    @Test fun positionPickerUpdatesPreviewAndSurvivesRecreationWithoutResettingFields() {
        val store=LocalStore(context);val previous=store.config();val profile=store.profile()
        store.saveProfile(CampusProfile(mapOf(FieldKey.NAME to "示例同学")))
        store.saveConfig(EditorConfig(enabled=setOf(FieldKey.NAME)))
        try {
            ActivityScenario.launch(WatermarkActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val root=activity.window.decorView
                    val button=root.findViewWithTag<Button>("watermark_position")
                    assertNotNull("Position choice belongs below the field chips",button)
                    button.requestRectangleOnScreen(Rect(0,0,button.width,button.height),true)
                    button.performClick()
                }
                instrumentation.waitForIdleSync()
                val root=instrumentation.uiAutomation.rootInActiveWindow
                val topLeft=root.findAccessibilityNodeInfosByText("左上").single()
                assertTrue(topLeft.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val preview=activity.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview")
                    assertEquals(Position.TOP_LEFT,preview.config.style.position)
                    assertEquals(setOf(FieldKey.NAME),preview.config.enabled)
                    preview.onMoved!!.invoke(.6,.3)
                    assertTrue(activity.window.decorView.findViewWithTag<Button>("watermark_position").text.contains("自由移动"))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val preview=activity.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview")
                    assertEquals(Position.CUSTOM,preview.config.style.position)
                    assertEquals(.6,preview.config.style.anchorX,.001)
                    assertEquals(.3,preview.config.style.anchorY,.001)
                }
            }
        } finally { store.saveConfig(previous);store.saveProfile(profile) }
    }
}
