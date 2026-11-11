package cn.campuslife.assistant.story

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Intercepts every Intent; no example form, feedback form or network request is ever opened. */
class StorySubmissionIntentTest {
    private class Recorder(context:Context):Activity() {
        val intents=mutableListOf<Intent>()
        var reject=false
        init {attachBaseContext(context)}
        override fun startActivity(intent:Intent) {intents+=intent;if(reject && intent.action==Intent.ACTION_VIEW)throw ActivityNotFoundException("Offline test")}
    }
    @Test fun emptyProductionConfigurationOnlyOpensLocalDraft() {
        val inst=InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync {
            assertEquals("",StorySubmissionConfig.PUBLIC_FORM_URL)
            val activity=Recorder(inst.targetContext);StorySubmissionLauncher.open(activity)
            assertEquals(1,activity.intents.size)
            assertEquals(StorySubmissionActivity::class.java.name,activity.intents.single().component!!.className)
            assertNull(activity.intents.single().data);assertNull(activity.intents.single().action)
        }
    }
    @Test fun configuredFormOpensDirectlyWithoutPrefillingOrSendingDraftAndFailureReturnsLocally() {
        val inst=InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync {
            val activity=Recorder(inst.targetContext)
            StorySubmissionLauncher.open(activity,"https://example.org/offline-form")
            assertEquals(1,activity.intents.size)
            val intent=activity.intents.single()
            if(StoryCloudConfig.configured) {
                assertEquals(StorySubmissionActivity::class.java.name,intent.component!!.className)
                assertNull(intent.action);assertNull(intent.data);return@runOnMainSync
            }
            assertEquals(Intent.ACTION_VIEW,intent.action);assertEquals("https://example.org/offline-form",intent.dataString)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE));assertNull(intent.extras)
            activity.intents.clear();activity.reject=true
            StorySubmissionLauncher.open(activity,"https://example.org/offline-form")
            assertEquals(2,activity.intents.size)
            val fallback=activity.intents.last()
            assertEquals(StorySubmissionActivity::class.java.name,fallback.component!!.className)
            assertEquals(StorySubmissionLauncher.BROWSER_UNAVAILABLE,fallback.getStringExtra(StorySubmissionLauncher.EXTRA_UNAVAILABLE_REASON))
        }
    }
}
