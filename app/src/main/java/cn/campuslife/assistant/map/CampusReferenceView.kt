package cn.campuslife.assistant.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import cn.campuslife.assistant.ui.Ui

/** Image-only zoom/pan/tap surface. It has no GPS or SDK dependency. */
class CampusReferenceView(context: Context,private val onChoose: (String)->Unit,private val onScaleChanged: (Double)->Unit,
    private val items: List<CampusLandmark> = CampusCatalog.entries): View(context) {
    private val allowedIds=items.map { it.id }.toSet()
    private var bitmap: Bitmap?=null
    private var disposed=false
    private var loading=true
    private var selectedId: String?=null
    private var transform: ReferenceTransform?=null
    private var multiTouch=false
    private val rect=RectF()
    private val imagePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dot=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Ui.blue }
    private val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Ui.blue;style=Paint.Style.STROKE;strokeWidth=Ui.dp(context,3).toFloat() }
    private val halo=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0x550c6b58 }
    private val white=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.WHITE }
    private val messagePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Ui.muted;textSize=Ui.dp(context,15).toFloat() }

    private val scaleGesture=ScaleGestureDetector(context,object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            transform?.zoomAt(detector.scaleFactor.toDouble(),detector.focusX.toDouble(),detector.focusY.toDouble())?.let { update(it) }
            return true
        }
    })
    private val gestures=GestureDetector(context,object: GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent)=true
        override fun onScroll(first: MotionEvent?,last: MotionEvent,dx: Float,dy: Float): Boolean {
            if(!scaleGesture.isInProgress) transform?.pan(-dx.toDouble(),-dy.toDouble())?.let { update(it) }
            return true
        }
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            val frame=transform ?: return true
            if(!multiTouch && bitmap!=null) CampusReference.hit(frame,event.x.toDouble(),event.y.toDouble(),Ui.dp(context,24).toDouble(),allowedIds)?.let { onChoose(it) }
            return true
        }
    })

    init {
        tag="ref_canvas";isClickable=true;isFocusable=true;setBackgroundColor(Ui.pale)
        contentDescription="校园原图对照，可双指缩放或拖动，也可用选择地点和缩放按钮。不是实时定位图。"
        val assets=context.applicationContext.assets
        val handler=Handler(Looper.getMainLooper())
        Thread({
            val decoded=runCatching { assets.open("pingtan-reference.png").use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=2 }) } }.getOrNull()
            handler.post {
                if(disposed) decoded?.recycle() else { bitmap=decoded;loading=false;describe();invalidate() }
            }
        },"campus-reference-image").start()
    }

    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(w>0 && h>0) {
            val fit=ReferenceTransform.fit(w.toDouble(),h.toDouble())
            update(CampusReference.points[selectedId]?.let { fit.focus(it) } ?: fit)
        }
    }

    private fun update(frame: ReferenceTransform) {
        transform=frame
        onScaleChanged(frame.imageWidth/ReferenceTransform.fit(frame.viewWidth,frame.viewHeight).imageWidth)
        invalidate()
    }

    fun select(id: String?) {
        selectedId=id
        val point=CampusReference.points[id]
        if(point!=null) transform?.focus(point)?.let { update(it) } else fit()
        describe()
        invalidate()
    }

    private fun describe() {
        val state=if(loading) "原图加载中。" else if(bitmap!=null) "原图已载入。" else "原图读取失败。"
        val name=items.firstOrNull { it.id==selectedId }?.name
        contentDescription=state+"校园原图对照，不是实时定位图。"+(name?.let { "当前选择：$it。" } ?: "可用选择地点按钮。")
    }

    fun fit() { if(width>0 && height>0) update(ReferenceTransform.fit(width.toDouble(),height.toDouble())) }
    fun zoom(factor: Double) { transform?.zoomAt(factor,width/2.0,height/2.0)?.let { update(it) } }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame=transform ?: return
        val image=bitmap
        if(image==null) { canvas.drawText(if(loading) "正在读取校园原图…" else "校园原图读取失败",Ui.dp(context,16).toFloat(),height/2f,messagePaint);return }
        rect.set(frame.left.toFloat(),frame.top.toFloat(),(frame.left+frame.imageWidth).toFloat(),(frame.top+frame.imageHeight).toFloat())
        canvas.drawBitmap(image,null,rect,imagePaint)
        CampusReference.points.forEach { (id,point) ->
            if(id !in allowedIds) return@forEach
            val screen=frame.screen(point);val x=screen.x.toFloat();val y=screen.y.toFloat()
            if(id==selectedId) {
                canvas.drawCircle(x,y,Ui.dp(context,19).toFloat(),halo)
                canvas.drawCircle(x,y,Ui.dp(context,15).toFloat(),white)
                canvas.drawCircle(x,y,Ui.dp(context,13).toFloat(),ring)
                canvas.drawCircle(x,y,Ui.dp(context,5).toFloat(),dot)
            } else {
                canvas.drawCircle(x,y,Ui.dp(context,5).toFloat(),white)
                canvas.drawCircle(x,y,Ui.dp(context,3).toFloat(),dot)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN) multiTouch=false
        if(event.pointerCount>1) multiTouch=true
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleGesture.onTouchEvent(event);gestures.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun performClick(): Boolean { super.performClick();return true }
    override fun onDetachedFromWindow() {
        disposed=true;bitmap?.recycle();bitmap=null
        super.onDetachedFromWindow()
    }
}
