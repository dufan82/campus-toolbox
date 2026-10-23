package cn.campuslife.assistant.express

import org.junit.Assert.*
import org.junit.Test

/** JVM only: no Android APIs. Literal expectations protect routing and validation boundaries. */
class ExpressRoutesTest {
    private class MemorySettings : ExpressSettings {
        private val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test fun onlyRequestedThreeEntriesAreAvailableInShortcutOrder() {
        assertEquals(listOf("pdd_packages","pdd_code","douyin_orders"), ExpressCatalog.entries.map { it.id })
    }

    @Test fun packagesTryPageLinksBeforeAppHome() {
        val plan = ExpressConfiguration(MemorySettings()).effective("pdd_packages")
        assertEquals("pinduoduo://com.xunmeng.pinduoduo/mdkd/package", plan.candidates.first().uri)
        assertEquals("com.xunmeng.pinduoduo", plan.candidates.first().packageName)
        assertEquals(RouteKind.APP_HOME, plan.candidates.last().kind)
        assertTrue(plan.candidates.dropLast(1).all { it.kind == RouteKind.LINK })
    }

    @Test fun savedCustomLinkIsFirstInTheSameEffectivePlanUsedByConsumers() {
        val settings = MemorySettings()
        val config = ExpressConfiguration(settings)
        assertNull(config.saveLink("pdd_code", "  pinduoduo://custom  "))
        val reopened = ExpressConfiguration(settings).effective("pdd_code")
        assertEquals("pinduoduo://custom", reopened.candidates.first().uri)
        assertEquals(ConfigSource.CUSTOM, reopened.source)
        assertTrue(reopened.candidates.all { it.packageName == "com.xunmeng.pinduoduo" })
        assertEquals(ConfigSource.DEFAULT, config.effective("pdd_packages").source)
    }

    @Test fun restoringOneEntryKeepsOtherEntriesConfigured() {
        val config = ExpressConfiguration(MemorySettings())
        config.saveLink("pdd_code", "https://example.com/code")
        config.saveLink("pdd_packages", "pinduoduo://packages")
        config.restore("pdd_code")
        assertEquals(ConfigSource.DEFAULT, config.effective("pdd_code").source)
        assertEquals(ConfigSource.CUSTOM, config.effective("pdd_packages").source)
    }

    @Test fun dangerousAndForeignSchemesCannotReplaceValidStoredLink() {
        val config = ExpressConfiguration(MemorySettings())
        config.saveLink("pdd_code", "https://example.com/code")
        listOf("intent://x#Intent;package=other;end", "file:///sdcard/a", "javascript:alert(1)",
            "content://a/b", "taobao://m.taobao.com/x", "https://", "https://a b",
            "https://example.com/%0Aevil", "https://user:pass@example.com", "https://example.com/\nq").forEach {
            assertNotNull("must reject $it", config.saveLink("pdd_code", it))
        }
        assertEquals("https://example.com/code", config.effective("pdd_code").candidates.first().uri)
    }

    @Test fun httpAndHttpsRemainPinnedToThePlatformPackage() {
        val config = ExpressConfiguration(MemorySettings())
        listOf("http://example.com/code", "https://example.com/code").forEach {
            assertNull(config.saveLink("pdd_code", it))
            assertEquals("com.xunmeng.pinduoduo", config.effective("pdd_code").candidates.first().packageName)
        }
    }

    @Test fun litePreferenceOrdersAllPageAttemptsBeforeHomeFallbacks() {
        val config = ExpressConfiguration(MemorySettings())
        config.setDouyinVersion(DouyinVersion.LITE)
        val routes = config.effective("douyin_orders").candidates
        assertEquals("com.ss.android.ugc.aweme.lite", routes.first().packageName)
        assertEquals("snssdk2329://ecom_order_list", routes.first().uri)
        assertEquals(listOf("com.ss.android.ugc.aweme.lite", "com.ss.android.ugc.aweme"),
            routes.filter { it.kind == RouteKind.APP_HOME }.map { it.packageName })
        val firstHome = routes.indexOfFirst { it.kind == RouteKind.APP_HOME }
        assertTrue(routes.take(firstHome).all { it.kind == RouteKind.LINK })
        assertTrue(routes.drop(firstHome).all { it.kind == RouteKind.APP_HOME })
    }

    @Test fun changingDouyinVersionDoesNotUseAnIncompatibleCustomScheme() {
        val config = ExpressConfiguration(MemorySettings())
        assertNull(config.saveLink("douyin_orders", "snssdk1128://custom"))
        config.setDouyinVersion(DouyinVersion.LITE)
        val plan = config.effective("douyin_orders")
        assertEquals(ConfigSource.DEFAULT, plan.source)
        assertNotNull(plan.warning)
        assertEquals("snssdk2329://ecom_order_list", plan.candidates.first().uri)
    }

    @Test fun clearingCustomLinkKeepsTheSelectedDouyinVersion() {
        val config = ExpressConfiguration(MemorySettings())
        config.setDouyinVersion(DouyinVersion.LITE)
        config.saveLink("douyin_orders", "snssdk2329://custom")
        assertNull(config.saveLink("douyin_orders", ""))
        val effective = config.effective("douyin_orders")
        assertEquals(ConfigSource.DEFAULT, effective.source)
        assertEquals("com.ss.android.ugc.aweme.lite", effective.candidates.first().packageName)
    }

    @Test fun corruptedStoredLinksAreIgnoredAndCannotReachTheExecutor() {
        val settings = MemorySettings()
        settings.write("link.pdd_packages", "intent://bad")
        val plan = ExpressConfiguration(settings).effective("pdd_packages")
        assertEquals(ConfigSource.DEFAULT, plan.source)
        assertNotNull(plan.warning)
        assertTrue(plan.candidates.none { it.uri?.startsWith("intent:") == true })
    }

    @Test fun everyRouteStaysInsideItsFixedPackageAndSchemeAllowlist() {
        val config = ExpressConfiguration(MemorySettings())
        ExpressCatalog.entries.forEach { entry ->
            val routes = config.effective(entry.id).candidates
            assertTrue(routes.isNotEmpty())
            assertEquals(routes.size, routes.distinct().size)
            routes.forEach { candidate ->
                val target = entry.targets.single { it.packageName == candidate.packageName }
                candidate.uri?.let { assertNull(LinkPolicy.error(it, target.schemes)) }
            }
        }
    }
}
