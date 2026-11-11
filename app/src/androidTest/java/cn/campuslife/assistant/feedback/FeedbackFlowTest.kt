package cn.campuslife.assistant.feedback

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.ui.SettingsActivity
import org.junit.Assert.*
import org.junit.Test

/** Offline only: external Intents are captured, never dispatched to a browser or real form. */
class FeedbackFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun settingsHasSmallRightAlignedEntryAndOpensPublishedFeedback() {
        assertEquals("https://wj.qq.com/s2/28616104/3ezx/", FeedbackConfig.FEEDBACK_FORM_URL)
        val monitor = instrumentation.addMonitor(android.content.IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_BROWSABLE); addDataScheme("https") },
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    val entry = root.findViewWithTag<Button>("settings_feedback")
                    assertNotNull(entry)
                    assertEquals("意见反馈", entry.text.toString())
                    assertTrue(entry.isClickable && entry.isEnabled)
                    assertNotNull(entry.compoundDrawablesRelative[0])
                    val layout = entry.layoutParams as LinearLayout.LayoutParams
                    assertEquals(Gravity.END, layout.gravity)
                    assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, layout.width)
                    val about = root.findViewWithTag<View>("settings_about")
                    val entryBounds = Rect(0, 0, entry.width, entry.height)
                    val aboutBounds = Rect(0, 0, about.width, about.height)
                    val body = entry.parent as ViewGroup
                    body.offsetDescendantRectToMyCoords(entry, entryBounds)
                    body.offsetDescendantRectToMyCoords(about, aboutBounds)
                    assertTrue(entryBounds.top >= aboutBounds.bottom)
                    entry.performClick()
                }
                assertEquals(1, monitor.hits)
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun fallbackDraftListsAllTypesAndHasNoSubmitOrSuccess() {
        ActivityScenario.launch(FeedbackActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertEquals("暂时无法打开反馈表单", root.findViewWithTag<TextView>("feedback_availability").text.toString())
                assertEquals(FeedbackCategory.entries.map { it.label }, views(root).filterIsInstance<RadioButton>().map { it.text.toString() })
                assertFalse(views(root).filterIsInstance<RadioButton>().any { it.isChecked })
                assertFalse(views(root).filterIsInstance<Button>().any { it.text.toString().contains("提交") })
                assertFalse(views(root).filterIsInstance<TextView>().any { it.text.toString().contains("提交成功") })
                assertEquals("", root.findViewWithTag<EditText>("feedback_content").text.toString())
                assertEquals("", root.findViewWithTag<EditText>("feedback_contact").text.toString())
            }
        }
    }

    @Test fun draftRequiresTypeAndContentButContactMayRemainAnonymous() {
        ActivityScenario.launch(FeedbackActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val check = root.findViewWithTag<Button>("feedback_check_draft")
                val status = root.findViewWithTag<TextView>("feedback_draft_status")
                check.performClick()
                assertEquals("请选择反馈类型", status.text.toString())
                root.findViewWithTag<RadioButton>("feedback_category_OTHER").performClick()
                check.performClick()
                assertEquals("请填写反馈内容", root.findViewWithTag<EditText>("feedback_content").error.toString())
                root.findViewWithTag<EditText>("feedback_content").setText("希望工具更好用")
                check.performClick()
                assertEquals("格式正确，尚未提交。", status.text.toString())
                assertTrue(views(root).filterIsInstance<TextView>().any { it.text.toString() == "此页不会提交；联系方式可留空。" })
                assertEquals("", root.findViewWithTag<EditText>("feedback_contact").text.toString())
            }
        }
    }

    @Test fun contentFilterCapsPastedTextAndDraftSurvivesActivityRecreation() {
        ActivityScenario.launch(FeedbackActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                root.findViewWithTag<RadioButton>("feedback_category_SCHEDULE").performClick()
                val content = root.findViewWithTag<EditText>("feedback_content")
                content.setText("字".repeat(1001))
                assertEquals(1000, content.length())
                assertEquals("1000/1000", root.findViewWithTag<TextView>("feedback_content_count").text.toString())
                root.findViewWithTag<EditText>("feedback_contact").setText("student@example.invalid")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertTrue(root.findViewWithTag<RadioButton>("feedback_category_SCHEDULE").isChecked)
                assertEquals("字".repeat(1000), root.findViewWithTag<EditText>("feedback_content").text.toString())
                assertEquals("student@example.invalid", root.findViewWithTag<EditText>("feedback_contact").text.toString())
            }
        }
    }

    @Test fun configuredHttpsOpensOnlyBareExternalFormIntentWithoutPrivatePayload() {
        val context = CapturingContext()
        val url = "https://survey.example.invalid/form?id=public-test#start"
        FeedbackLauncher.open(context, url)
        val intent = context.started.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(intent.extras)
        assertNull(intent.clipData)
        assertNull(intent.component)
        assertNull(intent.`package`)
    }

    @Test fun emptyAndInvalidConfigurationsOnlyOpenLocalExplanatoryForm() {
        listOf("", "http://survey.example.invalid/form", "javascript:alert(1)").forEach { url ->
            val context = CapturingContext()
            FeedbackLauncher.open(context, url)
            val intent = context.started.single()
            assertEquals(FeedbackActivity::class.java.name, intent.component?.className)
            assertNull(intent.data)
            assertNull(intent.clipData)
        }
    }

    @Test fun missingOrDeniedBrowserFallsBackLocallyWithoutClaimingSubmission() {
        listOf(ActivityNotFoundException(), SecurityException()).forEach { error ->
            val context = CapturingContext(error)
            FeedbackLauncher.open(context, "https://survey.example.invalid/form")
            assertEquals(2, context.started.size)
            assertEquals(FeedbackActivity::class.java.name, context.started.last().component?.className)
            assertNull(context.started.last().data)
            assertEquals(FeedbackLauncher.BROWSER_UNAVAILABLE,
                context.started.last().getStringExtra(FeedbackLauncher.EXTRA_UNAVAILABLE_REASON))
        }
    }

    private class CapturingContext(private val browserFailure: RuntimeException? = null) :
        ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        val started = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            started += Intent(intent)
            if (intent.action == Intent.ACTION_VIEW && browserFailure != null) throw browserFailure
        }
    }

    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
