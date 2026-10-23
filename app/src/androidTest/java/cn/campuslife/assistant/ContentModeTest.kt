package cn.campuslife.assistant

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.image.PhotoLoader
import cn.campuslife.assistant.ui.*
import cn.campuslife.watermark.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContentModeTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Test fun checkboxSwitchesBothModesAndRemembersTheChoiceWithoutChangingPositionOrNaming() {
        val store=LocalStore(context)
        val profile=store.profile();val details=store.details();val config=store.config();val draft=store.draftName();val beforeNaming=store.namingFields()
        val naming=ExportNaming.defaultFields;store.saveNamingFields(naming)
        val source=File(PhotoLoader(context).draftFolder,"content-mode-fixture.png")
        val image=Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        source.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
        store.saveProfile(CampusProfile(mapOf(FieldKey.CLASS_NAME to "示例班级",FieldKey.NAME to "示例同学",FieldKey.SEAT_NUMBER to "05")))
        store.saveDetails(ActivityDetails());store.saveConfig(EditorConfig(enabled=setOf(FieldKey.CLASS_NAME,FieldKey.NAME,FieldKey.SEAT_NUMBER)))
        store.saveDraftName(source.name)
        try {
            ActivityScenario.launch(WatermarkActivity::class.java).use { scenario ->
                val deadline=SystemClock.elapsedRealtime()+10000
                var ready=false
                while(!ready && SystemClock.elapsedRealtime()<deadline) {
                    scenario.onActivity { ready=it.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview").photo!=null }
                    if(!ready)SystemClock.sleep(40)
                }
                assertTrue("Fixture did not load",ready)
                scenario.onActivity { activity ->
                    val root=activity.window.decorView
                    val toggle=root.findViewWithTag<CheckBox>("content_only")
                    assertNotNull("Only content must be an optional control",toggle)
                    val preview=root.findViewWithTag<WatermarkPreview>("watermark_preview")
                    assertTrue(toggle.isChecked)
                    assertEquals(listOf("示例班级","示例同学","05"),preview.layout!!.lines.map { it.text })
                    preview.onMoved!!.invoke(.7,.2)
                    toggle.performClick()
                    assertEquals(listOf("班级：示例班级","姓名：示例同学","座号：05"),preview.layout!!.lines.map { it.text })
                    assertEquals(.7,preview.config.style.anchorX,.001)
                    assertEquals(.2,preview.config.style.anchorY,.001)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val root=activity.window.decorView
                    assertFalse(root.findViewWithTag<CheckBox>("content_only").isChecked)
                    val preview=root.findViewWithTag<WatermarkPreview>("watermark_preview")
                    assertEquals(listOf("班级：示例班级","姓名：示例同学","座号：05"),preview.layout!!.lines.map { it.text })
                    root.findViewWithTag<CheckBox>("content_only").performClick()
                    assertEquals(listOf("示例班级","示例同学","05"),preview.layout!!.lines.map { it.text })
                }
            }
            assertEquals(naming,store.namingFields())
            assertEquals("示例班级_示例同学_05",ExportNaming.compose(store.profile(),store.details(),naming,"fixture"))
        } finally { store.saveProfile(profile);store.saveDetails(details);store.saveConfig(config);store.saveDraftName(draft);store.saveNamingFields(beforeNaming) }
    }
    @Test fun modePersistsForEditorAndTemplatesAndLegacyMissingFlagDefaultsOn() {
        val name="test_content_mode_store"
        val store=LocalStore(context,name)
        val labeled=EditorConfig(contentOnly=false)
        store.saveConfig(labeled)
        store.saveTemplates(listOf(WatermarkTemplate("mode-test","示例模板",labeled)))
        assertFalse(LocalStore(context,name).config().contentOnly)
        assertFalse(LocalStore(context,name).templates().single().config.contentOnly)
        val prefs=context.getSharedPreferences(name,0)
        val json=org.json.JSONObject(prefs.getString("config",null)!!).apply { remove("contentOnly") }
        assertTrue(prefs.edit().putString("config",json.toString()).commit())
        assertTrue(LocalStore(context,name).config().contentOnly)
    }
}
