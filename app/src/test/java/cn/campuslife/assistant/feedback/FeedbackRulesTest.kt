package cn.campuslife.assistant.feedback

import org.junit.Assert.*
import org.junit.Test

/** Pure JVM tests: no Android APIs, network, school accounts or real survey requests. */
class FeedbackRulesTest {
    @Test fun publishedFeedbackIsConfiguredAndEmptyConfigurationStillFallsBack() {
        assertEquals("https://wj.qq.com/s2/28616104/3ezx/", FeedbackConfig.FEEDBACK_FORM_URL)
        assertEquals(FeedbackConfig.FEEDBACK_FORM_URL, FeedbackRules.formUrl(FeedbackConfig.FEEDBACK_FORM_URL))
        assertNull(FeedbackRules.formUrl(""))
    }

    @Test fun categoriesMatchTheRequestedListAndHaveNoDefaultSelection() {
        assertEquals(listOf("功能建议", "功能异常", "课表问题", "界面设计建议", "其他"),
            FeedbackCategory.entries.map { it.label })
        assertNull(FeedbackDraft().category)
    }

    @Test fun onlyValidHttpsFormUrlsAreAccepted() {
        listOf("", " ", "http://survey.example/form", "javascript:alert(1)",
            "intent://survey.example/form", "file:///survey", "https:survey.example/form",
            "https:///form", "https://", "https://survey.example/%zz", "https://survey.example\\form",
            "https://user:password@survey.example/form", "https://survey.example:0/form",
            "https://survey.example:65536/form", "https://survey.example/form\n").forEach {
            assertNull("Must reject unsafe or malformed configuration", FeedbackRules.formUrl(it))
        }
        assertEquals("https://survey.example/form", FeedbackRules.formUrl(" https://survey.example/form "))
    }

    @Test fun formUrlIsUsedAsConfiguredWithoutPrefillingAnyDraftOrIdentity() {
        val url = "https://survey.example/form?id=public-form&lang=zh#start"
        assertEquals(url, FeedbackRules.formUrl(url))
        assertEquals("https://survey.example/form", FeedbackRules.formUrl("HTTPS://survey.example/form"))
    }

    @Test fun categoryIsRequiredEvenWhenContentIsPresent() {
        assertEquals(FeedbackError.CATEGORY_REQUIRED, FeedbackRules.validate(FeedbackDraft(content = "改进建议")))
    }

    @Test fun blankOrWhitespaceContentCannotPassLocalDraftCheck() {
        listOf("", " ", "\n\t", "　").forEach {
            assertEquals(FeedbackError.CONTENT_REQUIRED,
                FeedbackRules.validate(FeedbackDraft(FeedbackCategory.OTHER, it)))
        }
    }

    @Test fun contentLimitIsEnforcedAtExactlyOneThousand() {
        assertEquals(1000, FeedbackRules.MAX_CONTENT_LENGTH)
        assertNull(FeedbackRules.validate(FeedbackDraft(FeedbackCategory.OTHER, "字".repeat(1000))))
        assertEquals(FeedbackError.CONTENT_TOO_LONG,
            FeedbackRules.validate(FeedbackDraft(FeedbackCategory.OTHER, "字".repeat(1001))))
        assertEquals(FeedbackError.CONTENT_TOO_LONG,
            FeedbackRules.validate(FeedbackDraft(FeedbackCategory.OTHER, " ".repeat(1000) + "字")))
    }

    @Test fun allCategoriesSupportAnonymousFeedbackAndOptionalQqOrEmail() {
        FeedbackCategory.entries.forEach { category ->
            listOf("", "12345678", "student@example.invalid").forEach { contact ->
                assertNull(FeedbackRules.validate(FeedbackDraft(category, "仅测试本地草稿", contact)))
            }
        }
    }
}
