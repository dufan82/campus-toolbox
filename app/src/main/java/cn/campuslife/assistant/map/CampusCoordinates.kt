package cn.campuslife.assistant.map

data class CampusCoordinate(val id: String,val point: GeoPoint,val source: String,val reviewed: Boolean)
object CampusCoordinateRules {
    fun validate(system: String,center: GeoPoint,records: List<CampusCoordinate>,allowed: Set<String> = CampusCatalog.entries.map { it.id }.toSet()): Map<String,GeoPoint> {
        require(system=="GCJ-02") { "Unsupported campus coordinate system" }
        require(records.map { it.id }.distinct().size==records.size) { "Duplicate campus place IDs" }
        records.forEach { record ->
            require(record.id in allowed && record.reviewed && record.source.isNotBlank()) { "Unreviewed campus coordinate" }
            require(MapRules.distanceMeters(center,record.point)<=1000) { "Coordinate is outside the campus review area" }
        }
        return records.associate { it.id to it.point }
    }
}
