package cn.campuslife.assistant.story

import org.junit.Assert.*
import org.junit.Test

class StoryColumnTest {
    private fun story(type:String,id:String="story-cloud-example")=Story(id,"标题","简介",StoryCategory.LIFE,"正文",
        provenance=StoryProvenance(sourceType=type,rights=StoryRights.AUTHORIZED))
    @Test fun networkContainsOnlyDouyinNotUnrelatedBlogsOrClassics() {
        for(type in listOf("douyin_post","douyin_image","douyin_comment"))assertEquals(StoryColumn.NETWORK,StoryColumn.of(story(type)))
        for(type in listOf("other_platform","legacy_original","unknown"))assertEquals(StoryColumn.LITERATURE,StoryColumn.of(story(type)))
    }
    @Test fun reviewedSubmissionsAreNotMistakenForEditorialCloudWorks() {
        assertEquals(StoryColumn.SUBMISSIONS,StoryColumn.of(story("user_submission")))
        assertEquals(StoryColumn.NETWORK,StoryColumn.of(story("douyin_image")))
        assertEquals(StoryColumn.LITERATURE,StoryColumn.of(story("other_platform")))
    }
    @Test fun classificationDoesNotMutateIdentityRightsOrReadingState() {
        val a=story("douyin_image","story-suiji-002");val copy=a.copy()
        StoryColumn.of(a);assertEquals(copy,a)
        assertEquals(StoryColumn.NETWORK,StoryColumn.fromKey(null));assertEquals(StoryColumn.NETWORK,StoryColumn.fromKey("removed-filter"))
        assertEquals(StoryColumn.LITERATURE,StoryColumn.fromKey("literature"))
    }
}
