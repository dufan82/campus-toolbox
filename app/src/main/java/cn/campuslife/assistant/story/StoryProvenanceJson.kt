package cn.campuslife.assistant.story

import org.json.JSONArray
import org.json.JSONObject

object StoryProvenanceJson {
    fun decode(item:JSONObject,legacySubmission:Boolean=false):StoryProvenance {
        val source=item.optString("sourceType",if(legacySubmission)"user_submission" else "unknown")
            .let {if(it=="submission")"user_submission" else it}
        val rights=if(source !in StoryProvenance.sourceTypes)StoryRights.UNVERIFIED
            else if(!item.has("rightsStatus") && legacySubmission && source=="user_submission")StoryRights.USER_AUTHORIZED
            else StoryRights.fromWire(item.optString("rightsStatus"))
        val aliases=item.optJSONArray("sourceAliases") ?: JSONArray()
        require(aliases.length()<=5)
        return StoryProvenance(source,item.optString("sourceUrl"),item.optString("publisherName"),
            item.optString("sourceWorkTitle"),item.optString("commentLocator"),rights,item.optString("excerpt"),
            item.optInt("editorialRank",10000).coerceIn(-1000000000,1000000000),item.optBoolean("hidden"),item.optBoolean("archived"),
            item.optBoolean("sourceVerified",true),(0 until aliases.length()).map {aliases.getString(it)}).also {
            require(it.sourceUrl.isEmpty() || it.link!=null)
            require(it.sourceAliases.all {url->StoryProvenance.publicSourceUrl(url)!=null})
            require(StoryRules.characterCount(it.excerpt)<=80)
            require(it.publisherName.length<=120 && it.commentLocator.length<=500 && it.sourceWorkTitle.length<=200)
        }
    }
    fun encode(p:StoryProvenance)=JSONObject().put("sourceType",p.sourceType).put("sourceUrl",p.sourceUrl)
        .put("publisherName",p.publisherName).put("sourceWorkTitle",p.sourceWorkTitle).put("commentLocator",p.commentLocator)
        .put("rightsStatus",p.rights.wire).put("excerpt",p.excerpt).put("editorialRank",p.editorialRank)
        .put("hidden",p.hidden).put("archived",p.archived).put("sourceVerified",p.sourceVerified)
        .put("sourceAliases",JSONArray(p.sourceAliases))
}
