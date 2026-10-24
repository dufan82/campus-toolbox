package cn.campuslife.assistant.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import cn.campuslife.assistant.ui.Ui
import com.amap.api.maps.AMap
import com.amap.api.maps.model.*
import kotlin.math.ceil
import kotlin.math.roundToInt

object CampusMarkerIcons {
    fun bitmap(context: Context,selected: Boolean): Bitmap {
        val size=Ui.dp(context,48)
        return Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).also { image ->
            val canvas=Canvas(image);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            val c=size/2f
            if(selected) { paint.color=0x330c6b58;canvas.drawCircle(c,c,Ui.dp(context,14).toFloat(),paint) }
            paint.color=Color.WHITE;canvas.drawCircle(c,c,Ui.dp(context,if(selected) 9 else 7).toFloat(),paint)
            paint.color=Ui.blue;canvas.drawCircle(c,c,Ui.dp(context,if(selected) 6 else 4).toFloat(),paint)
        }
    }
}

/** Small, 48dp hit-area dots; readable names are independently selected in screen space. */
class CampusMarkerLayer(private val context: Context) {
    private val normal=BitmapDescriptorFactory.fromBitmap(CampusMarkerIcons.bitmap(context,false))
    private val highlighted=BitmapDescriptorFactory.fromBitmap(CampusMarkerIcons.bitmap(context,true))
    private val markers=linkedMapOf<String,Marker>()
    private val labels=linkedMapOf<String,Text>()
    private var items=emptyList<MapPlace>()
    private var map: AMap?=null
    private var selectedId: String?=null
    private var campus=false
    private val fontSize=(12*context.resources.displayMetrics.scaledDensity).roundToInt()
    private val textPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=fontSize.toFloat();typeface=Typeface.DEFAULT }
    private fun label(place: MapPlace)=place.title.substringBefore(" · ").let { if(it.length>12) it.take(11)+"…" else it }

    fun render(map: AMap,items: List<MapPlace>,scope: SearchScope,selected: String?,width: Int,height: Int) {
        clear();this.map=map;this.items=items;selectedId=selected;campus=scope==SearchScope.CAMPUS
        items.forEach { place ->
            map.addMarker(MarkerOptions().position(LatLng(place.point.latitude,place.point.longitude)).title(place.title)
                .anchor(.5f,.5f).icon(if(place.id==selectedId) highlighted else normal).zIndex(3f))?.let { marker ->
                marker.`object`=place;markers[place.id]=marker
            }
            if(campus) map.addText(TextOptions().position(LatLng(place.point.latitude,place.point.longitude)).text(label(place))
                .fontSize(fontSize).typeface(Typeface.DEFAULT).fontColor(Ui.ink).backgroundColor(0xeeffffff.toInt())
                .align(Text.ALIGN_CENTER_HORIZONTAL,Text.ALIGN_BOTTOM).visible(false).zIndex(2f))?.let { labels[place.id]=it }
        }
        layout(width,height)
    }

    fun select(id: String?,width: Int,height: Int) {
        selectedId=id
        markers.forEach { (key,marker) -> marker.setIcon(if(key==id) highlighted else normal) }
        layout(width,height)
    }

    fun layout(width: Int,height: Int) {
        val surface=map ?: return
        if(!campus || width<=0 || height<=0) return
        // Projection is only for label placement, never persisted as a geographic point.
        runCatching {
            val gap=Ui.dp(context,5).toDouble()
            val offset=Ui.dp(context,12)
            val heightPixels=ceil((textPaint.fontMetrics.bottom-textPaint.fontMetrics.top).toDouble())+2*gap
            val boxes=items.map { place ->
                val point=surface.projection.toScreenLocation(LatLng(place.point.latitude,place.point.longitude))
                labels[place.id]?.position=surface.projection.fromScreenLocation(Point(point.x,point.y-offset))
                val halfWidth=textPaint.measureText(label(place))/2+gap
                MapLabelBox(place.id,point.x-halfWidth,point.y-offset-heightPixels,point.x+halfWidth,point.y-offset+gap,
                    if(place.id==selectedId) 100 else if(place.address in listOf("食堂","快递","运动")) 10 else 0)
            }
            val visible=MapLabelLayout.select(boxes,width.toDouble(),height.toDouble(),MapLabelLayout.budget(surface.cameraPosition.zoom.toDouble(),items.size))
            labels.forEach { (id,text) -> text.isVisible=id in visible }
        }
    }

    private fun clear() {
        markers.values.forEach { runCatching { it.remove() } };markers.clear()
        labels.values.forEach { runCatching { it.remove() } };labels.clear()
    }
    fun close() { clear();map=null;items=emptyList();runCatching { normal.recycle() };runCatching { highlighted.recycle() } }
}
