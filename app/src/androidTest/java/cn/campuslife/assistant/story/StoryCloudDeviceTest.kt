package cn.campuslife.assistant.story

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.BuildConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit loopback Mock phases. Never uses production URLs, credentials or school services. */
class StoryCloudDeviceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=inst.targetContext
    private val prefs get()=app.getSharedPreferences("cloud-test-fixture",0)
    private fun nodes(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {nodes(v.getChildAt(it))}else emptyList()
    private fun await(check:()->Boolean) {val end=SystemClock.uptimeMillis()+20000;while(SystemClock.uptimeMillis()<end){if(check())return;SystemClock.sleep(60)};assertTrue("Timed out",check())}
    private fun shot(name:String) {
        inst.waitForIdleSync();Thread.sleep(500)
        val bmp=inst.uiAutomation.takeScreenshot();val dir=File(app.getExternalFilesDir(null),"cloud-screens").apply {mkdirs()}
        File(dir,"$name.png").outputStream().use {assertTrue(bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))};bmp.recycle()
    }
    private fun api(action:String,payload:JSONObject=JSONObject(),token:String?=null):JSONObject {
        assumeTrue(StoryCloudConfig.mock)
        val connection=URL("http://127.0.0.1:8787/api").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=3000;connection.readTimeout=5000;connection.requestMethod="POST";connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json");token?.let {connection.setRequestProperty("Authorization","Bearer $it")}
            connection.outputStream.use {it.write(JSONObject().put("action",action).put("payload",payload).toString().toByteArray())}
            return JSONObject(connection.inputStream.bufferedReader().use {it.readText()}).also {assertTrue(it.toString(),it.getBoolean("ok"))}.getJSONObject("data")
        } finally {connection.disconnect()}
    }
    private fun admin()=api("adminLogin",JSONObject().put("username","admin").put("password","mock-admin-password")).getString("accessToken")
    @Test fun cloudFlowPhase() {
        assumeTrue(BuildConfig.DEBUG && StoryCloudConfig.mock)
        when(InstrumentationRegistry.getArguments().getString("cloudPhase")) {
            "submit" -> submitAndApprove()
            "offline" -> offlineRead()
            "withdraw" -> withdraw()
            "failedSubmit" -> failedSubmission()
            "retrySubmit" -> retrySubmission()
            else -> assumeTrue("Use explicit local Mock phase",false)
        }
    }
    private fun submitAndApprove() {
        StoryStore(app).write(StoryLocalState())
        val title="回声测试${System.currentTimeMillis().toString().takeLast(6)}"
        ActivityScenario.launch(StorySubmissionActivity::class.java).use {scenario->
            var ready=false;await {scenario.onActivity {ready=it.window.decorView.findViewWithTag<View>("story_submit")!=null};ready}
            scenario.onActivity {activity->val root=activity.window.decorView
                root.findViewWithTag<EditText>("story_draft_title").setText(title)
                root.findViewWithTag<Spinner>("story_draft_category").setSelection(1)
                root.findViewWithTag<EditText>("story_draft_body").setText(("这是一份明确标记的本地Mock测试故事。雨停后，同学把借来的书还给图书馆，并记下约定。\n\n").repeat(5))
                root.findViewWithTag<CheckBox>("story_draft_rights").isChecked=true
            }
            shot("android-submission")
            scenario.onActivity {it.window.decorView.findViewWithTag<View>("story_submit").performClick();it.window.decorView.findViewWithTag<View>("story_submit").performClick()}
            var success=false;await {scenario.onActivity {success=it.window.decorView.findViewWithTag<View>("story_submission_success")!=null};success}
            shot("android-submission-success");scenario.recreate()
            await {scenario.onActivity {success=it.window.decorView.findViewWithTag<View>("story_submission_success")!=null};success}
        }
        val token=admin();val rows=api("getPendingStories",JSONObject().put("status","pending").put("limit",20),token).getJSONArray("items")
        val own=(0 until rows.length()).map {rows.getJSONObject(it)}.filter {it.getString("title")==title}
        assertEquals("Double tap creates one submission",1,own.size)
        val id=own.single().getString("id")
        val client=StoryCloudClient(app);assertTrue(client.list().items.none {it.title==title})
        val approved=api("approveStory",JSONObject().put("id",id),token);val published=approved.getString("publishedStoryId")
        assertTrue(client.list().items.any {it.id==published && it.title==title})
        val done=CountDownLatch(1);var result:Result<Story>?=null
        StoryRepository.get(app).detail(published){result=it;done.countDown()};assertTrue(done.await(10,TimeUnit.SECONDS));assertTrue(result.toString(),result!!.isSuccess)
        assertTrue(StoryCloudCache(app).read().any {it.id==published})
        prefs.edit().putString("submission",id).putString("published",published).putString("title",title).commit()
        ActivityScenario.launch<StoryReaderActivity>(StoryReaderActivity.intent(app,published).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {scenario->
            var loaded=false;await {scenario.onActivity {loaded=it.window.decorView.findViewWithTag<TextView>("story_reader_body")?.text?.contains("本地Mock")==true};loaded};shot("android-cloud-reading")
        }
    }
    private fun failedSubmission() {
        // Fresh anonymous Mock identity only, to isolate rate budgets from the first fixture.
        app.getSharedPreferences(StoryCloudConfig.identityPrefs,0).edit().putString("installation",java.util.UUID.randomUUID().toString()).commit()
        val draft=StoryDraft("断网草稿${System.currentTimeMillis().toString().takeLast(5)}",StoryCategory.GROWTH,"离线重试会保留同一投稿编号，避免重复记录。".repeat(12),"匿名",true,true)
        StoryStore(app).write(StoryLocalState(draft=draft))
        ActivityScenario.launch(StorySubmissionActivity::class.java).use {s->
            var ready=false;await {s.onActivity {ready=it.window.decorView.findViewWithTag<View>("story_submit")!=null};ready}
            s.onActivity {it.window.decorView.findViewWithTag<View>("story_submit").performClick()}
            var failed=false;await {s.onActivity {val status=it.window.decorView.findViewWithTag<TextView>("story_draft_status");failed=status?.text?.contains("未能确认")==true};failed}
            s.onActivity {assertNull(it.window.decorView.findViewWithTag<View>("story_submission_success"))}
            assertEquals(draft,StoryStore(app).read().draft)
            prefs.edit().putString("retry-id",StoryOutbox(app).prepare(draft)).putString("retry-title",draft.title).commit()
            s.recreate();await {s.onActivity {ready=it.window.decorView.findViewWithTag<EditText>("story_draft_title")?.text?.toString()==draft.title};ready}
            shot("android-submission-network-failure")
        }
    }
    private fun retrySubmission() {
        val title=prefs.getString("retry-title",null)!!;val requestId=prefs.getString("retry-id",null)!!
        ActivityScenario.launch(StorySubmissionActivity::class.java).use {s->
            var ready=false;await {s.onActivity {ready=it.window.decorView.findViewWithTag<View>("story_submit")!=null};ready}
            s.onActivity {it.window.decorView.findViewWithTag<View>("story_submit").performClick()}
            var success=false;await {s.onActivity {success=it.window.decorView.findViewWithTag<View>("story_submission_success")!=null};success}
            s.onActivity {assertTrue(nodes(it.window.decorView).filterIsInstance<TextView>().any {v->v.text.toString().contains(requestId)})}
        }
        val rows=api("getPendingStories",JSONObject().put("status","pending").put("limit",20),admin()).getJSONArray("items")
        assertEquals(1,(0 until rows.length()).count {rows.getJSONObject(it).getString("title")==title})
        assertEquals(StoryDraft(),StoryStore(app).read().draft)
    }
    private fun offlineRead() {
        val id=prefs.getString("published",null)!!
        assertTrue(StoryAsset.load(app.assets).isNotEmpty())
        val done=CountDownLatch(1);var result:Result<Story>?=null
        StoryRepository.get(app).detail(id){result=it;done.countDown()};assertTrue(done.await(20,TimeUnit.SECONDS));assertTrue(result.toString(),result!!.isSuccess)
        ActivityScenario.launch<StoryReaderActivity>(StoryReaderActivity.intent(app,id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {s->var loaded=false;await {s.onActivity {loaded=it.window.decorView.findViewWithTag<TextView>("story_reader_body")?.text?.contains("本地Mock")==true};loaded};shot("android-offline-reading")}
    }
    private fun withdraw() {
        val id=prefs.getString("published",null)!!;val submission=prefs.getString("submission",null)!!
        api("deletePublishedStory",JSONObject().put("id",submission).put("note","Local Mock withdrawal verification"),admin())
        assertTrue(StoryCloudClient(app).list().items.none {it.id==id})
        val done=CountDownLatch(1);var failed=false
        StoryRepository.get(app).detail(id){failed=it.isFailure;done.countDown()};assertTrue(done.await(10,TimeUnit.SECONDS));assertTrue(failed)
        assertTrue(StoryCloudCache(app).read().none {it.id==id})
    }
}
