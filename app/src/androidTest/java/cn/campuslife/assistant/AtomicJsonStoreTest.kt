package cn.campuslife.assistant

import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.AtomicJsonFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AtomicJsonStoreTest {
    @Test fun roundTripAndCorruptReadDoesNotEraseFile() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="atomic-test-${System.nanoTime()}.json"
        val file=java.io.File(context.filesDir,name)
        try {
            val store=AtomicJsonFile(context,name)
            assertNull(store.read())
            store.write(JSONObject().put("version",1).put("value","saved"))
            assertEquals("saved",AtomicJsonFile(context,name).read()!!.getString("value"))
            file.writeText("broken")
            assertTrue(runCatching { store.read() }.isFailure)
            assertEquals("broken",file.readText())
        } finally { file.delete();java.io.File(file.path+".bak").delete() }
    }
}
