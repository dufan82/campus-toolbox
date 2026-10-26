package cn.campuslife.assistant.update

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

data class UpdatePackage(val versionCode: Long, val versionName: String, val tag: String, val minSdk: Int,
    val assetName: String, val size: Long, val sha256: String, val notes: String, val releaseId: Long) {
    val url get() = "https://github.com/dufan82/campus-toolbox/releases/download/$tag/$assetName"
}
data class ApkIdentity(val packageName: String, val versionCode: Long, val minSdk: Int, val signers: Set<String>, val debuggable: Boolean)

object UpdateRules {
    const val PACKAGE = "cn.campuslife.assistant"
    const val INTERVAL = 24 * 60 * 60 * 1000L
    fun valid(info: UpdatePackage): Boolean = info.versionCode in 1..2_100_000_000L &&
        info.versionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")) &&
        info.tag == "v${info.versionName}" && info.minSdk in 1..1000 &&
        info.assetName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,100}\\.apk")) &&
        info.size in 1..(512L * 1024 * 1024) && info.sha256.matches(Regex("[a-f0-9]{64}")) &&
        info.notes.length <= 6000 && info.releaseId > 0

    fun available(info: UpdatePackage, installed: Long, sdk: Int) = valid(info) && info.versionCode > installed && sdk >= info.minSdk
    fun assetUrl(url: String, tag: String, name: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.rawAuthority == "github.com" && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath == "/dufan82/campus-toolbox/releases/download/$tag/$name" &&
            tag.matches(Regex("v[A-Za-z0-9][A-Za-z0-9._-]{0,39}")) && name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,104}"))
    }.getOrDefault(false)

    fun shouldCheck(now: Long, attempted: Long, retryAt: Long, manual: Boolean): Boolean {
        if (retryAt > now && retryAt - now <= 7 * INTERVAL) return false
        if (attempted <= 0 || attempted > now) return true
        return now - attempted >= if (manual) 60_000 else INTERVAL
    }

    // The installed certificate is the trust anchor, never a signer supplied by update.json.
    fun compatible(info: UpdatePackage, apk: ApkIdentity, installed: Long, sdk: Int, signers: Set<String>) =
        available(info, installed, sdk) && apk.packageName == PACKAGE && apk.versionCode == info.versionCode &&
            apk.minSdk == info.minSdk && apk.minSdk <= sdk && !apk.debuggable && signers.isNotEmpty() && apk.signers == signers

    fun digest(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun copyVerified(input: InputStream, output: OutputStream, info: UpdatePackage) {
        require(valid(info)) { "更新信息无效" }
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw IOException("更新校验已取消")
            val count = input.read(buffer); if (count < 0) break
            total += count
            if (total > info.size) throw IOException("安装包大小不符，请重新下载")
            sha.update(buffer, 0, count); output.write(buffer, 0, count)
        }
        if (total != info.size || digest(sha.digest()) != info.sha256) throw IOException("安装包校验失败，请重新下载")
    }
}
