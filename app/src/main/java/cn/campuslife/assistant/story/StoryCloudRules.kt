package cn.campuslife.assistant.story

import java.net.URI
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

data class StoryReceipt(val submissionId:String,val id:String,val status:String,val duplicate:Boolean)
data class StoryCloudSummary(val id:String,val title:String,val category:StoryCategory,val summary:String,val authorName:String,val publishedAt:String,val readingMinutes:Int=1,
    val provenance:StoryProvenance=StoryProvenance(sourceType="user_submission",rights=StoryRights.USER_AUTHORIZED))
data class StoryCloudPage(val items:List<StoryCloudSummary>,val nextCursor:String?)
class StoryCloudException(val code:String,message:String,val httpStatus:Int=0):IOException(message)

object StoryCloudRules {
    const val CONSENT_VERSION="2026-11-10-v1"
    const val CACHED_SOURCE="已缓存 · 非当前在线分页结果"
    /** Public shared identity for cache, outbox and anonymous prefs; no credentials in filenames. */
    fun sourceKey(api:String?,auth:String?):String {
        fun normalized(raw:String?):String {
            val value=raw?.trim().orEmpty();if(value.isEmpty())return ""
            return runCatching {
                val uri=URI(value).normalize();val scheme=uri.scheme.lowercase(Locale.ROOT)
                require(scheme in setOf("http","https") && !uri.host.isNullOrBlank())
                require(uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null)
                val host=uri.host.lowercase(Locale.ROOT)
                val port=if(uri.port==-1 || (scheme=="https" && uri.port==443) || (scheme=="http" && uri.port==80)) "" else ":${uri.port}"
                // Keep raw escaped path: %2F must not collapse into a path separator.
                "$scheme://$host$port${uri.rawPath.orEmpty().trimEnd('/')}"
            }.getOrElse {"invalid:$value"}
        }
        val a=normalized(api);val b=normalized(auth)
        val identity=if(a.isEmpty() && b.isEmpty())"story-cloud-source-v1:unconfigured" else "story-cloud-source-v1:${a.length}:$a${b.length}:$b"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") {"%02x".format(it.toInt() and 255)}.take(24)
    }
    fun endpoint(raw:String,mock:Boolean=false,auth:Boolean=false):String? = runCatching {
        require(raw.isNotEmpty() && raw==raw.trim() && raw.none {it.isWhitespace() || it.isISOControl()})
        val uri=URI(raw);require(uri.userInfo==null && uri.fragment==null && uri.query==null && !uri.host.isNullOrBlank())
        if(mock)require(uri.scheme=="http" && uri.host in setOf("10.0.2.2","127.0.0.1","localhost") && uri.port==8787)
        else require(uri.scheme=="https" && uri.port in setOf(-1,443))
        if(auth) {require(uri.path.isNullOrEmpty() || uri.path=="/");if(!mock)require(uri.host.endsWith(".api.tcloudbasegateway.com"))}
        uri.toASCIIString().trimEnd('/')
    }.getOrNull()
    fun cloudId(id:String)=id.startsWith("story-cloud-") && StoryRules.validId(id)
    fun authoritativeMissing(error:Throwable)=error is StoryCloudException &&
        (error.httpStatus==404 || error.code in setOf("STORY_NOT_FOUND","NOT_FOUND","HTTP_404"))
    fun combine(local:List<Story>,cloud:List<Story>,cached:List<Story> = emptyList(),withdrawn:Set<String> = emptySet()):List<Story> {
        val remote=(cloud.filter {cloudId(it.id)}+cached.filter {cloudId(it.id)}.map {it.copy(source=CACHED_SOURCE)})
            .filterNot {it.id in withdrawn}.distinctBy {it.id}.distinctBy {story->
                val p=story.provenance
                val key=StoryProvenance.sourceKey(p.sourceUrl)
                when {
                    p.sourceType=="user_submission" || key==null -> "id:${story.id}"
                    p.sourceType=="douyin_comment" && p.commentLocator.isBlank() -> "id:${story.id}"
                    p.sourceType=="douyin_comment" -> listOf("comment",key,story.authorLabel,p.commentLocator)
                    else -> listOf("work",key)
                }
            }
        val used=mutableSetOf<String>()
        val merged=local.map {seed->
            val keys=(listOf(seed.provenance.sourceUrl)+seed.provenance.sourceAliases).mapNotNull(StoryProvenance::sourceKey).toSet()
            val replacement=if(seed.provenance.archived || keys.isEmpty())null else remote.firstOrNull {
                it.provenance.sourceType!="user_submission" && StoryProvenance.sourceKey(it.provenance.sourceUrl) in keys &&
                    (if(seed.provenance.sourceType=="douyin_comment" || it.provenance.sourceType=="douyin_comment")
                        seed.provenance.sourceType==it.provenance.sourceType && seed.authorLabel==it.authorLabel &&
                            seed.provenance.commentLocator.isNotBlank() && seed.provenance.commentLocator==it.provenance.commentLocator
                    else true)
            }
            if(replacement==null)seed else {
                used.add(replacement.id)
                replacement.copy(id=seed.id,remoteId=replacement.id,provenance=replacement.provenance.copy(editorialRank=seed.provenance.editorialRank))
            }
        }
        return StoryRules.ordered((merged+remote.filterNot {it.id in used}).filterNot {it.provenance.hidden}.distinctBy {it.id})
    }
}
