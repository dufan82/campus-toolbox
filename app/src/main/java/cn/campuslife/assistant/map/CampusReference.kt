package cn.campuslife.assistant.map

import kotlin.math.min

data class ImagePoint(val x: Double,val y: Double)
data class ReferenceTransform(val viewWidth: Double,val viewHeight: Double,val imageWidth: Double,val imageHeight: Double,val left: Double,val top: Double) {
    companion object {
        fun fit(width: Double,height: Double): ReferenceTransform {
            require(width>0 && height>0 && width.isFinite() && height.isFinite())
            val scale=min(width/4822,height/3470)
            return ReferenceTransform(width,height,4822*scale,3470*scale,(width-4822*scale)/2,(height-3470*scale)/2)
        }
    }
    fun screen(point: ImagePoint)=ImagePoint(point.x*imageWidth+left,point.y*imageHeight+top)
    fun imageAt(x: Double,y: Double): ImagePoint? {
        val px=(x-left)/imageWidth;val py=(y-top)/imageHeight
        return if(px in 0.0..1.0 && py in 0.0..1.0) ImagePoint(px,py) else null
    }
    private fun clamped()=copy(
        left=if(imageWidth<=viewWidth) (viewWidth-imageWidth)/2 else left.coerceIn(viewWidth-imageWidth,0.0),
        top=if(imageHeight<=viewHeight) (viewHeight-imageHeight)/2 else top.coerceIn(viewHeight-imageHeight,0.0))
    fun zoomAt(factor: Double,x: Double,y: Double): ReferenceTransform {
        require(factor>0 && factor.isFinite())
        val base=fit(viewWidth,viewHeight)
        val nextWidth=(imageWidth*factor).coerceIn(base.imageWidth,base.imageWidth*8)
        val ratio=nextWidth/imageWidth
        return copy(imageWidth=nextWidth,imageHeight=imageHeight*ratio,left=x-(x-left)*ratio,top=y-(y-top)*ratio).clamped()
    }
    fun pan(dx: Double,dy: Double)=copy(left=left+dx,top=top+dy).clamped()
    fun focus(point: ImagePoint): ReferenceTransform {
        val base=fit(viewWidth,viewHeight)
        return base.copy(imageWidth=base.imageWidth*3.5,imageHeight=base.imageHeight*3.5,
            left=viewWidth/2-point.x*base.imageWidth*3.5,top=viewHeight/2-point.y*base.imageHeight*3.5).clamped()
    }
}

/** Anchors on the user's diagram only. They never become navigation coordinates. */
object CampusReference {
    private fun p(x: Int,y: Int)=ImagePoint(x/1778.0,y/1279.0)
    val points: Map<String,ImagePoint> = linkedMapOf(
        "dorm-1" to p(530,267),"dorm-2" to p(538,378),"dorm-3" to p(667,353),"dorm-4" to p(655,251),
        "dorm-5" to p(788,239),"dorm-6" to p(805,332),"dorm-7" to p(894,220),"dorm-8" to p(992,205),
        "dorm-9" to p(1007,311),"dorm-10" to p(1018,419),"teacher-1" to p(376,288),"teacher-2" to p(375,358),
        "teacher-3" to p(375,432),"staff-1" to p(918,401),
        "training-1-north" to p(387,694),"construction" to p(363,754),"training-1-south" to p(387,830),
        "training-8" to p(383,902),"training-2" to p(387,972),"training-3" to p(497,1039),
        "science-1" to p(526,716),"science-2" to p(550,773),"science-3" to p(599,835),"science-4" to p(624,884),
        "training-6" to p(1013,568),"training-7" to p(1019,629),"humanities" to p(1026,726),
        "canteen-1" to p(567,519),"canteen-2" to p(906,468),"museum" to p(716,469),"lecture-hall" to p(810,436),
        "alumni-harbor" to p(725,420),"coffee" to p(654,445),"service-hall" to p(809,380),"activity-center" to p(774,462),
        "mental-health" to p(795,294),"express-station" to p(791,275),"fire-station" to p(706,268),
        "safety-center" to p(653,208),"training-store" to p(615,194),"startup-center" to p(452,218),"gym" to p(1185,654))

    fun hit(transform: ReferenceTransform,x: Double,y: Double,radius: Double,allowedIds: Set<String> = points.keys): String? {
        fun distance(point: ImagePoint): Double { val screen=transform.screen(point);return (screen.x-x)*(screen.x-x)+(screen.y-y)*(screen.y-y) }
        val closest=points.filterKeys { it in allowedIds }.minByOrNull { distance(it.value) } ?: return null
        return closest.key.takeIf { distance(closest.value)<=radius*radius }
    }
}
