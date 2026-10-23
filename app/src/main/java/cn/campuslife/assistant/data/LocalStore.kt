package cn.campuslife.assistant.data

import android.content.Context
import cn.campuslife.watermark.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class LocalStore(context: Context, name: String = "campus_local_v1") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    var recoveryMessage: String? = null
        private set

    fun profile() = CampusProfile(readValues("profile").filterKeys { it.personal })
    fun details() = ActivityDetails(readValues("details").filterKeys { !it.personal })
    fun config() = read("config", EditorConfig()) { decodeConfig(JSONObject(it),migrateLegacyDefault=true) }
    fun templates(): List<WatermarkTemplate> = read("templates", emptyList()) { text ->
        val array = JSONArray(text)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            WatermarkTemplate(item.getString("id"), item.getString("name"), decodeConfig(item.getJSONObject("config")))
        }
    }
    fun draftName(): String? = prefs.getString("draft", null)?.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]+")) }
    fun saveProfile(profile: CampusProfile) = saveValues("profile", profile.values.filterKeys { it.personal })
    fun saveDetails(details: ActivityDetails) = saveValues("details", details.values.filterKeys { !it.personal })
    fun saveConfig(config: EditorConfig) = save("config", encodeConfig(config).toString())
    fun namingFields(): List<FieldKey> = read("naming_fields",ExportNaming.defaultFields) { text ->
        val array=JSONArray(text)
        ExportNaming.fields((0 until array.length()).mapNotNull { index -> FieldKey.entries.find { it.name==array.optString(index) } })
    }
    fun saveNamingFields(fields: List<FieldKey>) = save("naming_fields",JSONArray(ExportNaming.fields(fields).map { it.name }).toString())
    fun saveDraftName(name: String?) { checkSaved(prefs.edit().putString("draft", name).commit()) }
    fun saveTemplates(templates: List<WatermarkTemplate>) {
        require(templates.size <= 30) { "个人模板最多 30 个，请先删除不再使用的模板" }
        val array = JSONArray()
        templates.forEach { template ->
            require(template.name.trim().isNotEmpty() && template.name.length <= 40) { "模板名请填写 1–40 个字" }
            array.put(JSONObject().put("id", template.id).put("name", template.name.trim()).put("config", encodeConfig(template.config)))
        }
        save("templates", array.toString())
    }

    private fun saveValues(key: String, values: Map<FieldKey, String>) {
        val json = JSONObject()
        values.forEach { (field, value) -> json.put(field.name, value) }
        save(key, json.toString())
    }
    private fun readValues(key: String): Map<FieldKey, String> = read(key, emptyMap()) { text ->
        val json = JSONObject(text)
        FieldKey.entries.filter { json.has(it.name) }.associateWith { json.optString(it.name, "").take(2000) }
    }
    private fun save(key: String, text: String) { checkSaved(prefs.edit().putString(key, text).commit()) }
    private fun checkSaved(ok: Boolean) { if (!ok) throw IOException("本机存储失败，请检查剩余空间后重试") }
    private fun <T> read(key: String, fallback: T, decode: (String) -> T): T {
        val text = prefs.getString(key, null) ?: return fallback
        return try { decode(text) } catch (_: Exception) {
            recoveryMessage = "有一项本地设置无法读取，已使用默认设置；其他资料仍保留"
            fallback
        }
    }

    private fun encodeConfig(config: EditorConfig): JSONObject {
        val s = config.style
        return JSONObject().put("version", 2).put("order", JSONArray(config.orderedFields().map { it.name }))
            .put("enabled", JSONArray(config.enabled.map { it.name }))
            .put("contentOnly",config.contentOnly)
            .put("style", JSONObject().put("fontRatio", s.fontRatio).put("textColor", s.textColor)
                .put("bold", s.bold).put("alignment", s.alignment.name).put("lineSpacing", s.lineSpacing)
                .put("backgroundColor", s.backgroundColor).put("backgroundOpacity", s.backgroundOpacity)
                .put("paddingRatio", s.paddingRatio).put("marginRatio", s.marginRatio).put("maxWidthRatio", s.maxWidthRatio)
                .put("position", s.position.name).put("anchorX", s.anchorX).put("anchorY", s.anchorY).put("multiline", s.multiline))
    }
    private fun decodeConfig(json: JSONObject,migrateLegacyDefault: Boolean=false): EditorConfig {
        val version=json.getInt("version")
        require(version in 1..2)
        val defaults = WatermarkStyle()
        val style = json.getJSONObject("style")
        fun number(key: String, default: Double, min: Double, max: Double) =
            style.optDouble(key, default).takeIf { it.isFinite() }?.coerceIn(min, max) ?: default
        fun fields(key: String): List<FieldKey> {
            val array = json.getJSONArray(key)
            return (0 until array.length()).mapNotNull { item -> FieldKey.entries.find { it.name == array.optString(item) } }.distinct()
        }
        val config=EditorConfig(fields("order"), fields("enabled").toSet(), WatermarkStyle(
            fontRatio = number("fontRatio", defaults.fontRatio, .008, .1),
            textColor = style.optInt("textColor", defaults.textColor), bold = style.optBoolean("bold", false),
            alignment = TextAlignment.entries.find { it.name == style.optString("alignment") } ?: defaults.alignment,
            lineSpacing = number("lineSpacing", defaults.lineSpacing, 1.0, 2.2),
            backgroundColor = style.optInt("backgroundColor", defaults.backgroundColor),
            backgroundOpacity = number("backgroundOpacity", defaults.backgroundOpacity, 0.0, 1.0),
            paddingRatio = number("paddingRatio", defaults.paddingRatio, 0.0, .06),
            marginRatio = number("marginRatio", defaults.marginRatio, 0.0, .2),
            maxWidthRatio = number("maxWidthRatio", defaults.maxWidthRatio, .3, 1.0),
            position = Position.entries.find { it.name == style.optString("position") } ?: defaults.position,
            anchorX = number("anchorX", defaults.anchorX, 0.0, 1.0), anchorY = number("anchorY", defaults.anchorY, 0.0, 1.0),
            multiline = style.optBoolean("multiline", true)),contentOnly=json.optBoolean("contentOnly",true))
        // Only the untouched old editor default moves. Never rewrite old templates,
        // dragged/restyled positions, or a left preset deliberately saved in v2.
        return if(migrateLegacyDefault && version==1 && config.style==WatermarkStyle(position=Position.BOTTOM_LEFT))
            config.copy(style=config.style.copy(position=Position.BOTTOM_RIGHT)) else config
    }
}
