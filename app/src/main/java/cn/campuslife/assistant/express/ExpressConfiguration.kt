package cn.campuslife.assistant.express

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

interface ExpressSettings {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

object LinkPolicy {
    fun error(link: String, schemes: Set<String>): String? {
        if (link.isBlank()) return "请输入链接，或使用恢复默认。"
        if (link.length > 4096) return "链接过长，请使用 4096 字符以内的链接。"
        if (link.any { it.isISOControl() } || Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE).containsMatchIn(link)) {
            return "链接不能包含控制字符。"
        }
        val normalized = link.trim()
        val uri = try { URI(normalized) } catch (_: URISyntaxException) { return "链接格式不正确。" }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return "链接必须包含协议。"
        if (scheme !in schemes && scheme !in setOf("http", "https")) {
            return "只支持 http、https 和当前平台的协议：${schemes.joinToString("、")}。"
        }
        if (uri.isOpaque || uri.rawAuthority.isNullOrBlank() || uri.rawUserInfo != null || normalized.contains('\\')) {
            return "请输入包含有效地址、且不带账号密码的完整链接。"
        }
        if (scheme in setOf("http", "https") && uri.host.isNullOrBlank()) return "网页链接需要有效域名。"
        return null
    }
}

/** Single source of truth for settings, diagnostics, and launch candidate order. No Android dependency. */
class ExpressConfiguration(private val settings: ExpressSettings) {
    fun effective(entryId: String): EffectiveExpressConfig {
        val entry = ExpressCatalog.entry(entryId)
        val targets = orderedTargets(entry)
        val storedLink = settings.read("link.$entryId")
        val warning = storedLink?.let { LinkPolicy.error(it, targets.first().schemes) }
        val customLink = storedLink?.takeIf { warning == null }?.trim()
        val candidates = buildList {
            if (customLink != null) add(RouteCandidate(targets.first().packageName, RouteKind.LINK, customLink))
            targets.forEach { target -> target.links.forEach { add(RouteCandidate(target.packageName, RouteKind.LINK, it)) } }
            targets.forEach { add(RouteCandidate(it.packageName, RouteKind.APP_HOME)) }
        }.distinct()
        return EffectiveExpressConfig(entry, if (customLink == null) ConfigSource.DEFAULT else ConfigSource.CUSTOM,
            candidates, customLink, warning?.let { "已忽略不适用于当前平台或版本的自定义链接，正在使用默认配置。$it" })
    }

    fun saveLink(entryId: String, link: String): String? {
        val target = orderedTargets(ExpressCatalog.entry(entryId)).first()
        if (link.isBlank()) {
            settings.write("link.$entryId", null)
            return null
        }
        LinkPolicy.error(link, target.schemes)?.let { return it }
        settings.write("link.$entryId", link.trim())
        return null
    }

    fun restore(entryId: String) {
        ExpressCatalog.entry(entryId)
        settings.write("link.$entryId", null)
        if (entryId == "douyin_orders") settings.write("douyin.version", null)
    }

    fun setDouyinVersion(version: DouyinVersion) { settings.write("douyin.version", version.name) }

    fun douyinVersion(): DouyinVersion = if (settings.read("douyin.version") == "LITE") DouyinVersion.LITE else DouyinVersion.STANDARD

    fun orderedTargets(entry: ExpressEntry): List<ExpressTarget> =
        if (entry.id == "douyin_orders" && douyinVersion() == DouyinVersion.LITE) entry.targets.asReversed() else entry.targets
}
