package cn.campuslife.assistant.home

import org.junit.Assert.*
import org.junit.Test

class HomeLayoutTest {
    @Test fun retiredToolsDisappearWhileLegacySurvivorOrderAndHiddenChoiceRemain() {
        val result=HomeLayout(listOf("batch","watermark","favorites","planner","map","schedule"),
            setOf("favorites","batch","planner")).normalized()
        assertEquals(listOf("watermark","planner","map","schedule"),result.order)
        assertEquals(setOf("planner"),result.hidden)
        assertEquals(listOf("watermark","map","schedule"),result.visible())
        assertEquals("倒计时",ToolCatalog.tools.single { it.id=="planner" }.title)
    }
    @Test fun normalizationKeepsOrderAddsNewToolsAndRejectsExpressIds() {
        val result=HomeLayout(listOf("planner","pdd_code","map","planner","gone"),setOf("map","pdd_code" )).normalized()
        assertEquals(listOf("planner","map","schedule","watermark"),result.order)
        assertEquals(setOf("map"),result.hidden)
        assertFalse(result.visible().contains("pdd_code"))
    }
    @Test fun moveAndHideNeverLoseOtherTools() {
        val state=HomeLayout.defaults().move("watermark",-1).hide("map",true)
        assertEquals(listOf("watermark","schedule","planner"),state.visible())
        assertEquals(listOf("map","watermark","schedule","planner"),state.order)
        assertEquals(listOf("map","schedule","watermark","planner"),HomeLayout.defaults().visible())
    }
    @Test fun unknownMoveCannotChangeLayoutAndAllHiddenIsAllowed() {
        val state=HomeLayout.defaults()
        assertEquals(state,state.move("pdd_packages",1))
        assertTrue(state.copy(hidden=state.order.toSet()).visible().isEmpty())
    }
}
