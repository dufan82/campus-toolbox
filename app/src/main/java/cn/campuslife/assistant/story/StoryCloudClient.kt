package cn.campuslife.assistant.story

import android.content.Context
import cn.campuslife.assistant.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.UUID

object StoryCloudConfig {
    val mock=BuildConfig.DEBUG && BuildConfig.STORY_MOCK
    val api=StoryCloudRules.endpoint(BuildConfig.STORY_API_URL,mock)
    val auth=StoryCloudRules.endpoint(BuildConfig.STORY_AUTH_URL,mock,auth=true)
    val configured get()=api!=null && auth!=null
    val identityPrefs get()="story-anonymous-${StoryCloudRules.sourceKey(api,auth)}"
}

/** Module-local HTTPS only. No school cookies, user profile, hardware ID or SDK admin credentials. */
class StoryCloudClient(context:Context,private val requestDeadlineMs:Long=40_000L) {
    init {require(requestDeadlineMs in 100..40_000)}
    private val app=context.applicationContext
    private var accessToken:String?=null
    private var expiresAt=0L
    private fun request(url:String,body:JSONObject,token:String?=null,device:String?=null):JSONObject {
        val started=System.nanoTime()
        val expired=java.util.concurrent.atomic.AtomicBoolean(false)
        val connection=URL(url).openConnection() as HttpURLConnection
        // Unlike readTimeout, this also ends slowly trickled headers, connect and writes.
        val cancellation=deadlines.schedule({expired.set(true);connection.disconnect()},requestDeadlineMs,java.util.concurrent.TimeUnit.MILLISECONDS)
        fun deadline(){if(expired.get() || Thread.currentThread().isInterrupted || System.nanoTime()-started>requestDeadlineMs*1_000_000L)throw StoryCloudException("TIMEOUT","请求超时，请稍后重试")}
        try {
            connection.instanceFollowRedirects=false;connection.connectTimeout=10000;connection.readTimeout=15000
            connection.requestMethod="POST";connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
            connection.setRequestProperty("Accept","application/json")
            token?.let {connection.setRequestProperty("Authorization","Bearer $it")}
            device?.let {connection.setRequestProperty("x-device-id",it)}
            val bytes=body.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=40*1024)
            deadline();connection.setFixedLengthStreamingMode(bytes.size);connection.outputStream.use {deadline();it.write(bytes);deadline()}
            deadline();val status=connection.responseCode;deadline()
            if(status in 300..399)throw StoryCloudException("REDIRECT_REJECTED","服务地址发生跳转，请联系维护者")
            if(connection.contentLengthLong>256*1024)throw StoryCloudException("INVALID_RESPONSE","服务响应过大")
            val input=if(status in 200..299)connection.inputStream else connection.errorStream
            val out=ByteArrayOutputStream()
            input?.use {stream->val buffer=ByteArray(4096);while(true){deadline();val n=stream.read(buffer);deadline();if(n<0)break;if(out.size()+n>256*1024)throw StoryCloudException("INVALID_RESPONSE","服务响应过大");out.write(buffer,0,n)}}
            val json=runCatching {JSONObject(out.toString("UTF-8"))}.getOrElse {throw StoryCloudException("INVALID_RESPONSE","服务响应不完整，请稍后重试")}
            if(status !in 200..299 || (json.has("ok") && !json.optBoolean("ok"))) {
                val error=json.optJSONObject("error")
                throw StoryCloudException(error?.optString("code")?.ifBlank {null} ?: if(status==401)"UNAUTHORIZED" else "HTTP_$status",
                    error?.optString("message")?.take(300)?.ifBlank {null} ?: "服务暂不可用，请稍后重试",status)
            }
            return json
        } catch(error:Exception) {
            if(expired.get())throw StoryCloudException("TIMEOUT","请求超时，请稍后重试")
            throw error
        } finally {cancellation.cancel(false);connection.disconnect()}
    }
    private fun token():String {
        if(accessToken!=null && System.currentTimeMillis()<expiresAt)return accessToken!!
        val prefs=app.getSharedPreferences(StoryCloudConfig.identityPrefs,Context.MODE_PRIVATE)
        val installation=prefs.getString("installation",null) ?: UUID.randomUUID().toString().also {check(prefs.edit().putString("installation",it).commit())}
        val auth=StoryCloudConfig.auth ?: throw StoryCloudException("NOT_CONFIGURED","投稿暂未开放")
        val response=request("$auth/auth/v1/signin/anonymously",JSONObject(),device=installation)
        val value=response.getString("access_token");require(value.length in 1..4096 && value.none {it.isISOControl()})
        expiresAt=System.currentTimeMillis()+(response.optLong("expires_in",0).coerceIn(0,7200)-30).coerceAtLeast(0)*1000
        accessToken=value;return value
    }
    private fun call(action:String,payload:JSONObject,authenticated:Boolean=false,retry:Boolean=true):JSONObject {
        val endpoint=StoryCloudConfig.api ?: throw StoryCloudException("NOT_CONFIGURED","投稿暂未开放")
        val response=try {request(endpoint,JSONObject().put("action",action).put("payload",payload),if(authenticated)token() else null)}
        catch(error:StoryCloudException) {
            if(authenticated && retry && (error.httpStatus==401 || error.code in setOf("UNAUTHORIZED","AUTH_REQUIRED","INVALID_TOKEN","HTTP_401"))) {
                accessToken=null;return call(action,payload,true,false)
            };throw error
        }
        if(!response.optBoolean("ok"))throw StoryCloudException("INVALID_RESPONSE","服务未确认成功")
        return response.optJSONObject("data") ?: throw StoryCloudException("INVALID_RESPONSE","服务未返回完整结果")
    }
    fun submit(id:String,draft:StoryDraft):StoryReceipt {
        val json=call("submitStory",JSONObject().put("submissionId",id).put("title",draft.title.trim()).put("category",draft.category!!.name)
            .put("content",draft.body.trim()).put("authorName",draft.nicknameLabel).put("consent",draft.originalRights && draft.displayPermission)
            .put("consentVersion",StoryCloudRules.CONSENT_VERSION),true)
        require(json.getString("submissionId")==id && json.getString("status") in setOf("pending","approved","rejected"))
        return StoryReceipt(id,json.getString("id"),json.getString("status"),json.optBoolean("duplicate"))
    }
    fun list(cursor:String?=null):StoryCloudPage {
        val p=JSONObject().put("limit",20);cursor?.let {p.put("cursor",it)}
        val json=call("getPublishedStories",p);val rows=json.getJSONArray("items");require(rows.length()<=20)
        val stories=(0 until rows.length()).map {i->val j=rows.getJSONObject(i);summary(j)}
        require(stories.map {it.id}.distinct().size==stories.size)
        return StoryCloudPage(stories,json.optString("nextCursor").takeIf {it.isNotBlank() && it!="null"}.also {require(it==null || it.length<=1024)})
    }
    private fun summary(j:JSONObject):StoryCloudSummary {
        val id=j.getString("id");require(StoryCloudRules.cloudId(id))
        val title=j.getString("title");val author=j.getString("authorName");val short=j.getString("summary")
        require(StoryRules.characterCount(title) in 2..30 && StoryRules.characterCount(author) in 1..30 && short.length<=250)
        return StoryCloudSummary(id,title,StoryCategory.valueOf(j.getString("category")),short,author,j.getString("publishedAt"),j.optInt("readingMinutes",1).coerceIn(1,30))
    }
    fun detail(id:String):Story {
        require(StoryCloudRules.cloudId(id))
        val j=call("getStoryDetail",JSONObject().put("id",id));require(j.getString("id")==id)
        val text=j.getString("content");require(StoryRules.characterCount(text) in 100..5000)
        val title=j.getString("title");val author=j.getString("authorName")
        require(StoryRules.characterCount(title) in 2..30 && StoryRules.characterCount(author) in 1..30)
        return Story(id,title,text.take(65),StoryCategory.valueOf(j.getString("category")),text,author,"同学投稿 · 审核发布")
    }
    private companion object {
        val deadlines=java.util.concurrent.ScheduledThreadPoolExecutor(1) {task->Thread(task,"story-request-deadline").apply {isDaemon=true}}.apply {removeOnCancelPolicy=true}
    }
}
