package cn.campuslife.assistant.update

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PreferredReleaseClientTest {
    private val github = UpdatePackage(19, "1.7.2", "v1.7.2", 26, "campus-toolbox-1.7.2.apk", 3, "a".repeat(64), "fixture", 10)
    private val cnb = github.copy(source = UpdateSource.CNB, repository = "fixture/project", releaseId = 20)

    @Test fun cnbStableReleaseWinsWithoutContactingGithub() {
        val client = PreferredReleaseClient(ReleaseClient { error("GitHub must not be requested") }, ReleaseClient { ReleaseResponse(cnb, "cnb-new") })
        val result = client.fetch({ ReleaseCache() }, 100)
        assertEquals(UpdateSource.CNB, result.source)
        assertEquals(cnb, result.response.update)
    }

    @Test fun absentCnbReleaseFallsBackToGithub() {
        val calls = mutableListOf<String>()
        val client = PreferredReleaseClient(ReleaseClient { calls += "github"; ReleaseResponse(github) },
            ReleaseClient { calls += "cnb"; ReleaseResponse(missing = true) })
        assertEquals(github, client.fetch({ ReleaseCache() }, 100).response.update)
        assertEquals(listOf("cnb", "github"), calls)
    }

    @Test fun invalidOrUnavailableCnbDoesNotBlockGithub() {
        for (failure in listOf(IOException("offline"), IllegalArgumentException("invalid metadata"), UpdateNetworkException("limited", 500))) {
            val failedSources = mutableListOf<UpdateSource>()
            val client = PreferredReleaseClient(ReleaseClient { ReleaseResponse(github) }, ReleaseClient { throw failure })
            assertEquals(github, client.fetch({ ReleaseCache() }, 100) { source, _ -> failedSources += source }.response.update)
            assertEquals(listOf(UpdateSource.CNB), failedSources)
        }
    }

    @Test fun etagsAndNotModifiedBodiesStayWithTheirOwnSource() {
        val requests = mutableListOf<String>()
        val client = PreferredReleaseClient(ReleaseClient { etag -> requests += "github:$etag"; ReleaseResponse(notModified = true, etag = etag) },
            ReleaseClient { etag -> requests += "cnb:$etag"; throw IOException("cnb offline") })
        val result = client.fetch({ source -> if (source == UpdateSource.CNB) ReleaseCache(cnb, etag = "cnb-20") else ReleaseCache(github, etag = "gh-10") }, 100)
        assertEquals(github, result.response.update)
        assertEquals(UpdateSource.GITHUB, result.source)
        assertEquals(listOf("cnb:cnb-20", "github:gh-10"), requests)
    }

    @Test fun orphanedValidatorRetriesWithoutConditionInsteadOfAcceptingEmpty304() {
        val validators = mutableListOf<String?>()
        val client = PreferredReleaseClient(ReleaseClient { error("must not fall back") }, ReleaseClient { etag ->
            validators += etag
            if (etag != null) ReleaseResponse(notModified = true) else ReleaseResponse(cnb)
        })
        assertEquals(cnb, client.fetch({ ReleaseCache(etag = "orphan") }, 100).response.update)
        assertEquals(listOf("orphan", null), validators)
    }

    @Test fun missingCnbCacheOn304StillFallsBackToGithub() {
        val client = PreferredReleaseClient(ReleaseClient { ReleaseResponse(github) }, ReleaseClient { ReleaseResponse(notModified = true) })
        assertEquals(github, client.fetch({ source -> if (source == UpdateSource.CNB) ReleaseCache(missing = true, etag = "missing") else ReleaseCache() }, 100).response.update)
    }

    @Test fun oneSourceRateLimitDoesNotThrottleTheOther() {
        val client = PreferredReleaseClient(ReleaseClient { ReleaseResponse(github) }, ReleaseClient { error("CNB backoff must be honored") })
        assertEquals(github, client.fetch({ source -> ReleaseCache(retryAt = if (source == UpdateSource.CNB) 500 else 0) }, 100).response.update)
        val preferred = PreferredReleaseClient(ReleaseClient { error("Github backoff must not affect CNB") }, ReleaseClient { ReleaseResponse(cnb) })
        assertEquals(cnb, preferred.fetch({ source -> ReleaseCache(retryAt = if (source == UpdateSource.GITHUB) 500 else 0) }, 100).response.update)
    }

    @Test fun disabledCnbPreservesGithubOnlyBehaviorAndFailure() {
        val client = PreferredReleaseClient(ReleaseClient { ReleaseResponse(missing = true) }, null)
        assertTrue(client.fetch({ ReleaseCache() }, 100).response.missing)
        assertThrows(IOException::class.java) { PreferredReleaseClient(ReleaseClient { throw IOException("offline") }, null).fetch({ ReleaseCache() }, 100) }
    }

    @Test fun responseCannotMasqueradeAsTheOtherSource() {
        val client = PreferredReleaseClient(ReleaseClient { ReleaseResponse(github) }, ReleaseClient { ReleaseResponse(github) })
        assertEquals(UpdateSource.GITHUB, client.fetch({ ReleaseCache() }, 100).source)
    }

    @Test fun cancellationDoesNotStartAFallbackRequest() {
        val client = PreferredReleaseClient(ReleaseClient { error("must remain cancelled") }, ReleaseClient {
            Thread.currentThread().interrupt(); throw IOException("cancelled")
        })
        try { assertThrows(IOException::class.java) { client.fetch({ ReleaseCache() }, 100) } }
        finally { Thread.interrupted() }
    }
}
