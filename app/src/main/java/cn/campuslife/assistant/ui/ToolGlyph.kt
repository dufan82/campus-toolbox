package cn.campuslife.assistant.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable

/** Neutral scalable line icons, never platform brand logos. */
class ToolGlyph(context: Context,private val kind: String,color: Int) : Drawable() {
    private val size=Ui.dp(context,24)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color;style=Paint.Style.STROKE;strokeWidth=1.7f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
    override fun getIntrinsicWidth()=size
    override fun getIntrinsicHeight()=size
    override fun draw(canvas: Canvas) {
        canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
        fun path(vararg points: Float) { val p=Path();p.moveTo(points[0],points[1]);for(i in 2 until points.size step 2)p.lineTo(points[i],points[i+1]);canvas.drawPath(p,paint) }
        fun rect(l: Float,t: Float,r: Float,b: Float)=canvas.drawRoundRect(l,t,r,b,1.5f,1.5f,paint)
        when(kind) {
            "package" -> {path(12f,3f,21f,8f,21f,16f,12f,21f,3f,16f,3f,8f,12f,3f);path(3f,8f,12f,13f,21f,8f);path(12f,13f,12f,21f);path(7f,5.5f,16f,10.5f)}
            "qr" -> {rect(3f,3f,9f,9f);rect(15f,3f,21f,9f);rect(3f,15f,9f,21f);path(15f,15f,18f,15f,18f,18f,21f,18f,21f,21f,15f,21f);path(3f,12f,5f,12f);path(12f,3f,12f,6f);path(12f,11f,12f,16f)}
            "bag" -> {rect(4f,6f,20f,21f);path(8f,7f,8f,4f,16f,4f,16f,7f);val p=Path();p.moveTo(8f,10f);p.cubicTo(8f,15f,16f,15f,16f,10f);canvas.drawPath(p,paint)}
            "image" -> {rect(3f,3f,21f,21f);canvas.drawCircle(8f,8f,1.4f,paint);path(3f,17f,8f,12f,12f,16f,16f,12f,21f,17f)}
            "back" -> path(15f,5f,8f,12f,15f,19f)
            "map" -> {path(3f,5f,9f,3f,15f,5f,21f,3f,21f,19f,15f,21f,9f,19f,3f,21f,3f,5f);path(9f,3f,9f,19f);path(15f,5f,15f,21f)}
            "info" -> {canvas.drawCircle(12f,12f,9f,paint);path(12f,10f,12f,17f);canvas.drawCircle(12f,6.5f,.5f,paint)}
            "share" -> {path(7f,10f,4f,10f,4f,21f,20f,21f,20f,10f,17f,10f);path(12f,15f,12f,2f);path(8f,6f,12f,2f,16f,6f)}
            else -> {canvas.drawCircle(12f,12f,7f,paint);canvas.drawCircle(12f,12f,2.5f,paint);for(i in 0..7){canvas.save();canvas.rotate(i*45f,12f,12f);path(12f,2f,12f,4f);canvas.restore()}}
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter }
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
