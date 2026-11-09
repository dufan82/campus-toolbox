package cn.campuslife.watermark

/** Lightweight, schema-compatible editor choices; does not reset the saved anchor. */
object WatermarkChoices {
    enum class Background { NONE, DARK, LIGHT }
    fun text(style: WatermarkStyle, black: Boolean) =
        style.copy(textColor = if (black) 0xff000000.toInt() else -1)
    fun horizontal(style: WatermarkStyle, enabled: Boolean) = style.copy(multiline = !enabled)
    fun background(style: WatermarkStyle, choice: Background) = when (choice) {
        Background.NONE -> style.copy(backgroundOpacity = 0.0)
        Background.DARK -> style.copy(backgroundColor = 0xff000000.toInt(), backgroundOpacity = 0.64)
        Background.LIGHT -> style.copy(backgroundColor = -1, backgroundOpacity = 0.75)
    }
}
