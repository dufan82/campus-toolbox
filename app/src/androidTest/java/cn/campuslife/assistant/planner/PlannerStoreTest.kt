package cn.campuslife.assistant.planner

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.AtomicJsonFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlannerStoreTest {
    private fun isolated(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(base.cacheDir, "planner-test-${PlannerRules.newId()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = dir
        }
    }
    @Test fun roundTripPreservesStableIdsAndOptionalFields() {
        val context = isolated()
        val item = TodoItem(PlannerRules.newId(), "Fixture", LocalDate.of(2026,1,31), category="Fixture", repeat=Repeat.MONTHLY)
        val data = PlannerState(todos=listOf(item),countdowns=listOf(CountdownItem(PlannerRules.newId(),"Fixture",LocalDate.of(2024,2,29),yearly=true)))
        PlannerStore(context).write(data)
        assertEquals(data,PlannerStore(context).read())
        val edited = item.copy(title="Edited",revision=PlannerRules.newId())
        PlannerStore(context).write(data.copy(todos=listOf(edited)))
        assertEquals(item.id,PlannerStore(context).read().todos.single().id)
    }
    @Test fun malformedVersionTypesAndDatesAreNotSilentlyReset() {
        val context = isolated()
        val file = AtomicJsonFile(context,"planner-v1.json")
        listOf("""{"version":2,"todos":[],"countdowns":[],"delivered":{}}""",
            """{"version":1,"todos":"wrong","countdowns":[],"delivered":{}}""",
            """{"version":1,"todos":[],"countdowns":[{"id":"bad"}],"delivered":{}}""").forEach {
            file.write(JSONObject(it))
            assertThrows(Exception::class.java) { PlannerStore(context).read() }
            assertEquals(JSONObject(it).toString(), file.read()!!.toString())
        }
    }
    @Test fun queuedMutationsReadLatestSavedStateAndLoadErrorPreventsOverwrite() {
        val context = isolated()
        val repo = PlannerRepository.get(context)
        val legacy=TodoItem(PlannerRules.newId(),"Legacy fixture")
        PlannerStore(context).write(PlannerState(todos=listOf(legacy)))
        val done = CountDownLatch(2)
        repeat(2) { index -> repo.change({ old -> old.copy(countdowns=old.countdowns+CountdownItem(PlannerRules.newId(),"Fixture $index",LocalDate.of(2030,1,1))) }) { result ->
            assertTrue(result.isSuccess); done.countDown()
        } }
        assertTrue(done.await(5,TimeUnit.SECONDS))
        assertEquals(2,PlannerStore(context).read().countdowns.size)
        assertEquals(listOf(legacy),PlannerStore(context).read().todos)
        AtomicJsonFile(context,"planner-v1.json").write(JSONObject("""{"version":99}"""))
        val failed = CountDownLatch(1)
        repo.change({ PlannerState() }) { result -> assertTrue(result.isFailure); failed.countDown() }
        assertTrue(failed.await(5,TimeUnit.SECONDS))
        assertEquals(99,AtomicJsonFile(context,"planner-v1.json").read()!!.getInt("version"))
    }
}
