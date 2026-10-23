package cn.campuslife.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import cn.campuslife.assistant.image.PhotoAsset
import cn.campuslife.assistant.image.WatermarkRenderer
import cn.campuslife.watermark.*
import kotlin.math.max

class WatermarkPreview(context: Context) : View(context) {
    var photo: PhotoAsset? = null
    var config = EditorConfig()
    var exportFormat=ExportFormat.JPEG
    var layout: WatermarkLayout? = null
        private set
    var failure: String? = null
        private set
    var onMoved: ((Double,Double) -> Unit)? = null
    private var dragOffset: Point? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val whitePaint=Paint().apply { color=android.graphics.Color.WHITE }

    fun update(photo: PhotoAsset?, texts: List<String>, config: EditorConfig) {
        this.photo = photo; this.config = config
        failure = null
        layout = if (photo == null) null else try {
            WatermarkRenderer.layout(photo.width,photo.height,texts,config.style)
        } catch (error: IllegalArgumentException) { failure = error.message; null }
        contentDescription = if (photo == null) "尚未选择照片" else "照片水印实时预览，${photo.width}乘${photo.height}像素，可拖动水印"
        invalidate()
    }
    private fun viewport(photo: PhotoAsset) = Viewport.fit(photo.width.toDouble(),photo.height.toDouble(),max(1,width).toDouble(),max(1,height).toDouble())
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xffe2e8e5.toInt())
        val photo = photo ?: run {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.muted; textSize = Ui.dp(context,16).toFloat(); textAlign=Paint.Align.CENTER }
            canvas.drawText("选一张活动照片，开始记录",width/2f,height/2f,paint)
            return
        }
        val transform = viewport(photo)
        canvas.save()
        canvas.translate(transform.offsetX.toFloat(),transform.offsetY.toFloat())
        canvas.scale(transform.scale.toFloat(),transform.scale.toFloat())
        if(exportFormat==ExportFormat.JPEG) canvas.drawRect(0f,0f,photo.width.toFloat(),photo.height.toFloat(),whitePaint)
        canvas.drawBitmap(photo.preview,null,RectF(0f,0f,photo.width.toFloat(),photo.height.toFloat()),bitmapPaint)
        layout?.let { WatermarkRenderer.drawWatermark(canvas,it,config.style) }
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val photo = photo ?: return false
        val layout = layout?.takeIf { it.lines.isNotEmpty() } ?: return false
        val point = viewport(photo).toSource(event.x.toDouble(),event.y.toDouble())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (point.x !in layout.box.left..layout.box.right || point.y !in layout.box.top..layout.box.bottom) return false
                dragOffset = Point(point.x-layout.box.left,point.y-layout.box.top)
                parent.requestDisallowInterceptTouchEvent(true); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val offset = dragOffset ?: return false
                val margin = minOf(photo.width,photo.height) * config.style.marginRatio
                val x = (point.x-offset.x-margin) / max(1.0,photo.width-margin*2-layout.box.width)
                val y = (point.y-offset.y-margin) / max(1.0,photo.height-margin*2-layout.box.height)
                onMoved?.invoke(x.coerceIn(0.0,1.0),y.coerceIn(0.0,1.0)); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragOffset=null; parent.requestDisallowInterceptTouchEvent(false); performClick(); return true
            }
        }
        return dragOffset != null
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
