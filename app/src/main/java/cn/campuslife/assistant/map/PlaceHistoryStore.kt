package cn.campuslife.assistant.map

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PlaceHistoryStore(context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("campus_place_history_v1",Context.MODE_PRIVATE)
    fun load(): PlaceHistory = synchronized(PlaceHistoryStore::class.java) {
        val text=prefs.getString("history",null) ?: return@synchronized PlaceHistory()
        val json=JSONObject(text);val version=json.getInt("version");require(version in 1..2)
        fun ids(key: String,limit: Int): List<String> {
            val array=json.getJSONArray(key);require(array.length()<=256)
            return (0 until array.length()).map { array.getString(it).also { id -> require(id.matches(Regex("[a-z0-9-]{1,80}"))) } }.distinct().take(limit)
        }
        val favorites=ids("favorites",256)
        val times=if(version==1) emptyMap() else {
            val values=json.getJSONObject("favoriteTimes");require(values.length()<=256)
            values.keys().asSequence().associateWith { id ->
                val value=values.get(id);require(id in favorites && (value is Long || value is Int))
                (value as Number).toLong().also { require(it>=0) }
            }
        }
        // Schema1 has unknown historic times: keep them unknown, never invent a collection date.
        PlaceHistory(favorites,ids("recent",20),times)
    }
    fun update(change: (PlaceHistory)->PlaceHistory): PlaceHistory = synchronized(PlaceHistoryStore::class.java) {
        val next=change(load())
        require(next.favorites.size<=256) { "收藏最多256处，请先取消不需要的收藏" }
        require(next.favoriteTimes.keys.all { it in next.favorites } && next.favoriteTimes.values.all { it>=0 })
        check(prefs.edit().putString("history",JSONObject().put("version",2).put("favorites",JSONArray(next.favorites)).put("recent",JSONArray(next.recent)).put("favoriteTimes",JSONObject(next.favoriteTimes)).toString()).commit()) { "地点记录未能保存，请检查存储空间" }
        next
    }
}
