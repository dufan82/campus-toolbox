package cn.campuslife.assistant.story

import java.net.URI

enum class StoryRights(val wire:String,val fullText:Boolean) {
    UNVERIFIED("unverified",false), EXCERPT_ONLY("excerpt_only",false),
    AUTHORIZED("authorized",true), PUBLIC_DOMAIN("public_domain",true),
    USER_AUTHORIZED("user_authorized",true), REVOKED("revoked",false);
    companion object { fun fromWire(value:String)=entries.firstOrNull {it.wire==value} ?: UNVERIFIED }
}

/** Public provenance only. Evidence/rights notes and identities remain on the admin server. */
data class StoryProvenance(
    val sourceType:String="unknown", val sourceUrl:String="", val publisherName:String="",
    val sourceWorkTitle:String="", val commentLocator:String="",
    val rights:StoryRights=StoryRights.UNVERIFIED, val excerpt:String="",
    val editorialRank:Int=10000, val hidden:Boolean=false, val archived:Boolean=false,
    val sourceVerified:Boolean=true, val sourceAliases:List<String> = emptyList(),
    val licenseName:String="", val licenseUrl:String="", val editionNote:String=""
) {
    val allowsFullText get()=rights.fullText && !hidden
    val link get()=publicSourceUrl(sourceUrl)
    companion object {
        val sourceTypes=setOf("douyin_post","douyin_image","douyin_comment","other_platform","user_submission","legacy_original")
        const val COPYRIGHT="作品版权归原作者所有，内容仅在获得相应授权后收录展示。如有版权或内容问题，请通过意见反馈联系。"
        fun publicSourceUrl(raw:String):String?=runCatching {
            require(raw.length in 1..2048 && raw==raw.trim() && raw.none {it.isWhitespace() || it.isISOControl()})
            val uri=URI(raw);val host=uri.host?.lowercase() ?: error("Missing host")
            require(uri.scheme=="https" && uri.userInfo==null && uri.port in setOf(-1,443))
            require(host.contains('.') && !host.endsWith(".localhost") && !host.endsWith(".local") && !host.matches(Regex("[0-9.]+")))
            uri.toASCIIString()
        }.getOrNull()
        fun sourceKey(raw:String):String?=publicSourceUrl(raw)?.let {
            val uri=URI(it);val path=uri.path.trimEnd('/')
            val douyin=if(uri.host.lowercase() in setOf("www.douyin.com","douyin.com"))
                Regex("/(?:note|video)/(\\d+)").matchEntire(path)?.groupValues?.get(1) else null
            if(douyin!=null) "douyin:$douyin" else "${uri.host.lowercase()}$path"+
                (uri.rawQuery?.let {query->"?$query"} ?: "")+(uri.rawFragment?.let {fragment->"#$fragment"} ?: "")
        }
    }
}
