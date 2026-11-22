package cn.campuslife.assistant.camera

import android.content.Context
import cn.campuslife.watermark.Position

/** Camera choices do not replace the album editor's style, draft, or personal data. */
internal class CameraSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("watermark_camera_v1", Context.MODE_PRIVATE)

    fun read(): CameraSettings {
        val defaults = CameraSettings()
        return CameraSettings(
            fields = prefs.getStringSet("fields", null)?.mapNotNull { name ->
                CameraField.entries.find { it.name == name }
            }?.toSet() ?: defaults.fields,
            position = Position.entries.find { it.name == prefs.getString("position", null) } ?: defaults.position,
            anchorX = prefs.getFloat("x", 0f).toDouble().takeIf { it in 0.0..1.0 } ?: 0.0,
            anchorY = prefs.getFloat("y", 1f).toDouble().takeIf { it in 0.0..1.0 } ?: 1.0,
            manualPlace = cameraText(prefs.getString("manual_place", "").orEmpty(), 72),
            note = cameraText(prefs.getString("note", "").orEmpty(), 100),
            automaticPlace = prefs.getBoolean("auto_place", false)
        )
    }

    fun save(value: CameraSettings) {
        prefs.edit().putStringSet("fields", value.fields.map { it.name }.toSet())
            .putString("position", value.position.name).putFloat("x", value.anchorX.toFloat())
            .putFloat("y", value.anchorY.toFloat()).putString("manual_place", value.manualPlace)
            .putString("note", value.note).putBoolean("auto_place", value.automaticPlace).apply()
    }
}
