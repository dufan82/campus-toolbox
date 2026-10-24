package cn.campuslife.assistant.map

import org.junit.Assert.*
import org.junit.Test

class MapLabelLayoutTest {
    @Test fun selectedLabelWinsOverNearbyOverlapsAndUnrelatedNamesRemain() {
        val boxes=listOf(MapLabelBox("ordinary",10.0,10.0,80.0,30.0),MapLabelBox("selected",40.0,12.0,100.0,32.0,100),MapLabelBox("far",110.0,10.0,180.0,30.0))
        assertEquals(setOf("selected","far"),MapLabelLayout.select(boxes,200.0,100.0,10))
        assertEquals(setOf("selected"),MapLabelLayout.select(boxes,200.0,100.0,1))
    }
    @Test fun equalPriorityIsStableAcrossInputOrderAndDoesNotExceedBudget() {
        val boxes=listOf(MapLabelBox("c",110.0,10.0,180.0,30.0),MapLabelBox("b",10.0,10.0,80.0,30.0),MapLabelBox("a",10.0,10.0,80.0,30.0))
        assertEquals(setOf("a","c"),MapLabelLayout.select(boxes,200.0,100.0,2))
        assertEquals(MapLabelLayout.select(boxes,200.0,100.0,2),MapLabelLayout.select(boxes.reversed(),200.0,100.0,2))
        assertTrue(MapLabelLayout.select(boxes,200.0,100.0,0).isEmpty())
    }
    @Test fun clippedAndInvalidBoxesAreNotShownAsReadableLabels() {
        val boxes=listOf(MapLabelBox("off",-10.0,10.0,50.0,30.0),MapLabelBox("nan",Double.NaN,10.0,50.0,30.0),
            MapLabelBox("inverted",50.0,10.0,10.0,30.0),MapLabelBox("valid",10.0,10.0,50.0,30.0))
        assertEquals(setOf("valid"),MapLabelLayout.select(boxes,100.0,100.0,10))
    }
    @Test fun zoomedOutViewsShowFewerNamesButSmallSearchResultsRemainReadable() {
        assertEquals(6,MapLabelLayout.budget(16.0,34))
        assertEquals(12,MapLabelLayout.budget(17.0,34))
        assertEquals(40,MapLabelLayout.budget(18.0,34))
        assertEquals(3,MapLabelLayout.budget(15.0,3))
    }
}
