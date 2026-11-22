package cn.campuslife.assistant.camera

import cn.campuslife.assistant.map.CampusCoordinateData
import cn.campuslife.assistant.map.GeoPoint
import cn.campuslife.assistant.map.LocatedFix
import cn.campuslife.assistant.map.MapRules
import cn.campuslife.watermark.CampusProfile
import cn.campuslife.watermark.FieldKey
import cn.campuslife.watermark.Position
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal enum class CameraField(val label: String) {
    TIME("时间"), DATE("日期"), WEEKDAY("星期"), PLACE("地点"),
    NAME("姓名"), CLASS("班级"), STUDENT_ID("学号"), NOTE("备注")
}

internal data class CameraSettings(
    val fields: Set<CameraField> = CameraField.entries.filterNot { it == CameraField.STUDENT_ID }.toSet(),
    val position: Position = Position.BOTTOM_LEFT,
    val anchorX: Double = 0.0,
    val anchorY: Double = 1.0,
    val manualPlace: String = "",
    val note: String = "",
    val automaticPlace: Boolean = false
) {
    fun stamp(time: ZonedDateTime, profile: CampusProfile, autoPlace: String?) = CameraStamp(
        clock = shown(CameraField.TIME, time.format(clockFormat)),
        date = shown(CameraField.DATE, time.format(dateFormat)),
        weekday = shown(CameraField.WEEKDAY, time.format(weekFormat)),
        lines = buildList {
            fun append(field: CameraField, value: String, label: String = field.label, limit: Int = 48) {
                val text = cameraText(value, limit)
                if (field in fields && text.isNotEmpty()) add("$label · $text")
            }
            if (manualPlace.isNotBlank()) append(CameraField.PLACE, manualPlace, "手填地点", 72)
            else append(CameraField.PLACE, autoPlace.orEmpty(), "自动定位", 72)
            append(CameraField.NAME, profile.values[FieldKey.NAME].orEmpty())
            append(CameraField.CLASS, profile.values[FieldKey.CLASS_NAME].orEmpty())
            append(CameraField.STUDENT_ID, profile.values[FieldKey.STUDENT_ID].orEmpty())
            append(CameraField.NOTE, note, limit = 100)
        },
        position = position, anchorX = anchorX, anchorY = anchorY
    )
    private fun shown(field: CameraField, text: String) = if (field in fields) text else ""

    private companion object {
        val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        val dateFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
        val weekFormat = DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE)
    }
}

internal data class CameraStamp(
    val clock: String, val date: String, val weekday: String, val lines: List<String>,
    val position: Position, val anchorX: Double, val anchorY: Double
) {
    val isEmpty get() = clock.isEmpty() && date.isEmpty() && weekday.isEmpty() && lines.isEmpty()
    val readable get() = (listOf(clock, date, weekday) + lines).filter { it.isNotEmpty() }.joinToString("，")
}

private val spaces = Regex("[\\s\\p{Z}\\p{Cc}]+")
internal fun cameraText(value: String, limit: Int): String {
    val cleaned = value.replace(spaces, " ").trim()
    return if (cleaned.codePointCount(0, cleaned.length) <= limit) cleaned
    else cleaned.substring(0, cleaned.offsetByCodePoints(0, limit)).trimEnd()
}

/** A location name is a local proximity hint, never a reverse-geocode request. */
internal fun cameraLocationLabel(
    fix: LocatedFix, gcj: GeoPoint?, campus: CampusCoordinateData?, now: Long
): String? {
    if (!fix.isFresh(now)) return null
    if (gcj != null && campus != null && fix.accuracyMeters <= 60f) {
        val names = campus.visiblePlaces.associate { it.id to it.name }
        val closest = campus.records.asSequence().filter { it.reviewed && it.id in names }
            .map { it to MapRules.distanceMeters(gcj, it.point) }
            .filter { (_, distance) -> distance + fix.accuracyMeters <= 85.0 }
            .minByOrNull { it.second }
        if (closest != null) return names.getValue(closest.first.id) + "附近"
    }
    val point = fix.point
    // A deliberately coarse fallback rather than recording exact coordinates.
    val decimals = if (fix.accuracyMeters > 1000) 1 else 2
    return String.format(Locale.ROOT, "约 %.${decimals}f°%s，%.${decimals}f°%s",
        abs(point.latitude), if (point.latitude < 0) "S" else "N",
        abs(point.longitude), if (point.longitude < 0) "W" else "E")
}

internal data class CameraSize(val width: Int, val height: Int) {
    val pixels get() = width.toLong() * height
    val ratio get() = width.toDouble() / height
    fun upright(rotation: Int) = if (rotation % 180 == 0) this else CameraSize(height, width)
}

/** Prefer the same aspect as the still stream without making preview decode at still resolution. */
internal fun choosePreviewSize(jpeg: CameraSize, sizes: List<CameraSize>): CameraSize {
    val withinPreviewBudget = sizes.filter { maxOf(it.width, it.height) <= 1920 &&
        minOf(it.width, it.height) <= 1080 }
    val sameAspect = sizes.filter { abs(it.ratio / jpeg.ratio - 1.0) < .015 }
    return withinPreviewBudget.filter { it in sameAspect }.maxByOrNull { it.pixels }
        ?: sameAspect.minByOrNull { it.pixels }
        ?: withinPreviewBudget.ifEmpty { sizes }
            .minWith(compareBy<CameraSize> { abs(it.ratio / jpeg.ratio - 1.0) }.thenByDescending { it.pixels })
}

// Display rotation has the opposite sign of the physical orientation used by JPEG_ORIENTATION.
internal fun jpegRotation(sensor: Int, display: Int, front: Boolean) =
    (sensor + (if (front) display else -display) + 360) % 360
