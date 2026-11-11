package cn.campuslife.assistant.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import cn.campuslife.assistant.feedback.FeedbackActivity
import cn.campuslife.assistant.gate.GateActivity
import org.junit.Assert.*
import org.junit.Test

/** Offline UI only; does not save profiles, open gate routes, initialize maps or request permissions. */
class UiCopyScreensTest {
    private fun nodes(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
    private fun texts(root: View) = nodes(root).filterIsInstance<TextView>().map { it.text.toString() }

    @Test fun profileUsesOneShortStudentIdHintWithoutRepeatedStoragePledge() {
        ActivityScenario.launch(ProfileActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val shown = texts(activity.window.decorView)
                assertTrue(shown.contains("学号仅在勾选后显示。"))
                assertTrue(shown.contains("勾选“展示”后加入水印，空项跳过。"))
                assertFalse(shown.any { it.contains("已有选择不变") || it.contains("资料只保存在本机") })
                assertNotNull(activity.window.decorView.findViewWithTag<Button>("save_profile"))
            }
        }
    }

    @Test fun gateKeepsFourActionsAndFieldUseWarningWithoutLongTutorial() {
        ActivityScenario.launch(GateActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val shown = texts(root)
                assertTrue(shown.contains("将入口发给微信文件传输助手，再点开链接。"))
                assertTrue(shown.contains("请在校门现场使用，通行结果以学校官方系统为准。"))
                assertFalse(shown.any { it.contains("选择文件传输助手或自己的聊天，确认发送") })
                listOf("gate_east_in", "gate_east_out", "gate_north_in", "gate_north_out", "gate_open_wechat").forEach {
                    assertNotNull(it, root.findViewWithTag<View>(it))
                }
            }
        }
    }

    @Test fun feedbackStillValidatesLocallyAndNeverClaimsSubmission() {
        ActivityScenario.launch(FeedbackActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                assertTrue(texts(root).contains("此页不会提交；联系方式可留空。"))
                root.findViewWithTag<RadioButton>("feedback_category_OTHER").performClick()
                root.findViewWithTag<EditText>("feedback_content").setText("离线界面文案测试")
                root.findViewWithTag<Button>("feedback_check_draft").performClick()
                assertEquals("格式正确，尚未提交。", root.findViewWithTag<TextView>("feedback_draft_status").text.toString())
                assertFalse(nodes(root).filterIsInstance<Button>().any { it.text.toString().contains("提交") })
            }
        }
    }
}
