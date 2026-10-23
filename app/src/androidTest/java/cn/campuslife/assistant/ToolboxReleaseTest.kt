package cn.campuslife.assistant

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.campuslife.assistant.ui.MainActivity
import cn.campuslife.assistant.ui.ProfileActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolboxReleaseTest {
    private fun views(root: View): List<View> = listOf(root)+if(root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    @Test fun threeCouriersAreVisibleAboveTheImageToolOnOpening() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root=activity.window.decorView
                val textViews=views(root).filterIsInstance<TextView>()
                val image=textViews.single { it.text.toString()=="图片工具" }
                val imagePosition=IntArray(2);image.getLocationOnScreen(imagePosition)
                for(label in listOf("拼多多包裹","拼多多身份码","抖音订单")) {
                    val shortcut=textViews.single { it.text.toString()==label }
                    val bounds=Rect();assertTrue("$label must be fully visible",shortcut.getGlobalVisibleRect(bounds))
                    assertTrue(bounds.bottom<imagePosition[1])
                    assertNotNull(root.findViewWithTag<View>("shortcut_${when(label){"拼多多包裹"->"pdd_packages";"拼多多身份码"->"pdd_code";else->"douyin_orders"}}"))
                }
                assertTrue(textViews.none { it.text.toString().contains("菜鸟") || it.text.toString().contains("京东") })
            }
        }
    }

    @Test fun profileOnlyOffersFivePersonalFieldsAndDoesNotEraseLegacyValues() {
        val store=cn.campuslife.assistant.data.LocalStore(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
        val previous=store.profile();val config=store.config()
        store.saveProfile(cn.campuslife.watermark.CampusProfile(mapOf(cn.campuslife.watermark.FieldKey.SCHOOL to "旧资料保留测试")))
        try {
        ActivityScenario.launch(ProfileActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root=activity.window.decorView
                for(key in listOf("COLLEGE","CLASS_NAME","NAME","SEAT_NUMBER","STUDENT_ID")) assertNotNull(root.findViewWithTag<View>("profile_$key"))
                for(key in listOf("SCHOOL","MAJOR","EVENT_NAME","EVENT_LOCATION","NOTE")) assertNull(root.findViewWithTag<View>("profile_$key"))
                root.findViewWithTag<android.widget.EditText>("profile_NAME").setText("资料保存测试")
                views(root).filterIsInstance<android.widget.Button>().single { it.text.toString()=="保存资料" }.performClick()
            }
        }
        assertEquals("旧资料保留测试",store.profile().values[cn.campuslife.watermark.FieldKey.SCHOOL])
        assertEquals("资料保存测试",store.profile().values[cn.campuslife.watermark.FieldKey.NAME])
        } finally {store.saveProfile(previous);store.saveConfig(config)}
    }
}
