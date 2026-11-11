package cn.campuslife.assistant.story

import org.junit.Assert.*
import org.junit.Test

class StoryRulesTest {
    private fun story(id: String, category: StoryCategory) = Story(id, "标题", "简介", category, "正文")
    private fun valid() = StoryDraft("一封信", StoryCategory.LIFE, "字".repeat(100), "", true, true)

    @Test fun draftBoundariesAndBothConsentsAreRequired() {
        assertTrue(StoryRules.validate(valid()).isEmpty())
        assertTrue(StoryRules.validate(valid().copy(title="字".repeat(30), body="字".repeat(5000))).isEmpty())
        assertTrue(StoryDraftError.TITLE in StoryRules.validate(valid().copy(title="字")))
        assertTrue(StoryDraftError.TITLE in StoryRules.validate(valid().copy(title="字".repeat(31))))
        assertTrue(StoryDraftError.BODY in StoryRules.validate(valid().copy(body="字".repeat(99))))
        assertTrue(StoryDraftError.BODY in StoryRules.validate(valid().copy(body="字".repeat(5001))))
        assertTrue(StoryDraftError.CATEGORY in StoryRules.validate(valid().copy(category=null)))
        assertTrue(StoryDraftError.ORIGINAL_RIGHTS in StoryRules.validate(valid().copy(originalRights=false)))
        assertTrue(StoryDraftError.DISPLAY_PERMISSION in StoryRules.validate(valid().copy(displayPermission=false)))
    }
    @Test fun unicodeCountingTrimsAndDoesNotCountSurrogatePairsTwice() {
        assertEquals(100, StoryRules.characterCount("😀".repeat(100)))
        assertTrue(StoryRules.validate(valid().copy(title="  两字  ",body=" 😀".trim().repeat(100))).isEmpty())
        assertEquals("匿名", valid().nicknameLabel)
        assertEquals("笔名", valid().copy(nickname=" 笔名 ").nicknameLabel)
    }
    @Test fun filtersUseStableIdsAndIgnoreUnknownFavoritesWithoutErasingThem() {
        val entries=listOf(story("story-001",StoryCategory.LIFE),story("story-002",StoryCategory.WARMTH))
        val saved=setOf("story-002","story-future-999")
        assertEquals(listOf(entries[1]),StoryRules.filter(entries,StoryCategory.WARMTH,true,saved))
        assertTrue(StoryRules.filter(entries,StoryCategory.LIFE,true,saved).isEmpty())
        assertEquals(2,saved.size)
        assertEquals(entries[1],StoryRules.next(entries,"story-001"))
        assertEquals(entries[0],StoryRules.next(entries,"story-unknown"))
        assertNull(StoryRules.next(emptyList(),"story-001"))
    }
    @Test fun progressAndFontAreBoundedAndIdsAreNotPoemIds() {
        assertEquals(0.0,StoryRules.progress(Double.NaN),0.0)
        assertEquals(1.0,StoryRules.progress(8.0),0.0)
        assertEquals(0.0,StoryRules.progress(-1.0),0.0)
        assertEquals(16,StoryRules.fontSize(3))
        assertEquals(18,StoryRules.fontSize(25))
        assertTrue(StoryRules.validId("story-future-999"))
        assertFalse(StoryRules.validId("poem-001"))
        assertFalse(StoryRules.validId("../../profile"))
    }
    @Test fun publicSubmissionRequiresStandaloneHttpsAndNeverUsesFeedbackConfig() {
        assertEquals("",StorySubmissionConfig.PUBLIC_FORM_URL)
        assertNull(StoryRules.publicFormUrl(""))
        listOf("http://example.com/story","javascript:alert(1)","https://user:pass@example.com/story","https://localhost/story","https://example.com/\n").forEach {
            assertNull(it,StoryRules.publicFormUrl(it))
        }
        assertEquals("https://example.com/story",StoryRules.publicFormUrl("https://example.com/story"))
    }
}
