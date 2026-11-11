package cn.campuslife.assistant.story

import org.junit.Assert.*
import org.junit.Test

class StoryProvenanceTest {
    @Test fun readingListRequiresAFullArticleButKeepsRealCloudPublicationsAndOldFavorites() {
        val pending=entry()
        val authorized=entry("story-suiji-005",StoryRights.AUTHORIZED)
        val cloudSummary=entry("story-cloud-reviewed",StoryRights.USER_AUTHORIZED).copy(body="")
        assertEquals(listOf(authorized,cloudSummary),StoryRules.filter(listOf(pending,authorized,cloudSummary),null,false,emptySet()))
        assertEquals(listOf(pending),StoryRules.filter(listOf(pending),null,true,setOf(pending.id)))
        assertEquals(authorized,StoryRules.adjacent(listOf(pending,authorized),pending.id,1))
    }
    private fun entry(id:String="story-suiji-001",rights:StoryRights=StoryRights.UNVERIFIED)=Story(
        id,"真实标题","独立介绍",StoryCategory.LIFE,"不得在未授权界面出现的完整正文", "原作者","原始来源",
        provenance=StoryProvenance(sourceType="douyin_image",sourceUrl="https://www.douyin.com/note/7422916853426621732",rights=rights,excerpt="必要短摘录"))
    @Test fun unverifiedAndRevokedContentCannotBeRenderedOrPersistedAsFullText() {
        for(rights in listOf(StoryRights.UNVERIFIED,StoryRights.EXCERPT_ONLY,StoryRights.REVOKED)) {
            val story=entry(rights=rights)
            assertEquals("必要短摘录",story.readableBody)
            assertEquals("",story.publicCopy().body)
        }
        assertEquals(StoryRights.UNVERIFIED,StoryRights.fromWire("anything-new"))
    }
    @Test fun authorizedBodyIsKeptVerbatimIncludingItsEnding() {
        val body="第一段。\n\n没有多余解释的最后一句。"
        assertEquals(body,entry(rights=StoryRights.AUTHORIZED).copy(body=body).readableBody)
    }
    @Test fun aPrivateLayoutPreviewDoesNotGrantPublicRightsOrLeakIntoCache() {
        val preview=entry().copy(privateReview=true)
        assertEquals(preview.body,preview.readableBody)
        assertFalse(preview.provenance.allowsFullText)
        assertEquals("",preview.publicCopy().body)
        assertFalse(preview.publicCopy().privateReview)
    }
    @Test fun explicitHttpsSourcesCanHaveCommentFragmentsButNotExecutableOrLocalTargets() {
        assertEquals("https://example.org/work#comment-4",StoryProvenance.publicSourceUrl("https://example.org/work#comment-4"))
        listOf("http://example.org/a","javascript:alert(1)","https://name:password@example.org/a","https://127.0.0.1/a","https://example.org/a\n").forEach {
            assertNull(it,StoryProvenance.publicSourceUrl(it))
        }
    }
    @Test fun theFirstThreeArePinnedWhileTheOtherEditorialRanksStayStable() {
        val list=listOf(entry("story-other").copy(provenance=StoryProvenance(editorialRank=0)),entry("story-suiji-003"),entry("story-suiji-002"),entry())
        assertEquals(listOf("story-suiji-001","story-suiji-002","story-suiji-003","story-other"),StoryRules.ordered(list).map {it.id})
        assertNull(StoryRules.adjacent(StoryRules.ordered(list),"story-suiji-001",-1))
    }
    @Test fun archivedExamplesLeaveThePublicListButExistingFavoritesStillOpen() {
        val old=entry("story-001",StoryRights.AUTHORIZED).copy(provenance=StoryProvenance(sourceType="legacy_original",rights=StoryRights.AUTHORIZED,archived=true))
        assertTrue(StoryRules.filter(listOf(old),null,false,setOf(old.id)).isEmpty())
        assertEquals(listOf(old),StoryRules.filter(listOf(old),null,true,setOf(old.id)))
    }
    @Test fun anAdminPublicationForTheSameSourceKeepsTheCuratedFavoriteIdAndUsesRemoteDetail() {
        val seed=entry().copy(provenance=entry().provenance.copy(editorialRank=1))
        val remote=entry("story-cloud-editorial-1",StoryRights.AUTHORIZED)
        val combined=StoryCloudRules.combine(listOf(seed),listOf(remote))
        assertEquals(1,combined.size)
        assertEquals(seed.id,combined.single().id)
        assertEquals(remote.id,combined.single().remoteId)
        assertEquals(StoryRights.AUTHORIZED,combined.single().provenance.rights)
        assertEquals("douyin:7422916853426621732",StoryProvenance.sourceKey("https://www.douyin.com/video/7422916853426621732?from=test"))
    }
    @Test fun differentSectionsAndDifferentCommentAuthorsAreNotMergedAsTheSameWork() {
        assertNotEquals(StoryProvenance.sourceKey("https://example.org/notes#one"),StoryProvenance.sourceKey("https://example.org/notes#two"))
        val post=entry()
        val comment=entry("story-cloud-comment").copy(authorLabel="评论作者",provenance=post.provenance.copy(sourceType="douyin_comment",commentLocator="另一条评论"))
        assertEquals(2,StoryCloudRules.combine(listOf(post),listOf(comment)).size)
    }
    @Test fun repeatedEditorialRecordsForOneSourceDoNotDuplicateTheCuratedArticle() {
        val seed=entry()
        val first=entry("story-cloud-one",StoryRights.AUTHORIZED)
        val duplicate=entry("story-cloud-two",StoryRights.AUTHORIZED)
        assertEquals(1,StoryCloudRules.combine(listOf(seed),listOf(first,duplicate)).size)
        val submission=duplicate.copy(provenance=duplicate.provenance.copy(sourceType="user_submission"))
        assertEquals(2,StoryCloudRules.combine(listOf(seed),listOf(first,submission)).size)
    }
}
