package cn.campuslife.watermark

enum class FieldKey(val label: String, val personal: Boolean) {
    SCHOOL("学校", true), COLLEGE("学院", true), MAJOR("专业", true), CLASS_NAME("班级", true),
    NAME("姓名", true), STUDENT_ID("学号", true), SEAT_NUMBER("座号", true),
    EVENT_NAME("活动名称", false), EVENT_LOCATION("活动地点", false), CAPTURED_AT("拍摄时间", false), NOTE("备注", false)
}

data class CampusProfile(val values: Map<FieldKey, String> = emptyMap())
data class ActivityDetails(val values: Map<FieldKey, String> = emptyMap())
enum class TextAlignment { LEFT, CENTER, RIGHT }
enum class Position(val label: String) {
    TOP_LEFT("左上"), TOP_RIGHT("右上"), BOTTOM_LEFT("左下"), BOTTOM_RIGHT("右下"), BOTTOM_CENTER("底部居中"), CUSTOM("自由移动")
}
data class WatermarkStyle(
    val fontRatio: Double = 0.032,
    val textColor: Int = -1,
    val bold: Boolean = false,
    val alignment: TextAlignment = TextAlignment.LEFT,
    val lineSpacing: Double = 1.35,
    val backgroundColor: Int = 0xff0f172a.toInt(),
    val backgroundOpacity: Double = 0.64,
    val paddingRatio: Double = 0.018,
    val marginRatio: Double = 0.028,
    val maxWidthRatio: Double = 0.9,
    val position: Position = Position.BOTTOM_RIGHT,
    val anchorX: Double = 0.0,
    val anchorY: Double = 1.0,
    val multiline: Boolean = true
)
// Legacy enum keys remain readable so upgrading does not delete saved profile values.
val defaultOrder = listOf(FieldKey.COLLEGE, FieldKey.CLASS_NAME, FieldKey.NAME,
    FieldKey.SEAT_NUMBER, FieldKey.STUDENT_ID, FieldKey.CAPTURED_AT)
val defaultEnabled = defaultOrder.filterNot { it == FieldKey.STUDENT_ID }.toSet()
data class EditorConfig(val order: List<FieldKey> = defaultOrder, val enabled: Set<FieldKey> = defaultEnabled, val style: WatermarkStyle = WatermarkStyle(), val contentOnly: Boolean = true) {
    fun orderedFields() = (order + defaultOrder).filter { it in defaultOrder }.distinct()
}
data class WatermarkTemplate(val id: String, val name: String, val config: EditorConfig, val builtin: Boolean = false)

object BuiltinTemplates {
    val all: List<WatermarkTemplate> = listOf(
        WatermarkTemplate("campus", "校园活动记录", EditorConfig(), true),
        WatermarkTemplate("college", "学院活动记录", EditorConfig(style = WatermarkStyle(bold = true)), true),
        WatermarkTemplate("volunteer", "志愿服务记录", EditorConfig(enabled = defaultEnabled,
            style = WatermarkStyle(backgroundColor = 0xff123d35.toInt())), true),
        WatermarkTemplate("practice", "实践活动记录", EditorConfig(enabled = defaultEnabled,
            style = WatermarkStyle(position = Position.BOTTOM_RIGHT)), true),
        WatermarkTemplate("custom", "自定义模板", EditorConfig(enabled = setOf(FieldKey.NAME, FieldKey.CAPTURED_AT)), true)
    )
}

fun formattedFields(profile: CampusProfile, details: ActivityDetails, config: EditorConfig): List<String> =
    config.orderedFields().filter { it in config.enabled }.mapNotNull { field ->
        val value = (if (field.personal) profile.values[field] else details.values[field]).orEmpty().trim()
        if(value.isEmpty()) null else if(config.contentOnly) value else "${field.label}：$value"
    }

enum class ExportFormat { JPEG, PNG }
data class ExportPlan(val width: Int, val height: Int, val mimeType: String, val extension: String, val quality: Int, val workingBytes: Long, val sampleSize: Int = 1)
object ExportPolicy {
    fun plan(rawWidth: Int, rawHeight: Int, exif: Int, format: ExportFormat, maxWorkingBytes: Long, maxEdge: Int? = null): ExportPlan {
        require(rawWidth > 0 && rawHeight > 0) { "照片尺寸无效，请重新选择" }
        val pixels = rawWidth.toLong() * rawHeight
        require(pixels <= 80_000_000L) { "照片超过 8000 万像素，请先选择较小尺寸的副本" }
        require(maxEdge == null || maxEdge >= 256) { "输出尺寸过小" }
        val scale = if(maxEdge==null) 1.0 else minOf(1.0,maxEdge.toDouble()/maxOf(rawWidth,rawHeight))
        val outputWidth=maxOf(1,(rawWidth*scale).toInt())
        val outputHeight=maxOf(1,(rawHeight*scale).toInt())
        var sample=1
        while(rawWidth/(sample*2)>=outputWidth && rawHeight/(sample*2)>=outputHeight) sample*=2
        val bytes = rawWidth.toLong()/sample * (rawHeight/sample) * 4 + outputWidth.toLong()*outputHeight*4 + 16_000_000L
        require(bytes <= maxWorkingBytes) { "照片超出本机可用处理内存，请选择较小尺寸的照片；原图未修改" }
        val swap = Orientation(exif).swapsAxes
        val png = format == ExportFormat.PNG
        return ExportPlan(if (swap) outputHeight else outputWidth, if (swap) outputWidth else outputHeight,
            if (png) "image/png" else "image/jpeg", if (png) "png" else "jpg", if (png) 100 else 96, bytes,sample)
    }
}
