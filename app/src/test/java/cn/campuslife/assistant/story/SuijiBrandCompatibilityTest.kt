package cn.campuslife.assistant.story

import cn.campuslife.assistant.home.HomeLayout
import cn.campuslife.assistant.home.ToolCatalog
import org.junit.Assert.*
import org.junit.Test

class SuijiBrandCompatibilityTest {
    @Test fun visibleNameChangesButTheExistingToolIdAndUserLayoutDoNot() {
        val before=HomeLayout(listOf("planner","story","map","watermark","schedule"),setOf("story"))
        assertEquals("随记",ToolCatalog.tools.single {it.id=="story"}.title)
        assertEquals(before,before.normalized())
        assertFalse("story" in before.visible())
        assertTrue("story" in before.hide("story",false).visible())
    }
}
