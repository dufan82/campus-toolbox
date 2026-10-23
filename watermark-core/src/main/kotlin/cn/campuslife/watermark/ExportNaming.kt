package cn.campuslife.watermark

/** Serializable field identifiers only; personal values are supplied by the local editor. */
object ExportNaming {
    val defaultFields=listOf(FieldKey.CLASS_NAME,FieldKey.NAME,FieldKey.SEAT_NUMBER)
    private val forbidden=Regex("[<>:\"/\\\\|?*\\x00-\\x1f]")
    private val reserved=Regex("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\.|$)")
    private val imageSuffix=Regex("(?i)\\.(?:jpg|jpeg|png)$")

    fun fields(values: List<FieldKey>)=values.filter { it in defaultOrder }.distinct()
    fun baseName(value: String)=value.replace(imageSuffix,"")
    fun fileName(value: String,format: ExportFormat): String {
        val base=baseName(value)
        require(error(base)==null) { error(base).orEmpty() }
        return "$base.${if(format==ExportFormat.PNG) "png" else "jpg"}"
    }
    fun error(value: String): String? = when {
        value.isBlank() -> "请填写文件名"
        forbidden.containsMatchIn(value) -> "文件名不能包含 / \\ : * ? \" < > | 或控制字符"
        value.lastOrNull()==' ' || value.lastOrNull()=='.' -> "文件名不能以空格或句点结尾"
        reserved.containsMatchIn(value) -> "请换一个文件名"
        value.toByteArray(Charsets.UTF_8).size>180 || value.codePointCount(0,value.length)>100 -> "文件名过长，请适当缩短"
        else -> null
    }
    fun compose(profile: CampusProfile,details: ActivityDetails,selected: List<FieldKey>,timestamp: String): String {
        val parts=fields(selected).mapNotNull { key ->
            val raw=(if(key.personal) profile.values[key] else details.values[key]).orEmpty()
            raw.trim().replace(forbidden,"_").replace(Regex("_+"),"_").trim('_',' ','.').takeIf { it.isNotEmpty() }
        }
        var base=parts.joinToString("_").ifEmpty { "水印照片_$timestamp" }
        if(reserved.containsMatchIn(base)) base="水印照片_$base"
        val result=StringBuilder()
        var bytes=0; var index=0; var count=0
        while(index<base.length && count<100) {
            val cp=base.codePointAt(index)
            val text=String(Character.toChars(cp)); val size=text.toByteArray(Charsets.UTF_8).size
            if(bytes+size>180) break
            result.append(text); bytes+=size; index+=Character.charCount(cp); count++
        }
        return result.toString().trimEnd(' ','.')
    }
}
