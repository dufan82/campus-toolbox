package cn.campuslife.assistant.gate

import cn.campuslife.assistant.home.HomeLayout
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class GateRouteTest {
    @Test fun eachGateAndDirectionKeepsItsExactOriginalUrl() {
        val expected = mapOf(
            "EAST_IN" to listOf("东门", "东门入校", "https://wx.fjpit.com/wechat/api.security/accessGate?xmdm=10066&crlx=1"),
            "EAST_OUT" to listOf("东门", "东门出校", "https://wx.fjpit.com/wechat/api.security/accessGate?xmdm=10067&crlx=2"),
            "NORTH_IN" to listOf("北门", "北门入校", "https://wx.fjpit.com/wechat/api.security/accessGate?xmdm=10062&crlx=1"),
            "NORTH_OUT" to listOf("北门", "北门出校", "https://wx.fjpit.com/wechat/api.security/accessGate?xmdm=10063&crlx=2")
        )
        assertEquals(expected, GateRoute.entries.associate { it.name to listOf(it.gate, it.title, it.url) })
        assertEquals(4, GateRoute.entries.map { it.url }.toSet().size)
        GateRoute.entries.forEach { route ->
            val uri = URI(route.url)
            assertEquals("https", uri.scheme)
            assertEquals("wx.fjpit.com", uri.host)
            assertEquals("/wechat/api.security/accessGate", uri.path)
            assertNull(uri.userInfo)
            assertNull(uri.fragment)
            assertEquals("平潭校区", route.campus)
            assertEquals(-1, uri.port)
        }
    }

    @Test fun retiredGateDoesNotResetAnExistingHiddenTool() {
        val old = HomeLayout(listOf("gate", "schedule", "map", "planner", "watermark"), setOf("gate", "watermark"))
        assertEquals(HomeLayout(listOf("schedule", "map", "planner", "watermark", "story", "daily60"), setOf("watermark")), old.normalized())
        assertEquals(listOf("schedule", "map", "planner", "story", "daily60"), old.visible())
    }

    @Test fun upgradePreservesOldOrderAndHiddenToolsWithoutAddingGate() {
        val old = HomeLayout(listOf("planner", "watermark", "map", "schedule"), setOf("watermark", "map"))
        val updated = old.normalized()
        assertEquals(listOf("planner", "watermark", "map", "schedule", "story", "daily60"), updated.order)
        assertEquals(setOf("watermark", "map"), updated.hidden)
        assertEquals(listOf("planner", "schedule", "story", "daily60"), updated.visible())
        assertEquals(updated, updated.normalized())
        assertFalse(updated.order.any { it in setOf("pdd_packages", "pdd_code", "douyin_orders") })
    }
}
