package cn.campuslife.assistant.image

import android.graphics.*
import cn.campuslife.watermark.*

object WatermarkRenderer {
    private fun paint(font: Double, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = font.toFloat(); typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    fun layout(width: Int, height: Int, texts: List<String>, style: WatermarkStyle) =
        LayoutEngine.layout(width.toDouble(), height.toDouble(), texts, style, object : TextMeasurer {
            override fun width(text: String, fontSize: Double, bold: Boolean) = paint(fontSize, bold).measureText(text).toDouble()
            override fun metrics(fontSize: Double, bold: Boolean): cn.campuslife.watermark.FontMetrics {
                val metrics = paint(fontSize, bold).fontMetrics
                return cn.campuslife.watermark.FontMetrics(metrics.ascent.toDouble(), metrics.descent.toDouble())
            }
        })

    fun drawWatermark(canvas: Canvas, layout: WatermarkLayout, style: WatermarkStyle) {
        if (layout.lines.isEmpty()) return
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.backgroundColor; alpha = (style.backgroundOpacity.coerceIn(0.0, 1.0) * 255).toInt()
        }
        val box = layout.box
        val radius = (layout.padding * 0.35).toFloat()
        canvas.drawRoundRect(RectF(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()), radius, radius, background)
        val textPaint = paint(layout.fontSize, style.bold).apply { color = style.textColor }
        layout.lines.forEach { canvas.drawText(it.text, it.x.toFloat(), it.baseline.toFloat(), textPaint) }
    }

    fun drawOriented(canvas: Canvas, bitmap: Bitmap, exif: Int, targetWidth: Int, targetHeight: Int) {
        val orientation = Orientation(exif)
        val corners = listOf(orientation.map(0.0, 0.0), orientation.map(1.0, 0.0), orientation.map(0.0, 1.0))
        val destination = corners.flatMap { listOf((it.x * targetWidth).toFloat(), (it.y * targetHeight).toFloat()) }.toFloatArray()
        val source = floatArrayOf(0f, 0f, bitmap.width.toFloat(), 0f, 0f, bitmap.height.toFloat())
        val matrix = Matrix().apply { check(setPolyToPoly(source, 0, destination, 0, 3)) }
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
}
