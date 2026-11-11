package cn.campuslife.assistant.story

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Two explicit phases across the saved pre-cloud APK and this build, on the disposable emulator. */
class StoryCloudUpgradeDeviceTest {
    @Test fun coverInstallPreservesOldStoryDraftFavoritesProgressAndOtherBusinessFiles() {
        val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext
        val phase=InstrumentationRegistry.getArguments().getString("storyUpgradePhase")
        assumeTrue(phase in setOf("seed","verify") && Build.HARDWARE in setOf("ranchu","goldfish"))
        val file=File(app.filesDir,"stories-local-v1.json")
        val snapshot=File(app.filesDir,"story-cloud-upgrade-fixture.json")
        fun sha(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
        if(phase=="seed") {
            // Uses only the original schema; also executable against the previous APK.
            file.writeText(JSONObject().put("version",1).put("favorites",org.json.JSONArray(listOf("story-001")))
                .put("progress",JSONObject().put("story-001",0.6)).put("fontSp",18)
                .put("draft",JSONObject().put("title","升级前草稿").put("category","WARMTH")
                    .put("body","这一份旧草稿需要在安装新版之后完整保留。".repeat(8)).put("nickname","离线测试")
                    .put("originalRights",true).put("displayPermission",true)).toString())
            val other=File(app.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty().filter {it.isFile && !it.name.startsWith("story")}
            snapshot.writeText(JSONObject().put("story",sha(file)).put("others",JSONObject(other.associate {it.name to sha(it)})).toString())
        } else {
            val before=JSONObject(snapshot.readText());assertEquals(before.getString("story"),sha(file))
            val others=before.getJSONObject("others");others.keys().forEach {name->assertEquals(name,others.getString(name),sha(File(app.applicationInfo.dataDir,"shared_prefs/$name")))}
            val state=StoryStore(app).read();assertEquals("升级前草稿",state.draft.title)
            assertEquals(setOf("story-001"),state.favorites);assertEquals(0.6,state.progress["story-001"]!!,0.0);assertEquals(18,state.fontSp)
            assertEquals("离线测试",state.draft.nickname);assertTrue(state.draft.originalRights && state.draft.displayPermission)
            assertFalse(StoryCloudConfig.mock);assertFalse(StoryCloudConfig.configured)
            assertEquals(before.getString("story"),sha(file))
        }
    }
}
