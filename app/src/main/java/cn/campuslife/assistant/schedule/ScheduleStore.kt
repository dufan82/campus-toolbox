package cn.campuslife.assistant.schedule

import android.content.Context
import cn.campuslife.assistant.data.AtomicJsonFile
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Synchronous storage boundary: Activity dispatches every call onto its retained worker. */
class ScheduleStore(context: Context) {
    private val lock = locks.getOrPut(File(context.applicationContext.filesDir, FILE_NAME).absolutePath) { Any() }
    private val file = AtomicJsonFile(context, FILE_NAME)
    private val importUndo = AtomicJsonFile(context, IMPORT_UNDO_FILE)
    fun load(): ScheduleData = synchronized(lock) { readLatest() }
    fun commit(before: ScheduleData, proposed: ScheduleData): ScheduleData = synchronized(lock) {
        val merged = ScheduleMerge.merge(before, proposed, readLatest())
        file.write(encode(merged))
        merged
    }
    private fun readLatest() = file.read()?.let(::decode) ?: ScheduleData()

    /** Explicit replacement only: validate, detect concurrent edits, save undo, then atomically write. */
    fun replaceWithBackup(expected: ScheduleData, replacement: ScheduleData): ScheduleData = synchronized(lock) {
        replacement.checked()
        val latest = readLatest()
        if (latest != expected) throw ScheduleConflictException("原课表已被其他页面修改，请返回后重新预览导入", latest)
        if (latest == replacement) return@synchronized latest
        importUndo.write(encode(latest))
        file.write(encode(replacement))
        replacement
    }
    fun previousImport(): ScheduleData? = synchronized(lock) { importUndo.read()?.let(::decode) }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        const val FILE_NAME = "schedule-v1.json"
        const val IMPORT_UNDO_FILE = "schedule-import-undo.json"
        fun encode(data: ScheduleData): JSONObject {
            data.checked()
            return JSONObject().put("version", 1)
                .put("semester", data.semester?.let { JSONObject().put("startMonday", it.startMonday.toString()).put("totalWeeks", it.totalWeeks) } ?: JSONObject.NULL)
                .put("periods", JSONArray().apply {
                    data.periods.forEach { put(JSONObject().put("label", it.label).put("start", it.start?.toString() ?: JSONObject.NULL).put("end", it.end?.toString() ?: JSONObject.NULL)) }
                }).put("courses", JSONArray().apply {
                    data.courses.forEach { c ->
                        put(JSONObject().put("id", c.id).put("name", c.name).put("teacher", c.teacher).put("classroom", c.classroom)
                            .put("weekday", c.weekday).put("startPeriod", c.startPeriod).put("endPeriod", c.endPeriod)
                            .put("startWeek", c.startWeek).put("endWeek", c.endWeek).put("pattern", c.pattern.name).put("placeId", c.placeId ?: JSONObject.NULL))
                    }
                })
        }

        fun decode(json: JSONObject): ScheduleData {
            require(integer(json, "version") == 1) { "课程表版本不受支持" }
            val semester = nullable(json, "semester")?.let { raw ->
                require(raw is JSONObject) { "学期数据格式错误" }
                Semester(LocalDate.parse(string(raw, "startMonday", 10)), integer(raw, "totalWeeks"))
            }
            val periods = array(json, "periods", 24).map { p ->
                SchedulePeriod(string(p, "label", 24), time(p, "start"), time(p, "end"))
            }
            val courses = array(json, "courses", 500).map { c ->
                Course(string(c, "id", 36), string(c, "name", 80), string(c, "teacher", 80), string(c, "classroom", 120),
                    integer(c, "weekday"), integer(c, "startPeriod"), integer(c, "endPeriod"),
                    integer(c, "startWeek"), integer(c, "endWeek"), WeekPattern.valueOf(string(c, "pattern", 5)),
                    nullable(c, "placeId")?.let { require(it is String && it.length <= 100) { "地点ID格式错误" }; it })
            }
            return ScheduleData(semester, periods, courses).checked()
        }
        private fun nullable(json: JSONObject, key: String): Any? {
            require(json.has(key)) { "课程表字段缺失" }
            return json.get(key).takeUnless { it === JSONObject.NULL }
        }
        private fun integer(json: JSONObject, key: String): Int {
            val value = json.get(key)
            require(value is Int) { "课程表整数字段格式错误" }
            return value
        }
        private fun string(json: JSONObject, key: String, max: Int): String {
            val value = json.get(key)
            require(value is String && value.length <= max) { "课程表文字字段格式错误" }
            return value
        }
        private fun time(json: JSONObject, key: String): LocalTime? = nullable(json, key)?.let {
            require(it is String && it.matches(Regex("[0-9]{2}:[0-9]{2}"))) { "节次时间格式应为HH:mm" }
            LocalTime.parse(it)
        }
        private fun array(json: JSONObject, key: String, max: Int): List<JSONObject> {
            val array = json.get(key)
            require(array is JSONArray && array.length() <= max) { "课程表列表格式或数量错误" }
            return (0 until array.length()).map { index ->
                val value = array.get(index); require(value is JSONObject) { "课程表记录格式错误" }; value
            }
        }
    }
}
