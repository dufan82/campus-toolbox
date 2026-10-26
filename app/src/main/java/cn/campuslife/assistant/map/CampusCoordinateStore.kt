package cn.campuslife.assistant.map

import android.content.Context
import cn.campuslife.assistant.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** Bundled public data plus local edits. Neither edits nor student profiles are uploaded. */
class CampusCoordinateStore(private val context: Context) {
    private val preferences=context.getSharedPreferences("campus_coordinate_draft",Context.MODE_PRIVATE)

    fun load(): CampusCoordinateData {
        val bundled=context.assets.open("pingtan-coordinates.json").bufferedReader().use { decode(it.readText()) }
        val draft=preferences.getString("public_coordinates_v1",null) ?: return bundled
        val restored=decode(draft)
        // Preserve old position snapshots, including deleted coordinates. Inherit only display defaults.
        return if(JSONObject(draft).getInt("version")==1) restored.withHiddenIds(bundled.hiddenIds) else restored
    }

    fun save(data: CampusCoordinateData): Boolean {
        check(BuildConfig.LOCAL_MAP_EDITING) { "Local campus editing is disabled" }
        data.checked()
        return preferences.edit().putString("public_coordinates_v1",encode(data)).commit()
    }

    companion object {
        fun decode(text: String): CampusCoordinateData {
            val json=JSONObject(text)
            val version=json.getInt("version")
            require(version in 1..2) { "Unsupported campus coordinate version" }
            require(json.getString("coordinateSystem")=="GCJ-02") { "Unsupported campus coordinate system" }
            val anchor=json.getJSONObject("campus")
            val center=GeoPoint(anchor.getDouble("latitude"),anchor.getDouble("longitude"))
            val array=json.getJSONArray("locations")
            val records=(0 until array.length()).map { index ->
                val item=array.getJSONObject(index)
                CampusCoordinate(item.getString("id"),GeoPoint(item.getDouble("latitude"),item.getDouble("longitude")),item.getString("source"),item.getBoolean("reviewed"))
            }
            val custom=if(version==2) json.getJSONArray("customPlaces") else JSONArray()
            require(custom.length()<=200) { "Too many custom places" }
            val places=(0 until custom.length()).map { index ->
                val item=custom.getJSONObject(index)
                val aliases=item.optJSONArray("aliases") ?: JSONArray()
                CampusLandmark(item.getString("id"),item.getString("name"),MapCategory.valueOf(item.getString("category")),
                    (0 until aliases.length()).map { aliases.getString(it) })
            }
            val hidden=if(version==2) json.getJSONArray("hiddenIds") else JSONArray()
            return CampusCoordinateData(center,records,places,(0 until hidden.length()).map { hidden.getString(it) }.toSet()).checked()
        }

        fun encode(data: CampusCoordinateData): String {
            data.checked()
            return JSONObject().put("version",2).put("coordinateSystem","GCJ-02")
                .put("campus",JSONObject().put("latitude",data.center.latitude).put("longitude",data.center.longitude))
                .put("locations",JSONArray().apply {
                    data.records.forEach { item -> put(JSONObject().put("id",item.id).put("latitude",item.point.latitude)
                        .put("longitude",item.point.longitude).put("source",item.source).put("reviewed",item.reviewed)) }
                }).put("catalog",JSONArray().apply {
                    data.catalog.forEach { item -> put(JSONObject().put("id",item.id).put("name",item.name)
                        .put("category",item.category.name).put("aliases",JSONArray(item.aliases))) }
                }).put("customPlaces",JSONArray().apply {
                    data.customPlaces.forEach { item -> put(JSONObject().put("id",item.id).put("name",item.name)
                        .put("category",item.category.name).put("aliases",JSONArray(item.aliases))) }
                }).put("hiddenIds",JSONArray(data.hiddenIds.sorted())).put("reference",JSONObject().put("asset","pingtan-reference.png")
                    .put("coordinateSystem","normalized-image").put("width",4822).put("height",3470)
                    .put("sha256","9694290a55c2b318048448ffc00a8db6ec6ff3118f499dbe6dd967434d5706f2")
                    .put("points",JSONArray().apply {
                        CampusReference.points.forEach { (id,point) -> put(JSONObject().put("id",id).put("x",point.x).put("y",point.y)) }
                    })).toString(2)
        }
    }
}
