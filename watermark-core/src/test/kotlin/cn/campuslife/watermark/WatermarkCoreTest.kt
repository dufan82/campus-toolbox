package cn.campuslife.watermark

import org.junit.Assert.*
import org.junit.Test

class WatermarkCoreTest {
    private val metrics = object : TextMeasurer {
        override fun width(text: String, fontSize: Double, bold: Boolean) = text.codePointCount(0, text.length) * fontSize
        override fun metrics(fontSize: Double, bold: Boolean) = FontMetrics(-fontSize * 0.8, fontSize * 0.2)
    }

    @Test fun emptyAndHiddenIdentityAreNeverPrinted() {
        val profile = CampusProfile(mapOf(FieldKey.NAME to " 张同学 ", FieldKey.STUDENT_ID to "202600012345"))
        val config = EditorConfig(enabled = setOf(FieldKey.NAME, FieldKey.SEAT_NUMBER))
        assertEquals(listOf("张同学"), formattedFields(profile, ActivityDetails(), config))
    }

    @Test fun selectedOrderIsThePrintedOrder() {
        val profile = CampusProfile(mapOf(FieldKey.NAME to "李同学", FieldKey.CLASS_NAME to "数媒1班"))
        val config = EditorConfig(order = listOf(FieldKey.NAME, FieldKey.CLASS_NAME), enabled = setOf(FieldKey.NAME, FieldKey.CLASS_NAME))
        assertEquals(listOf("李同学", "数媒1班"), formattedFields(profile, ActivityDetails(), config))
    }

    @Test fun defaultTemplatesDoNotRevealStudentIdOrInventIdentity() {
        assertEquals(5, BuiltinTemplates.all.size)
        BuiltinTemplates.all.forEach {
            assertFalse(it.config.enabled.contains(FieldKey.STUDENT_ID))
            assertTrue(formattedFields(CampusProfile(), ActivityDetails(), it.config).isEmpty())
        }
    }

    @Test fun templateUsesTheCurrentProfileNotAnotherPersonsValues() {
        val template = BuiltinTemplates.all.first()
        val values = formattedFields(CampusProfile(mapOf(FieldKey.NAME to "当前用户")), ActivityDetails(), template.config)
        assertEquals(listOf("当前用户"), values)
    }

    @Test fun fitViewportAccountsForLetterboxAndRoundTrips() {
        val viewport = Viewport.fit(4000.0, 2000.0, 400.0, 400.0)
        assertEquals(Point(0.0, 100.0), viewport.toView(0.0, 0.0))
        assertEquals(Point(2000.0, 1000.0), viewport.toSource(200.0, 200.0))
        assertEquals(Point(4000.0, 2000.0), viewport.toSource(400.0, 300.0))
    }

    @Test fun emptyFieldsMakeAnEmptyLayout() {
        assertTrue(LayoutEngine.layout(1200.0, 800.0, emptyList(), WatermarkStyle(), metrics).lines.isEmpty())
    }

    @Test fun everyPresetFitsLandscapePortraitAndSquare() {
        for ((width, height) in listOf(4000.0 to 3000.0, 2400.0 to 4800.0, 1000.0 to 1000.0)) {
            for (position in Position.entries) {
                val layout = LayoutEngine.layout(width, height, listOf("学院：数字媒体学院", "姓名：同学"), WatermarkStyle(position = position), metrics)
                assertTrue(layout.box.left >= 0 && layout.box.top >= 0)
                assertTrue(layout.box.right <= width + 0.001 && layout.box.bottom <= height + 0.001)
                assertTrue(layout.lines.all { it.baseline >= layout.box.top && it.baseline <= layout.box.bottom })
            }
        }
    }

    @Test fun rightBottomAnchorUsesMeasuredBoxNotPreviewPixels() {
        val style = WatermarkStyle(position = Position.BOTTOM_RIGHT)
        val layout = LayoutEngine.layout(4000.0, 3000.0, listOf("活动：测试"), style, metrics)
        assertEquals(4000.0 - 3000 * style.marginRatio, layout.box.right, 0.001)
        assertEquals(3000.0 - 3000 * style.marginRatio, layout.box.bottom, 0.001)
    }

