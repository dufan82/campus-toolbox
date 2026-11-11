package cn.campuslife.assistant.story

import android.content.Context
import android.content.res.AssetManager
import cn.campuslife.assistant.data.AtomicJsonFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object StoryAsset {
    fun load(assets: AssetManager): List<Story> {
        val json=assets.open("stories.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        return decode(json)
    }
    fun decode(json: JSONObject): List<Story> {
        require(json.get("version") is Int && json.getInt("version")==1)
        val items=json.getJSONArray("stories")
        require(items.length() in 1..100)
        val entries=(0 until items.length()).map { index ->
            val item=items.getJSONObject(index)
            Story(item.getString("id"),item.getString("title"),item.getString("summary"),
                StoryCategory.valueOf(item.getString("category")),item.getString("body"),
                item.getString("authorLabel"),item.getString("source")).also {
                require(StoryRules.validId(it.id) && it.title.isNotBlank() && it.summary.isNotBlank())
                require(StoryRules.characterCount(it.body) in 100..20000)
                require(it.source.isNotBlank() && it.authorLabel.isNotBlank())
            }
        }
        require(entries.map { it.id }.distinct().size==entries.size)
        return entries
    }
}

/** The only file this module owns; no SharedPreferences, poem IDs or legacy store access. */
class StoryStore(context: Context) {
    private val file=AtomicJsonFile(context,FILE_NAME)
    fun read(): StoryLocalState = file.read()?.let {
        try { decode(it) } catch (error: Exception) {
            throw IOException("小故事本机记录无法读取，原文件已保留；暂不保存，请勿清除应用数据。",error)
        }
    } ?: StoryLocalState()
    fun write(state: StoryLocalState) { file.write(encode(state)) }
    companion object {
        val FILE_NAME get()=if(StoryCloudConfig.mock)"stories-mock-local-v1.json" else "stories-local-v1.json"
        private fun JSONObject.string(key: String) = (get(key) as? String) ?: error("Invalid story string")
        private fun JSONObject.bool(key: String) = (get(key) as? Boolean) ?: error("Invalid story boolean")
        fun encode(state: StoryLocalState): JSONObject {
            require(state.favorites.all(StoryRules::validId) && state.progress.keys.all(StoryRules::validId))
            val draft=state.draft
            return JSONObject().put("version",1).put("favorites",JSONArray(state.favorites.toList()))
                .put("progress",JSONObject(state.progress.mapValues { StoryRules.progress(it.value) }))
                .put("fontSp",StoryRules.fontSize(state.fontSp))
                .put("draft",JSONObject().put("title",draft.title).put("category",draft.category?.name ?: JSONObject.NULL)
                    .put("body",draft.body).put("nickname",draft.nickname).put("originalRights",draft.originalRights)
                    .put("displayPermission",draft.displayPermission))
        }
        fun decode(json: JSONObject): StoryLocalState {
            require(json.get("version") is Int && json.getInt("version")==1)
            val favorites=json.getJSONArray("favorites")
            val progress=json.getJSONObject("progress")
            require(favorites.length()<=10000 && progress.length()<=10000)
            val ids=(0 until favorites.length()).map { favorites.get(it) as? String ?: error("Invalid story ID") }.toSet()
            val positions=progress.keys().asSequence().associateWith {
                val number=progress.get(it) as? Number ?: error("Invalid story position")
                require(number.toDouble().isFinite())
                StoryRules.progress(number.toDouble())
            }
            require(ids.all(StoryRules::validId) && positions.keys.all(StoryRules::validId))
            val draft=json.getJSONObject("draft")
            val category=if (draft.isNull("category")) null else StoryCategory.valueOf(draft.string("category"))
            require(json.get("fontSp") is Int)
            return StoryLocalState(ids,positions,StoryRules.fontSize(json.getInt("fontSp")),StoryDraft(
                draft.string("title"),category,draft.string("body"),draft.string("nickname"),
                draft.bool("originalRights"),draft.bool("displayPermission")))
        }
    }
}

/** Module-local serial I/O. No work is scheduled until a story screen is opened. */
class StoryRepository private constructor(context: Context) {
    private val app=context.applicationContext
    private val store=StoryStore(app)
    private var cached: List<Story>? = null
    private val cloud=StoryCloudClient(app)
    private val cloudCache=StoryCloudCache(app)
    private var online=emptyList<Story>()
    private var hasOnline=false
    private var refreshedAt=0L
    private var savedCursor:String?=null
    private val submitting=java.util.concurrent.atomic.AtomicBoolean(false)
    private fun local()=cached ?: StoryAsset.load(app.assets).also {cached=it}
    private fun available()=StoryCloudRules.combine(local(),if(hasOnline)online else emptyList(),
        runCatching {cloudCache.read()}.getOrDefault(emptyList()),cloudCache.withdrawnIds())
    fun load(callback: (Result<Pair<List<Story>,StoryLocalState>>)->Unit) = executor.execute {
        callback(runCatching { available() to store.read() })
    }
    fun loadState(callback: (Result<StoryLocalState>)->Unit) = executor.execute { callback(runCatching { store.read() }) }
    fun change(transform: (StoryLocalState)->StoryLocalState, callback: (Result<StoryLocalState>)->Unit = {}) = executor.execute {
        callback(runCatching { transform(store.read()).also(store::write) })
    }
    fun refresh(cursor:String?,force:Boolean=false,complete:(Result<Pair<List<Story>,String?>>)->Unit)=executor.execute {
        complete(runCatching {
            if(cursor==null && !force && hasOnline && System.currentTimeMillis()-refreshedAt<5*60*1000) return@runCatching available() to savedCursor
            val page=cloud.list(cursor)
            val rows=page.items.map {Story(it.id,it.title,it.summary,it.category,"",it.authorName,"同学投稿 · 审核发布",it.readingMinutes)}
            online=if(cursor==null)rows else (online+rows).distinctBy {it.id}
            online=online.take(500);hasOnline=true;savedCursor=if(online.size>=500)null else page.nextCursor
            if(cursor==null)refreshedAt=System.currentTimeMillis()
            available() to savedCursor
        })
    }
    fun detail(id:String,complete:(Result<Story>)->Unit)=executor.execute {
        complete(runCatching {
            local().firstOrNull {it.id==id} ?: run {
                require(StoryCloudRules.cloudId(id))
                try {cloudCache.resolveDetail(id,if(StoryCloudConfig.configured)({cloud.detail(id)})else null)}
                catch(error:Exception) {
                    if(StoryCloudRules.authoritativeMissing(error))online=online.filter {it.id!=id}
                    throw error
                }
            }
        })
    }
    fun submit(draft:StoryDraft,complete:(Result<StoryReceipt>)->Unit) {
        if(!submitting.compareAndSet(false,true)){complete(Result.failure(StoryCloudException("BUSY","正在提交，请稍候")));return}
        executor.execute {
            val result=runCatching {
                require(StoryRules.validate(draft).isEmpty()) {"请检查投稿内容和授权"}
                // Save before network; this queue also serializes every automatic draft save.
                store.write(store.read().copy(draft=draft))
                val id=StoryOutbox(app).prepare(draft)
                val receipt=cloud.submit(id,draft)
                val latest=store.read();if(latest.draft==draft)store.write(latest.copy(draft=StoryDraft()))
                receipt
            }
            submitting.set(false);complete(result)
        }
    }
    companion object {
        private val executor=Executors.newSingleThreadExecutor { Thread(it,"story-io") }
        private val instances=ConcurrentHashMap<String,StoryRepository>()
        fun get(context: Context): StoryRepository = instances.getOrPut(context.applicationContext.filesDir.absolutePath+":"+
            StoryCloudRules.sourceKey(StoryCloudConfig.api,StoryCloudConfig.auth)) { StoryRepository(context) }
    }
}

/** Shared detail policy; the nullable fetch also permits local-only use without a network call. */
internal fun StoryCloudCache.resolveDetail(id:String,fetch:(()->Story)?):Story {
    require(StoryCloudRules.cloudId(id))
    if(isWithdrawn(id))throw StoryCloudException("STORY_NOT_FOUND","作品已撤下或暂不可用")
    if(fetch==null)return read().firstOrNull {it.id==id} ?: throw StoryCloudException("NOT_CACHED","这篇故事尚未缓存")
    val story=try {fetch()} catch(error:Exception) {
        if(StoryCloudRules.authoritativeMissing(error)) {
            val failure=runCatching {remove(id)}.exceptionOrNull()
            throw StoryCloudException("STORY_NOT_FOUND",if(failure==null)"作品已撤下或暂不可用" else "作品已撤下，缓存清理未完成，已禁止读取。",404)
                .also {if(failure!=null)it.initCause(failure)}
        }
        return read().firstOrNull {it.id==id} ?: throw error
    }
    // A fresh online body can survive a best-effort cache save, but never a known withdrawal.
    require(story.id==id)
    runCatching {save(story)}
    if(isWithdrawn(id))throw StoryCloudException("STORY_NOT_FOUND","作品已撤下或暂不可用")
    return story
}
