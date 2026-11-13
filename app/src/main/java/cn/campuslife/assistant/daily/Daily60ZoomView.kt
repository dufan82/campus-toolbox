package cn.campuslife.assistant.daily

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import kotlin.math.min

/** Draws the already-bounded preview in-process; no intent, URI, WebView or new bitmap allocation. */
internal class Daily60ZoomView(context:Context,private val bitmap:Bitmap,private val restored:Frame?=null):View(context) {
    data class Frame(val zoom:Float,val centerX:Float,val centerY:Float)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect=RectF()
    private var base=1f;private var scale=1f;private var left=0f;private var top=0f
    private var ready=false
    internal val zoom get()=scale/base
    private val pinch=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(detector:ScaleGestureDetector):Boolean {zoomAt(detector.scaleFactor,detector.focusX,detector.focusY);return true}
    })
    private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
        override fun onDown(event:MotionEvent)=true
        override fun onScroll(first:MotionEvent?,last:MotionEvent,dx:Float,dy:Float):Boolean {
            if(!pinch.isInProgress){left-=dx;top-=dy;clamp()};return true
        }
        override fun onDoubleTap(event:MotionEvent):Boolean {
            val fitWidth=width.toFloat()/bitmap.width
            if(scale>fitWidth*1.4f)fit() else zoomAt((fitWidth*2)/scale,event.x,event.y)
            return true
        }
    })
    init {tag="daily60_zoom";isClickable=true;isFocusable=true;setBackgroundColor(android.graphics.Color.WHITE)}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        if(w<=0 || h<=0)return
        val frame=if(ready)frame() else restored
        base=min(w.toFloat()/bitmap.width,h.toFloat()/bitmap.height)
        if(frame==null)fit() else {
            scale=(base*frame.zoom).coerceIn(base,maxScale())
            left=w/2f-frame.centerX*bitmap.width*scale;top=h/2f-frame.centerY*bitmap.height*scale;clamp()
        }
        ready=true
    }
    private fun maxScale()=maxOf(base,width.toFloat()/bitmap.width)*6
    fun frame()=Frame(zoom,(width/2f-left)/(bitmap.width*scale),(height/2f-top)/(bitmap.height*scale))
    fun fit() {scale=(width.toFloat()/bitmap.width).coerceAtLeast(base);left=(width-bitmap.width*scale)/2;top=0f;clamp()}
    fun zoomBy(factor:Float) {zoomAt(factor,width/2f,height/2f)}
    private fun zoomAt(factor:Float,x:Float,y:Float) {
        if(!factor.isFinite() || factor<=0f)return
        val next=(scale*factor).coerceIn(base,maxScale());val ratio=next/scale
        left=x-(x-left)*ratio;top=y-(y-top)*ratio;scale=next;clamp()
    }
    private fun clamp() {
        val w=bitmap.width*scale;val h=bitmap.height*scale
        left=if(w<=width)(width-w)/2 else left.coerceIn(width-w,0f)
        top=if(h<=height)(height-h)/2 else top.coerceIn(height-h,0f)
        contentDescription="图片，缩放 "+String.format(Locale.ROOT,"%.0f",zoom*100)+"%，可双指缩放或拖动"
        invalidate()
    }
    override fun onDraw(canvas:Canvas) {super.onDraw(canvas);rect.set(left,top,left+bitmap.width*scale,top+bitmap.height*scale);canvas.drawBitmap(bitmap,null,rect,paint)}
    override fun onTouchEvent(event:MotionEvent):Boolean {
        pinch.onTouchEvent(event);gestures.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP)performClick()
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean=when(action){
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD->{zoomBy(1.5f);true}
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD->{zoomBy(1/1.5f);true}
        else->super.performAccessibilityAction(action,args)
    }
}
