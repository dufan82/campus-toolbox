package cn.campuslife.assistant.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class HomeLayoutStore(context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("home_layout_v1",Context.MODE_PRIVATE)
    fun load(): HomeLayout {
        val text=prefs.getString("layout",null) ?: return HomeLayout.defaults()
        val json=JSONObject(text);require(json.getInt("version")==1)
        fun ids(key: String): List<String> {
            val values=json.getJSONArray(key);require(values.length()<=100)
            return (0 until values.length()).map { values.getString(it) }
        }
        return HomeLayout(ids("order"),ids("hidden").toSet()).normalized()
    }
    fun save(state: HomeLayout) {
        val checked=state.normalized()
        check(prefs.edit().putString("layout",JSONObject().put("version",1).put("order",JSONArray(checked.order)).put("hidden",JSONArray(checked.hidden.toList())).toString()).commit()) { "首页布局未保存，请检查存储空间" }
    }
}
