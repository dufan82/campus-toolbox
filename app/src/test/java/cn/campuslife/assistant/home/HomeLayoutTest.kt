package cn.campuslife.assistant.home

import org.junit.Assert.*
import org.junit.Test

class HomeLayoutTest {
    @Test fun removedGateDoesNotReturnAndExistingChoicesSurviveUpgrade() {
        val result=HomeLayout(listOf("planner","gate","map","schedule","watermark"),setOf("gate","map")).normalized()
        assertEquals(listOf("planner","map","schedule","watermark","story","daily60"),result.order)
        assertEquals(setOf("map"),result.hidden)
        assertEquals(listOf("planner","schedule","watermark","story","daily60"),result.visible())
        assertFalse(ToolCatalog.tools.any { it.id=="gate" })
    }
    @Test fun retiredToolsDisappearWhileLegacySurvivorOrderAndHiddenChoiceRemain() {
        val result=HomeLayout(listOf("batch","watermark","favorites","planner","map","schedule"),
            setOf("favorites","batch","planner")).normalized()
        assertEquals(listOf("watermark","planner","map","schedule","story","daily60"),result.order)
        assertEquals(setOf("planner"),result.hidden)
        assertEquals(listOf("watermark","map","schedule","story","daily60"),result.visible())
        assertEquals("倒数日",ToolCatalog.tools.single { it.id=="planner" }.title)
    }
    @Test fun normalizationKeepsOrderAddsNewToolsAndRejectsExpressIds() {
        val result=HomeLayout(listOf("planner","pdd_code","map","planner","gone"),setOf("map","pdd_code" )).normalized()
        assertEquals(listOf("planner","map","schedule","watermark","story","daily60"),result.order)
        assertEquals(setOf("map"),result.hidden)
        assertFalse(result.visible().contains("pdd_code"))
    }
    @Test fun moveAndHideNeverLoseOtherTools() {
        val state=HomeLayout.defaults().move("watermark",-1).hide("map",true)
        assertEquals(listOf("watermark","schedule","planner","story","daily60"),state.visible())
        assertEquals(listOf("map","watermark","schedule","planner","story","daily60"),state.order)
        assertEquals(listOf("map","schedule","watermark","planner","story","daily60"),HomeLayout.defaults().visible())
    }
    @Test fun newStoryPreservesOldOrderAndCanHideRestoreAndMove() {
        val old=HomeLayout(listOf("planner","map","schedule","watermark"),setOf("map"))
        val upgraded=old.normalized();assertEquals(old.order,upgraded.order.take(4));assertEquals(listOf("story","daily60"),upgraded.order.drop(4))
        assertEquals(listOf("map"),upgraded.hidden.toList());assertFalse(upgraded.hide("story",true).visible().contains("story"))
        assertTrue(upgraded.hide("story",true).hide("story",false).visible().contains("story"));assertEquals("story",upgraded.move("story",-4).order.first())
    }
    @Test fun unknownMoveCannotChangeLayoutAndAllHiddenIsAllowed() {
        val state=HomeLayout.defaults()
        assertEquals(state,state.move("pdd_packages",1))
        assertTrue(state.copy(hidden=state.order.toSet()).visible().isEmpty())
    }
    @Test fun daily60AppendsWithoutChangingSavedChoicesAndCanMoveHideRestore() {
        val old=HomeLayout(listOf("story","planner","map","schedule","watermark"),setOf("map","story"))
        val upgraded=old.normalized()
        assertEquals(old.order,upgraded.order.dropLast(1));assertEquals("daily60",upgraded.order.last())
        assertEquals(old.hidden,upgraded.hidden)
        assertFalse("daily60" in upgraded.hide("daily60",true).visible())
        assertTrue("daily60" in upgraded.hide("daily60",true).hide("daily60",false).visible())
        assertEquals("daily60",upgraded.move("daily60",-5).order.first())
        assertFalse(ToolCatalog.tools.any {it.id in setOf("pdd_packages","pdd_code","douyin")})
    }
}
