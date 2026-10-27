package cn.campuslife.assistant.update

import cn.campuslife.assistant.BuildConfig
import java.io.IOException

fun interface ReleaseClient { fun fetch(etag: String?): ReleaseResponse }
internal data class ReleaseCache(val update: UpdatePackage? = null, val missing: Boolean = false, val etag: String? = null, val retryAt: Long = 0)
internal data class SourcedRelease(val source: UpdateSource, val response: ReleaseResponse)

/** Each validator belongs to one source. A failure or absent CNB release falls back to GitHub. */
internal class PreferredReleaseClient(
    private val github: ReleaseClient = GitHubReleaseClient(),
    private val cnb: ReleaseClient? = BuildConfig.CNB_REPOSITORY.takeIf(UpdateRules::cnbRepository)?.let { CnbReleaseClient(it) }
) {
    fun fetch(cache: (UpdateSource) -> ReleaseCache, now: Long,
        failed: (UpdateSource, Exception) -> Unit = { _, _ -> }): SourcedRelease {
        fun request(source: UpdateSource, client: ReleaseClient): SourcedRelease {
            val previous = cache(source)
            if (previous.retryAt > now && previous.retryAt - now <= 7 * UpdateRules.INTERVAL)
                throw UpdateNetworkException("更新源暂时限流，请稍后再试", previous.retryAt)
            var response = client.fetch(previous.etag)
            if (response.notModified && previous.update == null && !previous.missing) response = client.fetch(null)
            if (response.notModified) {
                require(previous.update != null || previous.missing) { "更新缓存已失效" }
                response = response.copy(update = previous.update, missing = previous.missing)
            }
            require(response.update == null || response.update.source == source) { "更新来源不一致" }
            return SourcedRelease(source, response)
        }
        if (cnb != null) {
            try {
                val result = request(UpdateSource.CNB, cnb)
                if (!result.response.missing) return result
            } catch (error: Exception) {
                if (Thread.currentThread().isInterrupted) throw error
                failed(UpdateSource.CNB, error)
            }
        }
        return try { request(UpdateSource.GITHUB, github) }
        catch (error: Exception) { failed(UpdateSource.GITHUB, error); throw error }
    }
}
