package cn.campuslife.assistant.story

import android.content.Context
import cn.campuslife.assistant.data.AtomicJsonFile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Only bodies actually opened are cached. No replacement of bundled contents or legacy state. */
class StoryCloudCache(context:Context,api:String?=StoryCloudConfig.api,auth:String?=StoryCloudConfig.auth) {
    private val source=StoryCloudRules.sourceKey(api,auth)
    val fileName="stories-cloud-cache-$source-v1.json"
    val withdrawnFileName="stories-cloud-withdrawn-$source-v1.json"
    private val file=AtomicJsonFile(context,fileName)
    private val withdrawnFile=AtomicJsonFile(context,withdrawnFileName)
    private val identity=File(context.applicationContext.filesDir,withdrawnFileName).canonicalPath
    private val lock=locks.getOrPut(identity) {Any()}
    private val blocked=withdrawals.getOrPut(identity) {ConcurrentHashMap.newKeySet<String>()}
    /** Independent durable tombstones plus a same-process deny set if storage cannot write. */
    fun withdrawnIds():Set<String> = synchronized(lock) {
        val json=withdrawnFile.read()
        if(json!=null) {
            require(json.getInt("version")==1)
            val rows=json.getJSONArray("ids")
            val ids=(0 until rows.length()).map {rows.getString(it)}
            require(ids.all(StoryCloudRules::cloudId));blocked.addAll(ids)
        }
        blocked.toSet()
    }
    fun isWithdrawn(id:String)=id in blocked || id in withdrawnIds()
    fun read():List<Story> = synchronized(lock) {
        val withdrawn=withdrawnIds()
        val j=file.read() ?: return@synchronized emptyList();require(j.getInt("version")==1)
        val rows=j.getJSONArray("stories");require(rows.length()<=100)
        (0 until rows.length()).map {i->val r=rows.getJSONObject(i)
            require(StoryCloudRules.cloudId(r.getString("id")))
            val provenance=StoryProvenanceJson.decode(r,true)
            val body=r.getString("body")
            if(provenance.allowsFullText)require(StoryRules.characterCount(body) in 100..20000) else require(body.isEmpty())
            Story(r.getString("id"),r.getString("title"),r.getString("summary"),StoryCategory.valueOf(r.getString("category")),body,r.getString("author"),"审核发布",
                r.optInt("readingMinutes",0).takeIf {it in 1..120},provenance)
        }.also {require(it.map {s->s.id}.distinct().size==it.size)}.filterNot {it.id in withdrawn}
    }
    private fun write(stories:List<Story>) {
        file.write(JSONObject().put("version",1).put("stories",JSONArray(stories.map {original->val s=original.publicCopy()
            StoryProvenanceJson.encode(s.provenance).put("id",s.id).put("title",s.title).put("summary",s.summary)
            .put("category",s.category.name).put("body",s.body).put("author",s.authorLabel)
            .put("readingMinutes",s.estimatedMinutes ?: 0)})))
    }
    fun save(story:Story) = synchronized(lock) {
        require(StoryCloudRules.cloudId(story.id))
        if(isWithdrawn(story.id))throw StoryCloudException("STORY_NOT_FOUND","作品已撤下或暂不可用")
        write((listOf(story.publicCopy())+read().filter {it.id!=story.id}).take(100))
    }
    /** Fresh server rights outrank an old cached full body, even if the next detail request fails. */
    fun applyPublicMetadata(rows:List<Story>) = synchronized(lock) {
        val restricted=rows.filterNot {it.provenance.allowsFullText}.associateBy {it.id}
        if(restricted.isEmpty())return@synchronized
        val old=read()
        val affected=old.filter {it.id in restricted}
        if(affected.isEmpty())return@synchronized
        val safe=old.map {story->restricted[story.id]?.let {meta->
            story.copy(title=meta.title,summary=meta.summary,authorLabel=meta.authorLabel,provenance=meta.provenance).publicCopy()
        } ?: story}
        try {write(safe)}catch(error:Exception) {
            // As with authoritative withdrawal, never reveal stale full text after a failed write.
            affected.forEach {blocked.add(it.id)}
            throw error
        }
    }
    fun remove(id:String) = synchronized(lock) {
        require(StoryCloudRules.cloudId(id))
        blocked.add(id) // Must precede ANY disk access, including a failed tombstone read/write.
        try {
            withdrawnFile.write(JSONObject().put("version",1).put("ids",JSONArray(withdrawnIds().sorted())))
        } catch(error:Exception) {
            throw IOException("作品已禁止读取，但撤下标记保存失败；本进程继续拦截，请检查存储空间。",error)
        }
        // A failed cache rewrite must not undo the separate, already durable tombstone.
        write(read().filter {it.id!=id})
    }
    companion object {
        private val locks=ConcurrentHashMap<String,Any>()
        private val withdrawals=ConcurrentHashMap<String,MutableSet<String>>()
    }
}

/** A durable request ID survives timeouts, recreation and process death. No token in this file. */
class StoryOutbox(context:Context,api:String?=StoryCloudConfig.api,auth:String?=StoryCloudConfig.auth) {
    val fileName="story-outbox-${StoryCloudRules.sourceKey(api,auth)}-v1.json"
    private val file=AtomicJsonFile(context,fileName)
    fun prepare(draft:StoryDraft):String {
        val hash=MessageDigest.getInstance("SHA-256").digest(listOf(draft.title.trim(),draft.category!!.name,draft.body.trim(),draft.nicknameLabel,StoryCloudRules.CONSENT_VERSION).joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
        val old=file.read()
        if(old!=null){require(old.getInt("version")==1);if(old.getString("hash")==hash)return UUID.fromString(old.getString("id")).toString()}
        return UUID.randomUUID().toString().also {id->file.write(JSONObject().put("version",1).put("hash",hash).put("id",id))}
    }
}
