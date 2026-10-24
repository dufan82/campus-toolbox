package cn.campuslife.assistant.map

data class MapLabelBox(val id: String,val left: Double,val top: Double,val right: Double,val bottom: Double,val priority: Int=0)
object MapLabelLayout {
    fun select(boxes: List<MapLabelBox>,width: Double,height: Double,budget: Int): Set<String> {
        if(width<=0 || height<=0 || budget<=0) return emptySet()
        val accepted=mutableListOf<MapLabelBox>()
        boxes.sortedWith(compareByDescending<MapLabelBox> { it.priority }.thenBy { it.id }).forEach { box ->
            val valid=listOf(box.left,box.top,box.right,box.bottom).all { it.isFinite() } &&
                box.left>=0 && box.top>=0 && box.right<=width && box.bottom<=height && box.right>box.left && box.bottom>box.top
            if(valid && accepted.size<budget && accepted.none { other ->
                    box.left<other.right && box.right>other.left && box.top<other.bottom && box.bottom>other.top
                }) accepted.add(box)
        }
        return accepted.map { it.id }.toSet()
    }
    fun budget(zoom: Double,count: Int)=when { count<=5->count;zoom<16.5->6;zoom<17.5->12;else->40 }
}
