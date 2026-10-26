package cn.campuslife.assistant.batch

import android.content.Context
import cn.campuslife.assistant.data.AtomicJsonFile
import cn.campuslife.watermark.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class BatchPersistenceException(cause: Exception) : IOException("批量任务记录保存失败，已暂停。请检查剩余空间后重试；请勿卸载应用", cause)

class BatchJobStore(context: Context, name: String = "batch_watermark_v1.json") {
    private val file = AtomicJsonFile(context, name)
    fun load(): BatchJob? = file.read()?.let { BatchJson.decode(it) }
    fun save(job: BatchJob) {
        try { file.write(BatchJson.encode(job)) } catch (error: Exception) { throw BatchPersistenceException(error) }
    }
}

/** Strict schema: unknown versions/malformed fields throw; the caller keeps the source and blocks writes. */
internal object BatchJson {
    private fun values(values: Map<FieldKey, String>) = JSONObject().also { json -> values.forEach { (key, value) -> json.put(key.name, value) } }
    private fun values(json: JSONObject): Map<FieldKey, String> = json.keys().asSequence().associate { key ->
        FieldKey.valueOf(key) to json.getString(key).also { require(it.length <= 2000) }
    }
    private fun fields(json: JSONArray) = (0 until json.length()).map { FieldKey.valueOf(json.getString(it)) }.also {
        require(it.distinct().size == it.size && it.size <= FieldKey.entries.size)
    }
    private fun nullable(json: JSONObject, key: String): String? = if (json.isNull(key)) null else json.getString(key)
    fun encode(job: BatchJob): JSONObject {
        val o = job.options; val c = o.config; val s = c.style
        val style = JSONObject().put("fontRatio", s.fontRatio).put("textColor", s.textColor).put("bold", s.bold)
            .put("alignment", s.alignment.name).put("lineSpacing", s.lineSpacing).put("backgroundColor", s.backgroundColor)
            .put("backgroundOpacity", s.backgroundOpacity).put("paddingRatio", s.paddingRatio).put("marginRatio", s.marginRatio)
            .put("maxWidthRatio", s.maxWidthRatio).put("position", s.position.name).put("anchorX", s.anchorX)
            .put("anchorY", s.anchorY).put("multiline", s.multiline)
        return JSONObject().put("version", 1).put("id", job.id).put("state", job.state.name)
            .put("stop", job.stopAfterCurrent).put("cancel", job.cancelRequested).put("tree", job.treeUri ?: JSONObject.NULL)
            .put("options", JSONObject().put("profile", values(o.profile.values)).put("details", values(o.details.values))
                .put("order", JSONArray(c.order.map { it.name })).put("enabled", JSONArray(c.enabled.map { it.name }))
                .put("contentOnly", c.contentOnly).put("style", style).put("naming", JSONArray(o.namingFields.map { it.name }))
                .put("format", o.format.name).put("maxEdge", o.maxEdge ?: JSONObject.NULL)
                .put("unifiedTime", o.unifiedTime).put("timestamp", o.timestamp))
            .put("items", JSONArray().also { items -> job.items.forEach { item ->
                items.put(JSONObject().put("uri", item.input.uri).put("name", item.input.displayName)
                    .put("persisted", item.input.persisted).put("bytes", item.input.bytes ?: JSONObject.NULL)
                    .put("state", item.state.name).put("attempt", item.attempt)
                    .put("outputUri", item.outputUri ?: JSONObject.NULL).put("outputName", item.outputName ?: JSONObject.NULL)
                    .put("error", item.error ?: JSONObject.NULL).put("importedName", item.importedName ?: JSONObject.NULL))
            } })
    }
    fun decode(json: JSONObject): BatchJob {
        require(json.getInt("version") == 1) { "无法读取此版本批量任务，原记录已保留" }
        val o = json.getJSONObject("options"); val s = o.getJSONObject("style")
        fun number(key: String, min: Double, max: Double) = s.getDouble(key).also { require(it.isFinite() && it in min..max) }
        val config = EditorConfig(fields(o.getJSONArray("order")), fields(o.getJSONArray("enabled")).toSet(),
            WatermarkStyle(number("fontRatio", .008, .1), s.getInt("textColor"), s.getBoolean("bold"),
                TextAlignment.valueOf(s.getString("alignment")), number("lineSpacing", 1.0, 2.2), s.getInt("backgroundColor"),
                number("backgroundOpacity", 0.0, 1.0), number("paddingRatio", 0.0, .06), number("marginRatio", 0.0, .2),
                number("maxWidthRatio", .3, 1.0), Position.valueOf(s.getString("position")), number("anchorX", 0.0, 1.0),
                number("anchorY", 0.0, 1.0), s.getBoolean("multiline")), o.getBoolean("contentOnly"))
        val options = BatchOptions.freeze(CampusProfile(values(o.getJSONObject("profile"))), ActivityDetails(values(o.getJSONObject("details"))),
            config, fields(o.getJSONArray("naming")), ExportFormat.valueOf(o.getString("format")),
            if (o.isNull("maxEdge")) null else o.getInt("maxEdge"), o.getBoolean("unifiedTime"), o.getString("timestamp").also { require(it.length <= 100) })
        val array = json.getJSONArray("items"); require(array.length() in 1..50)
        val items = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val input = BatchInput(item.getString("uri").also { require(it.startsWith("content://") && it.length <= 16000) },
                item.getString("name").also { require(it.length <= 2000) }, item.getBoolean("persisted"),
                if (item.isNull("bytes")) null else item.getLong("bytes").also { require(it >= 0) })
            val result = BatchItem(input, BatchItemState.valueOf(item.getString("state")), item.getInt("attempt").also { require(it >= 0) },
                nullable(item, "outputUri")?.also { require(it.startsWith("content://") && it != input.uri) },
                nullable(item, "outputName")?.also { require(ExportNaming.error(ExportNaming.baseName(it)) == null) },
                nullable(item, "error"), nullable(item, "importedName")?.also { require(it.matches(Regex("photo_[a-f0-9-]{36}\\.img"))) })
            if (result.state in setOf(BatchItemState.WRITING, BatchItemState.WRITTEN, BatchItemState.SUCCESS))
                require(result.outputUri != null && result.outputName != null)
            result
        }
        require(items.map { it.input.uri }.distinct().size == items.size)
        return BatchJob(json.getString("id").also { require(it.length in 1..100) }, options, items,
            nullable(json, "tree")?.also { require(it.startsWith("content://")) }, BatchRunState.valueOf(json.getString("state")),
            json.getBoolean("stop"), json.getBoolean("cancel"))
    }
}
