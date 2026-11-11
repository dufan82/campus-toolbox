package cn.campuslife.assistant.story

import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Fully offline: instance-local loaders, no Mock server, cloud config, or production store writes. */
class StoryCloudReaderRaceDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val local=Story("story-001","本机起点","简介",StoryCategory.LIFE,"本机故事。".repeat(100),provenance=StoryProvenance(sourceType="user_submission",rights=StoryRights.USER_AUTHORIZED))
    private val cloudB=Story("story-cloud-reader-race-b","云端乙","简介",StoryCategory.GROWTH,"云端乙的完整故事。\n\n".repeat(100),provenance=StoryProvenance(sourceType="user_submission",rights=StoryRights.USER_AUTHORIZED))
    private val cloudC=Story("story-cloud-reader-race-c","云端丙","简介",StoryCategory.WARMTH,"云端丙的完整故事。\n\n".repeat(100),provenance=StoryProvenance(sourceType="user_submission",rights=StoryRights.USER_AUTHORIZED))

    private class Pending(val id:String,val complete:(Result<Story>)->Unit)
    private class FakeLoader(private val stories:List<Story>):StoryReaderActivity.Loader {
        val requests=mutableListOf<Pending>()
        var state=StoryLocalState(fontSp=18)
        override fun load(complete:(Result<Pair<List<Story>,StoryLocalState>>)->Unit) {
            complete(Result.success(stories to state))
        }
        override fun detail(id:String,complete:(Result<Story>)->Unit) {requests+=Pending(id,complete)}
        override fun change(transform:(StoryLocalState)->StoryLocalState,complete:(Result<StoryLocalState>)->Unit) {
            state=transform(state);complete(Result.success(state))
        }
    }
    private fun loader()=FakeLoader(listOf(local,cloudB.copy(body=""),cloudC.copy(body="")))
    private fun await(scenario:ActivityScenario<StoryReaderActivity>,check:(StoryReaderActivity)->Boolean) {
        val until=SystemClock.uptimeMillis()+8000
        do {
            var done=false
            scenario.onActivity {done=check(it)}
            if(done)return
            SystemClock.sleep(25)
        } while(SystemClock.uptimeMillis()<until)
        throw AssertionError("Reader condition timed out")
    }
    private fun title(activity:StoryReaderActivity)=activity.window.decorView.findViewWithTag<TextView>("story_reader_title")?.text?.toString()
    private fun status(activity:StoryReaderActivity)=activity.window.decorView.findViewWithTag<TextView>("story_reader_status")?.text?.toString()
    private fun withReader(loader:FakeLoader,block:(ActivityScenario<StoryReaderActivity>)->Unit) {
        // A bundled initial ID prevents any real detail request before this instance is injected.
        ActivityScenario.launch<StoryReaderActivity>(StoryReaderActivity.intent(context,"story-001")).use {scenario->
            scenario.onActivity {it.useLoaderForTesting(loader)}
            await(scenario) {title(it)==local.title}
            block(scenario)
        }
    }
    private fun reply(loader:FakeLoader,index:Int,result:Result<Story>) {
        instrumentation.runOnMainSync {loader.requests[index].complete(result)}
        instrumentation.waitForIdleSync()
    }

    @Test fun rapidNextOnlyStartsOneCloudLoad() {
        val loader=loader()
        withReader(loader) {scenario->
            scenario.onActivity {activity->
                val next=activity.window.decorView.findViewWithTag<Button>("story_next")
                next.performClick();next.performClick()
                assertFalse(next.isEnabled)
                assertFalse(activity.window.decorView.findViewWithTag<Spinner>("story_font_size").isEnabled)
                assertFalse(activity.window.decorView.findViewWithTag<Button>("story_favorite").isEnabled)
                assertEquals(1,loader.requests.size)
                assertEquals(cloudB.id,loader.requests.single().id)
            }
            reply(loader,0,Result.success(cloudB))
            await(scenario) {title(it)==cloudB.title}
        }
    }

    @Test fun oldCloudSuccessCannotReplaceNewLocalNavigation() {
        val loader=loader()
        withReader(loader) {scenario->
            scenario.onActivity {it.openStory(cloudB.id,0.0);it.openStory(local.id,0.0)}
            reply(loader,0,Result.success(cloudB))
            scenario.onActivity {assertEquals(local.title,title(it));assertEquals("",status(it))}
        }
    }

    @Test fun oldCloudFailureCannotReplaceNewCloudSuccess() {
        val loader=loader()
        withReader(loader) {scenario->
            scenario.onActivity {it.openStory(cloudB.id,0.0);it.openStory(cloudC.id,0.0)}
            reply(loader,1,Result.success(cloudC))
            await(scenario) {title(it)==cloudC.title}
            reply(loader,0,Result.failure(IOException("旧请求失败，不应显示")))
            scenario.onActivity {
                assertEquals(cloudC.title,title(it));assertEquals("",status(it))
                assertTrue(it.window.decorView.findViewWithTag<Button>("story_favorite").isEnabled)
            }
        }
    }

    @Test fun sameTargetIdStillRejectsOlderRequestGeneration() {
        val loader=loader()
        withReader(loader) {scenario->
            scenario.onActivity {it.openStory(cloudB.id,0.0);it.openStory(cloudB.id,0.0)}
            val newest=cloudB.copy(title="乙的最新响应")
            reply(loader,1,Result.success(newest))
            await(scenario) {title(it)==newest.title}
            reply(loader,0,Result.success(cloudB.copy(title="乙的过期响应")))
            scenario.onActivity {assertEquals(newest.title,title(it))}
        }
    }

    @Test fun recreationKeepsPendingTargetRatioAndRejectsDestroyedActivityResponse() {
        val loader=loader()
        withReader(loader) {scenario->
            scenario.onActivity {it.openStory(cloudB.id,0.6)}
            scenario.recreate()
            await(scenario) {loader.requests.size==2}
            scenario.onActivity {assertEquals(cloudB.id,loader.requests[1].id)}
            reply(loader,0,Result.success(cloudB.copy(title="旧Activity响应")))
            scenario.onActivity {assertNull(title(it));assertEquals("正在读取…",status(it))}
            reply(loader,1,Result.success(cloudB))
            await(scenario) {activity->
                val scroll=activity.window.decorView.findViewWithTag<ScrollView>("story_reader_scroll")
                val range=scroll.getChildAt(0).height-scroll.height+scroll.paddingTop+scroll.paddingBottom
                title(activity)==cloudB.title && range>0 && scroll.scrollY.toDouble()/range in 0.55..0.65
            }
            scenario.onActivity {assertEquals(2,it.window.decorView.findViewWithTag<Spinner>("story_font_size").selectedItemPosition)}
        }
    }

    @Test fun failedRecreatedTargetRetriesThatTargetOnNextRecreation() {
        val loader=loader()
        withReader(loader) {scenario->
            scenario.onActivity {it.openStory(cloudB.id,0.4)}
            scenario.recreate()
            await(scenario) {loader.requests.size==2}
            reply(loader,1,Result.failure(IOException("本次目标读取失败")))
            await(scenario) {status(it)=="本次目标读取失败"}
            scenario.onActivity {assertNull(it.window.decorView.findViewWithTag<View>("story_reader_body"))}
            scenario.recreate()
            await(scenario) {loader.requests.size==3}
            scenario.onActivity {assertEquals(cloudB.id,loader.requests[2].id)}
            reply(loader,0,Result.success(cloudB.copy(title="更早Activity的迟到响应")))
            scenario.onActivity {assertNull(title(it))}
            reply(loader,2,Result.success(cloudB))
            await(scenario) {title(it)==cloudB.title}
        }
    }
}
