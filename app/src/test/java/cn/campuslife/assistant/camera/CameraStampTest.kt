package cn.campuslife.assistant.camera

import cn.campuslife.assistant.map.CampusCoordinate
import cn.campuslife.assistant.map.CampusCoordinateData
import cn.campuslife.assistant.map.CampusCatalog
import cn.campuslife.assistant.map.GeoPoint
import cn.campuslife.assistant.map.LocatedFix
import cn.campuslife.watermark.CampusProfile
import cn.campuslife.watermark.FieldKey
import cn.campuslife.watermark.Orientation
import cn.campuslife.watermark.Position
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class CameraStampTest {
    private val time = ZonedDateTime.parse("2026-11-20T12:19:59+08:00")
    private val profile = CampusProfile(mapOf(
        FieldKey.NAME to "  测试同学  ", FieldKey.CLASS_NAME to "示例班", FieldKey.STUDENT_ID to "TEST-123"
    ))

    @Test fun defaultsUseLocalClockAndSharedProfileWithoutStudentId() {
        val stamp = CameraSettings().stamp(time, profile, "实训楼附近")
        assertEquals("12:19", stamp.clock)
        assertEquals("2026-11-20", stamp.date)
        assertEquals("星期五", stamp.weekday)
        assertEquals(Position.BOTTOM_LEFT, stamp.position)
        assertEquals(listOf("自动定位 · 实训楼附近", "姓名 · 测试同学", "班级 · 示例班"), stamp.lines)
        assertFalse(stamp.lines.any { "TEST-123" in it })
    }

    @Test fun manualLocationWinsAndFieldSwitchesApplyIndependently() {
        val selected = setOf(CameraField.DATE, CameraField.PLACE, CameraField.STUDENT_ID, CameraField.NOTE)
        val options = CameraSettings(fields = selected, manualPlace = "   图书馆 ", note = "本次\n活动",
            position = Position.TOP_RIGHT)
        val stamp = options.stamp(time, profile, "定位结果")
        assertEquals("", stamp.clock)
        assertEquals("", stamp.weekday)
        assertEquals("2026-11-20", stamp.date)
        assertEquals(Position.TOP_RIGHT, stamp.position)
        assertEquals(listOf("手填地点 · 图书馆", "学号 · TEST-123", "备注 · 本次 活动"), stamp.lines)
        assertTrue(options.copy(fields = emptySet()).stamp(time, profile, null).isEmpty)
    }

    @Test fun missingLocationIsNotFormattedAsAnActualPlace() {
        assertTrue(CameraSettings().stamp(time, CampusProfile(), null).lines.isEmpty())
    }

    @Test fun locationPrefersOnlyVisibleReviewedNearbyLandmarksAndIgnoresStaleFixes() {
        val point = GeoPoint(25.50, 119.80)
        val id = CampusCatalog.entries.first().id
        val places = CampusCoordinateData(point, listOf(CampusCoordinate(id, point, "test", true)))
        val fix = LocatedFix(point, 12f, 5_000L)
        val name = CampusCatalog.entries.first().name
        assertEquals(name + "附近", cameraLocationLabel(fix, point, places, 6_000))
        assertFalse(cameraLocationLabel(fix, point, places.copy(hiddenIds = setOf(id)), 6_000)!!.contains(name))
        assertFalse(cameraLocationLabel(fix, point, places.copy(records = places.records.map { it.copy(reviewed = false) }), 6_000)!!.contains(name))
        assertFalse(cameraLocationLabel(fix.copy(accuracyMeters = 150f), point, places, 6_000)!!.contains(name))
        assertNull(cameraLocationLabel(fix, point, places, 70_000))
    }

    @Test fun outsideCampusCoordinatesAreApproximateAndNotGuessedAddresses() {
        val point = GeoPoint(30.128189, 120.909248)
        val fix = LocatedFix(point, 20f, 5_000)
        assertEquals("约 30.13°N，120.91°E", cameraLocationLabel(fix, null, null, 6_000))
    }

    @Test fun previewPrefersMatchingAspectAndNeverChangesJpegResolution() {
        val jpeg = CameraSize(4000, 3000)
        val preview = choosePreviewSize(jpeg, listOf(CameraSize(1920,1080), CameraSize(1600,1200),
            CameraSize(1440,1080), CameraSize(640,480)))
        assertEquals(CameraSize(1440,1080), preview)
        assertEquals(CameraSize(3000,4000), jpeg.upright(jpegRotation(90,0,false)))
        assertEquals(jpeg, jpeg.upright(jpegRotation(90,90,false)))
        assertEquals(0, jpegRotation(270,90,true))
    }

    @Test fun mirrorPreservesDimensionsAndFlipsOnlyUprightHorizontalAxis() {
        for (exif in 1..8) {
            val original = Orientation(exif)
            val flipped = original.mirroredHorizontally()
            val p = original.map(0.15, 0.25)
            val q = flipped.map(0.15, 0.25)
            assertEquals(original.swapsAxes, flipped.swapsAxes)
            assertEquals(1.0-p.x, q.x, 0.0001)
            assertEquals(p.y, q.y, 0.0001)
        }
    }
}