    @Test fun longUnbrokenStudentIdIsWrappedWithoutLosingCharacters() {
        val text = "学号：" + "1234567890".repeat(8)
        val layout = LayoutEngine.layout(900.0, 1400.0, listOf(text), WatermarkStyle(), metrics)
        assertTrue(layout.lines.size > 1)
        assertEquals(text, layout.lines.joinToString("") { it.text })
        assertTrue(layout.lines.all { metrics.width(it.text, layout.fontSize, false) <= layout.box.width - layout.padding * 2 + 0.001 })
    }

    @Test fun longChineseNamesAndEmojiAreNotTruncatedOrSplit() {
        val text = "学院：" + "数字产业与人工智能学院🌱".repeat(5)
        val layout = LayoutEngine.layout(1000.0, 1600.0, listOf(text), WatermarkStyle(), metrics)
        assertEquals(text, layout.lines.joinToString("") { it.text })
        assertFalse(layout.lines.any { it.text.firstOrNull()?.isLowSurrogate() == true || it.text.lastOrNull()?.isHighSurrogate() == true })
    }

    @Test fun singleLineModeFlowsFieldsIntoTheSameParagraph() {
        val layout = LayoutEngine.layout(4000.0, 3000.0, listOf("姓名：甲", "座号：8"), WatermarkStyle(multiline = false, fontRatio = 0.02), metrics)
        assertEquals("姓名：甲 · 座号：8", layout.lines.single().text)
    }

    @Test fun customPositionIsClampedInsideBounds() {
        val layout = LayoutEngine.layout(800.0, 600.0, listOf("姓名：甲"), WatermarkStyle(position = Position.CUSTOM, anchorX = 9.0, anchorY = -3.0), metrics)
        assertEquals(800.0 - 600 * 0.028, layout.box.right, 0.01)
        assertEquals(600 * 0.028, layout.box.top, 0.01)
    }

    @Test fun allExifOrientationsMapKnownCornerAndSwapAxes() {
        val expected = listOf(Point(0.0,0.0), Point(1.0,0.0), Point(1.0,1.0), Point(0.0,1.0), Point(0.0,0.0), Point(1.0,0.0), Point(1.0,1.0), Point(0.0,1.0))
        for (value in 1..8) {
            assertEquals(expected[value - 1], Orientation(value).map(0.0, 0.0))
            assertEquals(value >= 5, Orientation(value).swapsAxes)
        }
        assertEquals(Point(0.0,1.0), Orientation(6).map(1.0,1.0))
        assertEquals(Point(1.0,0.0), Orientation(8).map(1.0,1.0))
    }

    @Test fun exportKeepsOriginalOrientedResolutionAndFormat() {
        val plan = ExportPolicy.plan(4000, 3000, 6, ExportFormat.PNG, 256_000_000)
        assertEquals(3000, plan.width)
        assertEquals(4000, plan.height)
        assertEquals("image/png", plan.mimeType)
        assertEquals("png", plan.extension)
        assertEquals(100, plan.quality)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedExportIsRejectedBeforeBitmapAllocation() {
        ExportPolicy.plan(12000, 10000, 1, ExportFormat.JPEG, 256_000_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun lowMemoryExportIsRejectedWithoutSilentlyReducingResolution() {
        ExportPolicy.plan(6000, 4000, 1, ExportFormat.JPEG, 64_000_000)
    }

    @Test fun explicitSmallerOutputFitsMemoryAndKeepsOrientedAspectRatio() {
        val plan=ExportPolicy.plan(8000,6000,6,ExportFormat.JPEG,160_000_000,maxEdge=4000)
        assertEquals(3000,plan.width); assertEquals(4000,plan.height)
        assertEquals(2,plan.sampleSize)
        assertTrue(plan.workingBytes<=160_000_000)
    }
}
