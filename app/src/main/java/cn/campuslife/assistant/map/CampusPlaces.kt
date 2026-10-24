package cn.campuslife.assistant.map

data class CampusCoordinateData(
    val center: GeoPoint,
    val records: List<CampusCoordinate>,
    val customPlaces: List<CampusLandmark> = emptyList(),
    val hiddenIds: Set<String> = emptySet()
) {
    val points: Map<String,GeoPoint> get()=records.associate { it.id to it.point }
    val catalog: List<CampusLandmark> get()=CampusCatalog.entries+customPlaces
    val visiblePlaces: List<CampusLandmark> get()=catalog.filter { it.id !in hiddenIds }
    fun search(query: String,category: MapCategory)=CampusCatalog.search(query,category,visiblePlaces)
    fun withCustomPlace(place: CampusLandmark): CampusCoordinateData = copy(
        customPlaces=customPlaces.filterNot { it.id==place.id }+place.copy(name=place.name.trim())
    ).checked()
    fun withoutCustomPlace(id: String): CampusCoordinateData {
        require(customPlaces.any { it.id==id }) { "Only custom places can be removed" }
        return copy(customPlaces=customPlaces.filterNot { it.id==id },records=records.filterNot { it.id==id },hiddenIds=hiddenIds-id).checked()
    }
    fun withPosition(id: String,point: GeoPoint): CampusCoordinateData = copy(
        records=records.filterNot { it.id==id }+CampusCoordinate(id,point,"manual-map-review",true)
    ).checked()
    fun withHiddenIds(ids: Set<String>)=copy(hiddenIds=ids.toSet()).checked()
    fun without(id: String): CampusCoordinateData {
        require(catalog.any { it.id==id }) { "Unknown campus place" }
        return copy(records=records.filterNot { it.id==id })
    }
    fun checked(): CampusCoordinateData { CampusPlaceRules.validate(this);return this }
}

/** Public map data only; never reads phone position, profiles or photos. */
object CampusPlaceRules {
    val categories=listOf(MapCategory.STUDY,MapCategory.DORM,MapCategory.FOOD,MapCategory.EXPRESS,MapCategory.SPORT,MapCategory.SERVICE)
    private val customId=Regex("custom-[a-z0-9][a-z0-9-]{0,63}")
    private fun validName(name: String)=name.isNotBlank() && name==name.trim() && name.length<=40 && name.none { it.isISOControl() }

    fun validate(data: CampusCoordinateData) {
        require(data.customPlaces.size<=200) { "Too many custom places" }
        require(data.catalog.map { it.id }.distinct().size==data.catalog.size) { "Duplicate place IDs" }
        data.customPlaces.forEach { place ->
            require(customId.matches(place.id) && place.category in categories) { "Invalid custom place" }
            require(validName(place.name) && place.aliases.size<=10 && place.aliases.all(::validName)) { "Invalid place name" }
            val others=data.catalog.filterNot { it.id==place.id }
            require((listOf(place.name)+place.aliases).none { CampusCatalog.hasExactName(it,others) }) { "Place name already exists" }
        }
        val ids=data.catalog.map { it.id }.toSet()
        require(data.hiddenIds.all { it in ids }) { "Unknown hidden place" }
        CampusCoordinateRules.validate("GCJ-02",data.center,data.records,ids)
    }
}
