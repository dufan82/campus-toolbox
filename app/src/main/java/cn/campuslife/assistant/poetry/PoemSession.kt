package cn.campuslife.assistant.poetry

import android.content.Context
import org.json.JSONArray
import java.util.concurrent.ThreadLocalRandom

/** A process session, not an Activity session: tab returns/recreation keep the same poem. Call off-main. */
object PoemSession {
    private val lock=Any()
    @Volatile private var current:Poem?=null
    private var library:List<Poem>?=null
    fun peek():Poem?=current
    fun current(context:Context):Poem=synchronized(lock){current ?: select(context)}
    fun next(context:Context):Poem=synchronized(lock){select(context)}
    private fun select(context:Context):Poem {
        val app=context.applicationContext
        val poems=library ?: app.assets.open("poems.json").bufferedReader(Charsets.UTF_8).use {reader->
            val json=JSONArray(reader.readText());require(json.length() in 2..3000)
            (0 until json.length()).map {i->json.getJSONObject(i).let {Poem(it.getString("id"),it.getString("text"),it.getString("author"),it.getString("work"))}}
                .also {list->require(list.map {it.id}.distinct().size==list.size);require(list.all {it.id.isNotBlank() && it.text.isNotBlank() && it.author.isNotBlank() && it.work.isNotBlank()})}
        }.also {library=it}
        val prefs=app.getSharedPreferences("poem-history-v1",Context.MODE_PRIVATE)
        val history=runCatching {JSONArray(prefs.getString("recent","[]"))}.getOrDefault(JSONArray()).let {a->(0 until a.length()).map {a.optString(it)}}
        val id=PoemPicker.choose(poems.map {it.id},current?.id,history){ThreadLocalRandom.current().nextInt(it)}
        val chosen=poems.single {it.id==id}
        val recent=(listOf(id)+history.filter {it!=id && known(poems,it)}).take(40)
        check(prefs.edit().putString("recent",JSONArray(recent).toString()).commit()) {"诗句记录未能保存"}
        current=chosen
        return chosen
    }
    private fun known(poems:List<Poem>,id:String)=poems.any {it.id==id}
}
