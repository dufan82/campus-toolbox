package cn.campuslife.assistant

import android.graphics.Rect
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.ui.MainActivity
import cn.campuslife.assistant.ui.SettingsActivity
import cn.campuslife.assistant.ui.Ui
import org.junit.Assert.*
import org.junit.Test

/** Only local screens; never clicks a real express link, school page or update request. */
class RedesignHomeTest {
    @Test fun primaryAndReadingColorsHaveReadableContrast() {
        assertTrue(ColorUtils.calculateContrast(android.graphics.Color.WHITE,Ui.blue)>=4.5)
        assertTrue(ColorUtils.calculateContrast(Ui.ink,Ui.background)>=7.0)
        assertTrue(ColorUtils.calculateContrast(Ui.muted,android.graphics.Color.WHITE)>=4.5)
    }
    @Test fun fixedExpressAndMainDestinationsRemainSeparate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { a ->
                val root=a.window.decorView
                for(id in listOf("pdd_packages","pdd_code","douyin_orders")) {
                    val tile=root.findViewWithTag<View>("shortcut_$id");assertNotNull(tile)
                    val rect=Rect();assertTrue(tile.getGlobalVisibleRect(rect));assertEquals(tile.height,rect.height())
                    assertTrue(tile.isClickable)
                }
                for(id in listOf("home","schedule","settings")) assertNotNull(root.findViewWithTag<View>("bottom_nav_$id"))
                for(id in listOf("gate","favorites","batch")) assertNull(root.findViewWithTag<View>("home_tool_$id"))
            }
        }
    }
    @Test fun settingsOffersLocalToolsAndExistingUpdateEntry() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                for(id in listOf("profile","layout","express","update","privacy","about")) {
                    val row=a.window.decorView.findViewWithTag<View>("settings_$id")
                    assertNotNull(row);assertTrue(row.isEnabled && row.isClickable)
                }
                assertTrue(a.window.decorView.findViewWithTag<View>("bottom_nav_settings").isSelected)
            }
        }
    }
}
