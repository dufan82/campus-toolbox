package cn.campuslife.assistant.map

enum class PlaceAvailability { AVAILABLE,HIDDEN,MISSING_POSITION,REMOVED }
enum class VisitReason { USER_SELECTION,RESTORE,SEARCH_DISPLAY }

data class PlaceHistory(val favorites: List<String> = emptyList(),val recent: List<String> = emptyList(),val favoriteTimes: Map<String,Long> = emptyMap()) {
    fun toggle(id: String,at: Long=System.currentTimeMillis()): PlaceHistory {
        require(at>=0)
        return if(id in favorites) removeFavorite(id) else copy(favorites=favorites+id,favoriteTimes=favoriteTimes+(id to at))
    }
    fun removeFavorite(id: String)=copy(favorites=favorites-id,favoriteTimes=favoriteTimes-id)
    fun visit(id: String,reason: VisitReason=VisitReason.USER_SELECTION)=if(reason!=VisitReason.USER_SELECTION) this else copy(recent=(listOf(id)+recent.filterNot { it==id }).take(20))
    companion object {
        fun availability(id: String,data: CampusCoordinateData): PlaceAvailability = when {
            data.catalog.none { it.id==id }->PlaceAvailability.REMOVED
            id in data.hiddenIds->PlaceAvailability.HIDDEN
            data.points[id]==null->PlaceAvailability.MISSING_POSITION
            else->PlaceAvailability.AVAILABLE
        }
    }
}
