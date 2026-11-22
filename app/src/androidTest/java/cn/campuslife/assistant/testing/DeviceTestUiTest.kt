package cn.campuslife.assistant.testing

import org.junit.Test
import org.junit.Assert.*
import android.app.Activity
import android.content.Intent
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.story.StoryActivity
import java.io.File

class DeviceTestUiTest {
    @Test fun sharedUiAndFileRestoreHelpersAreIncludedInInstrumentationOnly() {
        Class.forName("cn.campuslife.assistant.testing.DeviceTestUi")
        Class.forName("cn.campuslife.assistant.testing.TestFilesSnapshot")
    }
    @Test fun nestedMainThreadLookupAndFailureDoNotDeadlockOrHideTheOriginalCause() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val a=inst.startActivitySync(Intent(inst.targetContext,StoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        try {
            DeviceTestUi.main {
                val v=TextView(a).apply {tag="helper_synthetic"}
                (a.window.decorView as android.view.ViewGroup).addView(v)
                assertSame(v,DeviceTestUi.main {DeviceTestUi.find(a,"helper_synthetic")})
                try {DeviceTestUi.find(a,"absent",1000);fail("Missing tag accepted")}catch(e:AssertionError){assertTrue(e.message!!.contains("no blocking wait"))}
            }
            val failure=IllegalArgumentException("Synthetic failure")
            try {DeviceTestUi.main {throw failure};fail("Failure swallowed")}catch(e:IllegalArgumentException){assertSame(failure,e)}
            val recreated=DeviceTestUi.recreate(a)
            DeviceTestUi.main {assertFalse(recreated===a);recreated.finish()}
        }finally {DeviceTestUi.main {if(!a.isDestroyed)a.finish()}}
    }
    @Test fun snapshotsRestoreOriginalAndSidecarsWithoutTouchingUnlistedFiles() {
        val root=kotlin.io.path.createTempDirectory("helper-owned-").toFile()
        try {
            File(root,"data").writeText("original");File(root,"data.bak").writeText("old backup");File(root,"unlisted").writeText("leave me")
            val snapshot=TestFilesSnapshot.capture(root,listOf("data","data.bak","data.new"))
            File(root,"data").writeText("changed");File(root,"data.bak").delete();File(root,"data.new").writeText("temporary")
            snapshot.restore()
            assertEquals("original",File(root,"data").readText());assertEquals("old backup",File(root,"data.bak").readText())
            assertFalse(File(root,"data.new").exists());assertEquals("leave me",File(root,"unlisted").readText())
            for(path in listOf("../outside",root.absolutePath,".")) {
                try {TestFilesSnapshot.capture(root,listOf(path));fail("Unsafe path accepted")}catch(_:IllegalArgumentException){}
            }
        }finally {root.deleteRecursively()}
    }
}
