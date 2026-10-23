package cn.campuslife.watermark

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class Point(val x: Double, val y: Double)
data class Box(val left: Double, val top: Double, val width: Double, val height: Double) {
    val right get() = left + width
    val bottom get() = top + height
}
data class FontMetrics(val ascent: Double, val descent: Double)
interface TextMeasurer {
    fun width(text: String, fontSize: Double, bold: Boolean): Double
    fun metrics(fontSize: Double, bold: Boolean): FontMetrics
}
data class DrawLine(val text: String, val x: Double, val baseline: Double)
data class WatermarkLayout(val box: Box, val lines: List<DrawLine>, val fontSize: Double, val padding: Double, val reduced: Boolean)

object LayoutEngine {
    fun layout(width: Double, height: Double, texts: List<String>, style: WatermarkStyle, measurer: TextMeasurer): WatermarkLayout {
        require(width > 0 && height > 0 && width.isFinite() && height.isFinite()) { "照片尺寸无效" }
        if (texts.isEmpty()) return WatermarkLayout(Box(0.0, 0.0, 0.0, 0.0), emptyList(), 0.0, 0.0, false)
        val short = min(width, height)
        val margin = short * style.marginRatio.coerceIn(0.0, 0.2)
        val padding = short * style.paddingRatio.coerceIn(0.0, 0.06)
        val maxBoxWidth = min(width - margin * 2, width * style.maxWidthRatio.coerceIn(0.3, 1.0))
        val textWidth = max(1.0, maxBoxWidth - padding * 2)
        val requestedFont = short * style.fontRatio.coerceIn(0.008, 0.1)
        var font = requestedFont
        val paragraphs = if (style.multiline) texts else listOf(texts.joinToString(" · "))
        repeat(80) {
            val lines = paragraphs.flatMap { wrap(it, textWidth, font, style.bold, measurer) }
            val metrics = measurer.metrics(font, style.bold)
            val glyphHeight = metrics.descent - metrics.ascent
            val lineHeight = glyphHeight * style.lineSpacing.coerceIn(1.0, 2.2)
            val boxHeight = padding * 2 + glyphHeight + max(0, lines.size - 1) * lineHeight
            if (boxHeight <= height - margin * 2) {
                val longest = lines.maxOfOrNull { measurer.width(it, font, style.bold) } ?: 0.0
                val boxWidth = min(maxBoxWidth, longest + padding * 2)
                val (ax, ay) = when (style.position) {
                    Position.TOP_LEFT -> 0.0 to 0.0
                    Position.TOP_RIGHT -> 1.0 to 0.0
                    Position.BOTTOM_LEFT -> 0.0 to 1.0
                    Position.BOTTOM_RIGHT -> 1.0 to 1.0
                    Position.BOTTOM_CENTER -> 0.5 to 1.0
                    Position.CUSTOM -> style.anchorX.coerceIn(0.0, 1.0) to style.anchorY.coerceIn(0.0, 1.0)
                }
                val left = margin + ax * max(0.0, width - margin * 2 - boxWidth)
                val top = margin + ay * max(0.0, height - margin * 2 - boxHeight)
                val draw = lines.mapIndexed { index, text ->
                    val free = max(0.0, boxWidth - padding * 2 - measurer.width(text, font, style.bold))
                    val extra = when (style.alignment) { TextAlignment.LEFT -> 0.0; TextAlignment.CENTER -> free / 2; TextAlignment.RIGHT -> free }
                    DrawLine(text, left + padding + extra, top + padding - metrics.ascent + index * lineHeight)
                }
                return WatermarkLayout(Box(left, top, boxWidth, boxHeight), draw, font, padding, font < requestedFont * 0.99)
            }
            font *= 0.92
            require(font >= short * 0.008) { "水印内容过多，请减少显示字段或缩短文字" }
        }
        error("水印内容无法完整放入照片，请减少文字")
    }

    private fun wrap(text: String, maxWidth: Double, font: Double, bold: Boolean, measurer: TextMeasurer): List<String> {
        return text.split('\n').flatMap { paragraph ->
            if (paragraph.isEmpty()) return@flatMap listOf("")
            val iterator = BreakIterator.getCharacterInstance(Locale.CHINA).apply { setText(paragraph) }
            val out = mutableListOf<String>()
            var line = StringBuilder()
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                val glyph = paragraph.substring(start, end)
                if (line.isNotEmpty() && measurer.width(line.toString() + glyph, font, bold) > maxWidth) {
                    out.add(line.toString()); line = StringBuilder()
                }
                require(measurer.width(glyph, font, bold) <= maxWidth) { "文字过大，请减小字号或边距" }
                line.append(glyph)
                start = end; end = iterator.next()
            }
            if (line.isNotEmpty()) out.add(line.toString())
            out
        }
    }
}

data class Viewport(val scale: Double, val offsetX: Double, val offsetY: Double, val imageWidth: Double, val imageHeight: Double) {
    fun toView(x: Double, y: Double) = Point(offsetX + x * scale, offsetY + y * scale)
    fun toSource(x: Double, y: Double) = Point(((x - offsetX) / scale).coerceIn(0.0, imageWidth), ((y - offsetY) / scale).coerceIn(0.0, imageHeight))
    companion object {
        fun fit(imageWidth: Double, imageHeight: Double, viewWidth: Double, viewHeight: Double): Viewport {
            require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0)
            val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
            return Viewport(scale, (viewWidth - imageWidth * scale) / 2, (viewHeight - imageHeight * scale) / 2, imageWidth, imageHeight)
        }
    }
}

data class Orientation(val exif: Int) {
    val swapsAxes get() = exif in 5..8
    fun map(x: Double, y: Double) = when (exif) {
        2 -> Point(1 - x, y)
        3 -> Point(1 - x, 1 - y)
        4 -> Point(x, 1 - y)
        5 -> Point(y, x)
        6 -> Point(1 - y, x)
        7 -> Point(1 - y, 1 - x)
        8 -> Point(y, 1 - x)
        else -> Point(x, y)
    }
}
