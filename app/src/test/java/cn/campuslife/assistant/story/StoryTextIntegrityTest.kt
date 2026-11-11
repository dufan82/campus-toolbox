package cn.campuslife.assistant.story

import org.junit.Assert.*
import org.junit.Test

class StoryTextIntegrityTest {
    private val text="第一段。\n\n最后一句。"
    private val sha="6f6cba930ee842016552b45360e80c1f6194bce3e4da4927ebd306ed228f3542"
    @Test fun exactBodyAndParagraphsMustMatchTheRecordedSourceCopy() {
        assertEquals(sha,StoryTextIntegrity.digest(text))
        assertTrue(StoryTextIntegrity.matches(text,sha,11,2))
        assertFalse(StoryTextIntegrity.matches("第一段。",sha,11,2))
        assertFalse(StoryTextIntegrity.matches(text.replace("最后","另一"),sha,11,2))
        assertFalse(StoryTextIntegrity.matches(text,sha,12,2))
        assertFalse(StoryTextIntegrity.matches(text,sha,11,1))
    }
    @Test fun unicodeCharactersAreCountedAsCodePointsAndBlankTextIsNotACompleteArticle() {
        val emoji="😀。\n\n末尾。"
        assertTrue(StoryTextIntegrity.matches(emoji,StoryTextIntegrity.digest(emoji),7,2))
        assertFalse(StoryTextIntegrity.matches("",StoryTextIntegrity.digest(""),0,0))
    }
}
