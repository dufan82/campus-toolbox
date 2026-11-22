package cn.campuslife.assistant.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import cn.campuslife.assistant.image.WatermarkRenderer
import cn.campuslife.assistant.ui.Ui
import cn.campuslife.watermark.Position
import cn.campuslife.watermark.WatermarkStyle
import kotlin.math.min

/** Layout is calculated in still-image pixels, before any scaling to the viewfinder. */
internal class CameraStampDrawing(private val frame: CameraSize, stamp: CameraStamp) {
    private val unit = min(frame.width, frame.height) / 1000f
    private val margin = unit * 34
    private val padding = unit * 29
    private val clock = stamp.clock
    private val meta = listOf(stamp.date, stamp.weekday).filter { it.isNotEmpty() }
    private val clockInk = WatermarkRenderer.textPaint((unit * 116).toDouble(), true).apply { color = Color.WHITE }
    private val metaInk = WatermarkRenderer.textPaint((unit * 40).toDouble(), true).apply { color = Color.WHITE }
    private val backing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xbd172b49.toInt() }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.teal }
    private val clockWidth = clockInk.measureText(clock)
    private val metaWidth = meta.maxOfOrNull { metaInk.measureText(it) } ?: 0f
    private val dividerGap = if (clock.isNotEmpty() && meta.isNotEmpty()) unit * 51 else 0f
    private val clockHeight = if (clock.isEmpty()) 0f else clockInk.fontMetrics.height
    private val metaStep = metaInk.fontMetrics.height * 1.17f
    private val metaHeight = if (meta.isEmpty()) 0f else metaInk.fontMetrics.height + (meta.size-1)*metaStep
    private val headerHeight = maxOf(clockHeight, metaHeight)
    private val bodyStyle = WatermarkStyle(fontRatio = .037, marginRatio = 0.0,
        position = Position.TOP_LEFT, lineSpacing = 1.18, maxWidthRatio = .82,
        backgroundOpacity = 0.0, paddingRatio = 0.0)
    private val body = WatermarkRenderer.layout(frame.width, frame.height, stamp.lines, bodyStyle)
    private val rowGap = if (headerHeight > 0 && body.lines.isNotEmpty()) unit * 12 else 0f
    private val panelWidth = padding * 2 + maxOf(clockWidth + dividerGap + metaWidth, body.box.width.toFloat())
    private val panelHeight = padding * 2 + headerHeight + rowGap + body.box.height.toFloat()
    private val fit = minOf(1f, (frame.width - margin * 2) / panelWidth, (frame.height - margin * 2) / panelHeight)
    private val moveX = (frame.width - margin*2 - panelWidth*fit).coerceAtLeast(0f)
    private val moveY = (frame.height - margin*2 - panelHeight*fit).coerceAtLeast(0f)

    fun bounds(stamp: CameraStamp): RectF {
        val (x, y) = when (stamp.position) {
            Position.TOP_LEFT -> 0.0 to 0.0
            Position.TOP_RIGHT -> 1.0 to 0.0
            Position.BOTTOM_LEFT -> 0.0 to 1.0
            Position.BOTTOM_RIGHT -> 1.0 to 1.0
            Position.BOTTOM_CENTER -> 0.5 to 1.0
            Position.CUSTOM -> stamp.anchorX to stamp.anchorY
        }
        val left = margin + x.coerceIn(0.0, 1.0).toFloat() * moveX
        val top = margin + y.coerceIn(0.0, 1.0).toFloat() * moveY
        return RectF(left, top, left + panelWidth*fit, top + panelHeight*fit)
    }

    fun anchor(left: Float, top: Float): Pair<Double, Double> =
        ((left - margin) / maxOf(moveX, 1f)).toDouble().coerceIn(0.0, 1.0) to
            ((top - margin) / maxOf(moveY, 1f)).toDouble().coerceIn(0.0, 1.0)

    fun draw(canvas: Canvas, stamp: CameraStamp) {
        if (stamp.isEmpty) return
        val box = bounds(stamp)
        canvas.save()
        canvas.translate(box.left, box.top)
        canvas.scale(fit, fit)
        canvas.drawRoundRect(0f, 0f, panelWidth, panelHeight, unit * 21, unit * 21, backing)
        if (clock.isNotEmpty()) {
            canvas.drawText(clock, padding, padding + (headerHeight - clockHeight) / 2 - clockInk.ascent(), clockInk)
        }
        if (dividerGap > 0) {
            val x = padding + clockWidth + dividerGap / 2
            canvas.drawRoundRect(x - 3 * unit, padding + 10 * unit, x + 3 * unit,
                padding + headerHeight - 10 * unit, unit * 3, unit * 3, accent)
        }
        val metaLeft = padding + clockWidth + dividerGap
        val metaTop = padding + (headerHeight - metaHeight) / 2
        meta.forEachIndexed { i, text ->
            canvas.drawText(text, metaLeft, metaTop - metaInk.ascent() + i * metaStep, metaInk)
        }
        canvas.translate(padding, padding + headerHeight + rowGap)
        WatermarkRenderer.drawWatermark(canvas, body, bodyStyle)
        canvas.restore()
    }
}

private val Paint.FontMetrics.height get() = descent - ascent

/** Same drawing class as the full-resolution photo exporter. No camera preview screenshots. */
internal class CameraStampView(context: Context) : View(context) {
    private var output: CameraSize? = null
    private var stamp: CameraStamp? = null
    private var drawing: CameraStampDrawing? = null
    private var drag: Pair<Float, Float>? = null
    var onMove: ((Double, Double, Boolean) -> Unit)? = null

    fun show(frame: CameraSize?, value: CameraStamp?) {
        val old = stamp
        if (frame == output && old == value) return
        val sameContent = old?.copy(position = value?.position ?: Position.BOTTOM_LEFT,
            anchorX = value?.anchorX ?: 0.0, anchorY = value?.anchorY ?: 1.0) == value
        if (frame != output || !sameContent) {
            drawing = if (frame != null && value != null && !value.isEmpty) CameraStampDrawing(frame, value) else null
        }
        stamp = value
        output = frame
        contentDescription = value?.readable?.takeIf { it.isNotEmpty() }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val photo = output ?: return
        val value = stamp ?: return
        canvas.save()
        canvas.scale(width.toFloat() / photo.width, height.toFloat() / photo.height)
        drawing?.draw(canvas, value)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val move = onMove ?: return false
        val photo = output ?: return false
        val value = stamp ?: return false
        val layout = drawing ?: return false
        if (width <= 0 || height <= 0) return false
        val x = event.x * photo.width / width
        val y = event.y * photo.height / height
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val box = layout.bounds(value)
                if (!box.contains(x, y)) return false
                drag = (x - box.left) to (y - box.top)
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val (dx, dy) = drag ?: return false
                val (ax, ay) = layout.anchor(x - dx, y - dy)
                val up = event.actionMasked == MotionEvent.ACTION_UP
                move(ax, ay, up)
                if (up) { drag = null; performClick() }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { drag = null; return true }
        }
        return drag != null
    }
    override fun performClick(): Boolean = super.performClick()
}
