package cn.campuslife.assistant.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class ReleaseHttpResponse(val code: Int, val bytes: ByteArray = byteArrayOf(), val etag: String? = null, val url: String = "") {
    val body get() = bytes.toString(Charsets.UTF_8)
}

/** Redirects are inspected before opening a connection; signed queries are accepted only on official asset hosts. */
internal class ReleaseHttp(private val source: UpdateSource, private val latest: String,
    private val asset: (String) -> Boolean,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun read(initial: String, etag: String?, limit: Int): ReleaseHttpResponse = request(initial, etag, limit, null)
    fun resolveApk(initial: String, size: Long): String = request(initial, null, 1, size).url

    private fun request(initial: String, etag: String?, limit: Int, apkSize: Long?): ReleaseHttpResponse {
        require(initial == latest || asset(initial)) { "更新地址不属于固定仓库" }
        val validator = etag?.takeIf { it.isNotEmpty() && it.length <= 200 && it.none(Char::isISOControl) }
        val started = System.nanoTime()
        fun checkDeadline() {
            if (Thread.currentThread().isInterrupted || System.nanoTime() - started > 45_000_000_000L) throw IOException("更新读取已停止或超时")
        }
        var current = initial
        repeat(5) { hop ->
            checkDeadline()
            val connection = open(URL(current))
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000; connection.readTimeout = 15000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "CampusToolbox-Android")
                connection.setRequestProperty("Accept", if (source == UpdateSource.GITHUB) "application/vnd.github+json" else "application/json")
                if (current == latest) {
                    if (source == UpdateSource.GITHUB) connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    validator?.let { connection.setRequestProperty("If-None-Match", it) }
                }
                if (apkSize != null) connection.setRequestProperty("Range", "bytes=0-0")
                val status = connection.responseCode
                checkDeadline()
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(initial != latest && hop < 4) { "更新地址发生异常跳转" }
                    val next = URL(URL(current), connection.getHeaderField("Location") ?: throw IOException("更新地址无效")).toString()
                    require(UpdateRules.attachmentRedirect(next, source) || next == initial) { "更新地址发生站外跳转" }
                    current = next
                } else {
                    if (initial == latest && status == 404) return ReleaseHttpResponse(status)
                    if (initial == latest && status == 304) {
                        require(validator != null) { "更新源返回了无效的缓存响应" }
                        return ReleaseHttpResponse(status, etag = validator)
                    }
                    if (status == 403 || status == 429) {
                        val now = System.currentTimeMillis()
                        val seconds = connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(60, 86400) ?: 3600
                        val reset = if (source == UpdateSource.GITHUB) connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                            ?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)?.coerceAtMost(now + UpdateRules.INTERVAL) ?: 0 else 0
                        throw UpdateNetworkException("更新源暂时限流，请稍后再试", maxOf(now + seconds * 1000, reset))
                    }
                    if (apkSize != null) {
                        require(status == 206 && connection.getHeaderField("Content-Range") == "bytes 0-0/$apkSize" &&
                            connection.contentLengthLong == 1L && UpdateRules.attachmentRedirect(current, source)) { "安装包下载地址或大小不符" }
                    } else if (status != 200) throw UpdateNetworkException("暂时无法检查更新，请稍后再试")
                    if (connection.contentLengthLong > limit) throw IOException("更新信息过大")
                    val bytes = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            checkDeadline()
                            val count = input.read(buffer); if (count < 0) break
                            checkDeadline()
                            if (bytes.size() + count > limit) throw IOException("更新信息过大")
                            bytes.write(buffer, 0, count)
                        }
                    }
                    if (apkSize != null) require(bytes.size() == 1) { "下载地址探测失败" }
                    return ReleaseHttpResponse(status, bytes.toByteArray(), connection.getHeaderField("ETag")
                        ?.takeIf { it.length <= 200 && it.none(Char::isISOControl) }, current)
                }
            } finally { connection.disconnect() }
        }
        throw IOException("更新地址跳转过多")
    }
}
