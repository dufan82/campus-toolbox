package cn.campuslife.assistant.express

enum class RouteKind { LINK, APP_HOME }
enum class ConfigSource { DEFAULT, CUSTOM }
enum class DouyinVersion { STANDARD, LITE }
data class ExpressTarget(val label: String, val packageName: String, val schemes: Set<String>, val links: List<String>)
data class ExpressEntry(val id: String, val title: String, val subtitle: String, val targets: List<ExpressTarget>)
data class RouteCandidate(val packageName: String, val kind: RouteKind, val uri: String? = null)
data class EffectiveExpressConfig(val entry: ExpressEntry, val source: ConfigSource,
    val candidates: List<RouteCandidate>, val customLink: String?, val warning: String? = null)

object ExpressCatalog {
    // Only public URI strings were consulted. No third-party code, images, or task-clearing logic.
    private const val PDD_PAGE = "https://m.pinduoduo.net/mdkd/package?entry_source=18&extra_params=from_wx_jump%3D1&p_channel=0"

    val entries: List<ExpressEntry> = listOf(
        ExpressEntry("pdd_packages", "拼多多包裹", "在拼多多中查看包裹", listOf(
            ExpressTarget("拼多多", "com.xunmeng.pinduoduo", setOf("pinduoduo"), listOf(
                "pinduoduo://com.xunmeng.pinduoduo/mdkd/package", PDD_PAGE)))),
        ExpressEntry("pdd_code", "拼多多身份码", "在拼多多中打开身份码入口", listOf(
            ExpressTarget("拼多多", "com.xunmeng.pinduoduo", setOf("pinduoduo"), listOf(
                "pinduoduo://com.xunmeng.pinduoduo/mdkd/package?tab=ID_CODE&entry_source=11&refer_page_name=login&refer_page_sn=10169",
                "pinduoduo://com.xunmeng.pinduoduo/mdkd/package?tab=ID_CODE&entry_source=11", PDD_PAGE)))),
        ExpressEntry("douyin_orders", "抖音订单", "支持抖音标准版与极速版", listOf(
            ExpressTarget("抖音标准版", "com.ss.android.ugc.aweme", setOf("snssdk1128"),
                listOf("snssdk1128://ecom_order_list")),
            ExpressTarget("抖音极速版", "com.ss.android.ugc.aweme.lite", setOf("snssdk2329"),
                listOf("snssdk2329://ecom_order_list"))))
    )
    fun entry(id: String): ExpressEntry = entries.single { it.id == id }
}
