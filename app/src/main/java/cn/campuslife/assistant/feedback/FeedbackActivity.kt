package cn.campuslife.assistant.feedback

import android.app.Activity
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import cn.campuslife.assistant.ui.Ui

/** Explanatory local draft only. There is intentionally no submit, upload or network operation. */
class FeedbackActivity : Activity() {
    private var draft = FeedbackDraft()
    private lateinit var content: EditText
    private lateinit var contact: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft = FeedbackDraft(
            savedInstanceState?.getString("feedback_category")?.let { name ->
                FeedbackCategory.entries.firstOrNull { it.name == name }
            },
            savedInstanceState?.getString("feedback_content").orEmpty(),
            savedInstanceState?.getString("feedback_contact").orEmpty()
        )
        val root = Ui.page(this)
        root.addView(Ui.header(this, "意见反馈"))
        val body = Ui.column(this).apply {
            setPadding(Ui.dp(context, 20), Ui.dp(context, 18), Ui.dp(context, 20), Ui.dp(context, 24))
        }
        root.addView(Ui.scroll(this, body), LinearLayout.LayoutParams(-1, 0, 1f))
        val reason = intent.getStringExtra(FeedbackLauncher.EXTRA_UNAVAILABLE_REASON)
        val configured = FeedbackRules.formUrl(FeedbackConfig.FEEDBACK_FORM_URL) != null
        val availability = if (configured || reason == FeedbackLauncher.BROWSER_UNAVAILABLE || reason == FeedbackLauncher.INVALID_CONFIGURATION)
            "暂时无法打开反馈表单" else "反馈入口暂未开放"
        body.addView(Ui.text(this, availability, 17f, bold = true).apply { tag = "feedback_availability" })
        val explanation = when (reason) {
            FeedbackLauncher.INVALID_CONFIGURATION -> "反馈链接配置无效，暂时无法打开在线表单。"
            FeedbackLauncher.BROWSER_UNAVAILABLE -> "未能打开在线表单，请检查是否有可用浏览器后重试。"
            else -> if (configured) "可从“我的”重新打开在线反馈。" else "在线反馈暂未开放。"
        }
        body.addView(Ui.space(this, 10))
        body.addView(Ui.text(this, "$explanation 此页仅供草拟，离开后不保留。", 14f, Ui.muted))
        body.addView(Ui.space(this, 22))
        body.addView(Ui.text(this, "反馈类型（必选）", 15f, bold = true))
        val categoryError = Ui.text(this, "请选择反馈类型", 13f).apply {
            tag = "feedback_category_error"; visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val categories = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; tag = "feedback_categories" }
        FeedbackCategory.entries.forEach { category ->
            categories.addView(RadioButton(this).apply {
                id = View.generateViewId(); tag = "feedback_category_${category.name}"
                text = category.label; textSize = 15f; setTextColor(Ui.ink)
                minimumHeight = Ui.dp(context, 48)
                isChecked = category == draft.category
            }, RadioGroup.LayoutParams(-1, -2))
        }
        categories.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<RadioButton>(checkedId)?.tag?.toString()
            draft = draft.copy(category = FeedbackCategory.entries.firstOrNull { "feedback_category_${it.name}" == selected })
            categoryError.visibility = View.GONE
        }
        body.addView(categories); body.addView(categoryError); body.addView(Ui.space(this, 16))
        body.addView(Ui.text(this, "反馈内容（必填，最多1000字）", 15f, bold = true))
        body.addView(Ui.space(this, 10))
        val counter = Ui.text(this, "${draft.content.length}/1000", 12f, Ui.muted).apply {
            tag = "feedback_content_count"; gravity = Gravity.END
        }
        content = Ui.input(this, "", "请具体描述问题和期望改进，不要填写密码等敏感信息", multiline = true) {
            draft = draft.copy(content = it)
            counter.text = getString(cn.campuslife.assistant.R.string.feedback_content_count,it.length)
        }.apply {
            tag = "feedback_content"; contentDescription = "反馈内容，必填，最多1000字"
            filters = arrayOf(InputFilter.LengthFilter(FeedbackRules.MAX_CONTENT_LENGTH))
            minLines = 4; maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setText(draft.content)
        }
        body.addView(content, LinearLayout.LayoutParams(-1, -2)); body.addView(Ui.space(this, 6)); body.addView(counter)
        body.addView(Ui.space(this, 18)); body.addView(Ui.text(this, "联系方式（选填，QQ或邮箱）", 15f, bold = true))
        body.addView(Ui.space(this, 10))
        contact = Ui.input(this, "", "可以留空，支持匿名反馈") { draft = draft.copy(contact = it) }.apply {
            tag = "feedback_contact"; contentDescription = "联系方式，选填，QQ或邮箱，留空为匿名"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setText(draft.contact)
        }
        body.addView(contact, LinearLayout.LayoutParams(-1, -2))
        body.addView(Ui.space(this, 18))
        body.addView(Ui.text(this, "此页不会提交；联系方式可留空。", 13f, Ui.muted))
        body.addView(Ui.space(this, 16))
        val status = Ui.text(this, "", 14f).apply {
            tag = "feedback_draft_status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(Ui.textButton(this, "检查草稿") {
            val error = FeedbackRules.validate(draft)
            categoryError.visibility = if (error == FeedbackError.CATEGORY_REQUIRED) View.VISIBLE else View.GONE
            content.error = if (error == FeedbackError.CONTENT_REQUIRED || error == FeedbackError.CONTENT_TOO_LONG) error.message else null
            status.text = error?.message ?: "格式正确，尚未提交。"
            if (error == FeedbackError.CONTENT_REQUIRED || error == FeedbackError.CONTENT_TOO_LONG) content.requestFocus()
        }.apply { tag = "feedback_check_draft" }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        body.addView(status)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("feedback_category", draft.category?.name)
        outState.putString("feedback_content", draft.content)
        outState.putString("feedback_contact", draft.contact)
        super.onSaveInstanceState(outState)
    }
}
