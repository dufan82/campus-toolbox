package cn.campuslife.assistant.story

import java.security.MessageDigest

/** Build-time source checks are recorded with the text; truncation must not silently pass. */
object StoryTextIntegrity {
    fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") {"%02x".format(it.toInt() and 255)}
    fun paragraphCount(text:String)=text.split(Regex("\n\\s*\n")).count {it.isNotBlank()}
    fun matches(text:String,sha256:String,characters:Int,paragraphs:Int)=
        text.isNotBlank() && characters>0 && paragraphs>0 &&
            StoryRules.characterCount(text)==characters && paragraphCount(text)==paragraphs &&
            sha256.matches(Regex("[a-fA-F0-9]{64}")) && digest(text).equals(sha256,true)
}
