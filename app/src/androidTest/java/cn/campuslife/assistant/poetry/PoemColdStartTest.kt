package cn.campuslife.assistant.poetry

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray

/** Run seed and verify as two separate instrumentation invocations to establish a cold process. */
class PoemColdStartTest {
    @Test fun separateProcessAvoidsPreviousRecentPoemAndLibraryIsAccurateShape() {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext
        val array=JSONArray(app.assets.open("poems.json").bufferedReader().use {it.readText()})
        assertTrue(array.length() in 200..350)
        val ids=mutableSetOf<String>();val texts=mutableSetOf<String>()
        repeat(array.length()) {i->val row=array.getJSONObject(i);assertTrue(ids.add(row.getString("id")));assertTrue(texts.add(row.getString("text")));listOf("author","work","source").forEach {assertTrue(row.getString(it).isNotBlank())}}
        val prefs=app.getSharedPreferences("poem-cold-test",0)
        val current=PoemSession.current(app)
        assertSame(current,PoemSession.current(app))
        if(InstrumentationRegistry.getArguments().getString("poemPhase")=="verify")assertNotEquals(prefs.getString("previous",null),current.id)
        assertTrue(prefs.edit().putString("previous",current.id).commit())
    }
}
