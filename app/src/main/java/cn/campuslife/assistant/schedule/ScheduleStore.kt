package cn.campuslife.assistant.schedule

import android.content.Context
import cn.campuslife.assistant.data.AtomicJsonFile
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class SchoolRegistry(val managed: Map<String, Course> = emptyMap(), val protectedIds: Set<String> = emptySet())
data class ScheduleSnapshot(val data: ScheduleData, val registry: SchoolRegistry)

/** Synchronous storage boundary: Activity dispatches every call onto its retained worker. */
class ScheduleStore(context: Context) {
    private val lock = locks.getOrPut(File(context.applicationContext.filesDir, FILE_NAME).absolutePath) { Any() }
    private val file = AtomicJsonFile(context, FILE_NAME)
    private val importUndo = AtomicJsonFile(context, IMPORT_UNDO_FILE)
    fun load(): ScheduleData = synchronized(lock) { readSnapshot().data }
    fun loadSnapshot(): ScheduleSnapshot = synchronized(lock) { readSnapshot() }
    fun commit(before: ScheduleData, proposed: ScheduleData): ScheduleData = synchronized(lock) {
        val current = readSnapshot()
        val merged = ScheduleMerge.merge(before, proposed, current.data)
        val changed = current.registry.managed.filter { (id, course) -> merged.courses.find { it.id == id } != course }.keys
        val settingsChanged = current.data.semester != merged.semester || current.data.periods != merged.periods
        val revoked = if (settingsChanged) current.registry.managed.keys else changed
        val registry = SchoolRegistry(current.registry.managed - revoked, current.registry.protectedIds + revoked)
        file.write(encodeSnapshot(ScheduleSnapshot(merged, registry)))
        merged
    }
    private fun readSnapshot(): ScheduleSnapshot = file.read()?.let(::decodeSnapshot) ?: ScheduleSnapshot(ScheduleData(), SchoolRegistry())

    /** Full replacement remains explicit. Only a confirmed school import registers ownership. */
    fun replaceWithBackup(expected: ScheduleData, replacement: ScheduleData, schoolImport: Boolean = false): ScheduleData = synchronized(lock) {
        replacement.checked()
        val latest = readSnapshot()
        if (latest.data != expected) throw ScheduleConflictException("原课表已被其他页面修改，请返回后重新预览导入", latest.data)
        val next = ScheduleSnapshot(replacement, if (schoolImport) SchoolRegistry(replacement.courses.associateBy { it.id }) else SchoolRegistry())
        if (latest == next) return@synchronized latest.data
        if (latest.data != replacement) importUndo.write(encodeSnapshot(latest))
        file.write(encodeSnapshot(next))
        replacement
    }
    fun applyFutureWithBackup(expected: ScheduleSnapshot, school: ScheduleData, selected: Set<String>, today: LocalDate): SchoolFuturePlan = synchronized(lock) {
        val latest = readSnapshot()
        if (latest != expected) throw ScheduleConflictException("原课表或来源已变化，请返回后重新预览", latest.data)
        require(today == LocalDate.now()) { "日期已变化，请重新预览" }
        val plan = SchoolFutureMerge.plan(latest.data, school, latest.registry.managed, selected, today, latest.registry.protectedIds)
        val next = ScheduleSnapshot(plan.data, SchoolRegistry(plan.managed, latest.registry.protectedIds))
        require(today == LocalDate.now()) { "日期已变化，请重新预览" }
        if (next != latest) {
            if (latest.data != next.data) importUndo.write(encodeSnapshot(latest))
            require(today == LocalDate.now()) { "日期已变化，请重新预览" }
            file.write(encodeSnapshot(next))
        }
        plan
    }
    fun previousImport(): ScheduleData? = synchronized(lock) { importUndo.read()?.let(::decode) }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        const val FILE_NAME = "schedule-v1.json"
        const val IMPORT_UNDO_FILE = "schedule-import-undo.json"
        fun encodeSnapshot(snapshot: ScheduleSnapshot): JSONObject {
            val data = snapshot.data.checked()
            require(snapshot.registry.managed.all { (id, course) -> id == course.id && data.courses.contains(course) }) { "学校来源记录与课表不一致" }
            require(snapshot.registry.managed.keys.intersect(snapshot.registry.protectedIds).isEmpty()) { "来源状态冲突" }
            require(snapshot.registry.protectedIds.size <= 10000 && snapshot.registry.protectedIds.all { it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) }) { "受保护课程记录无效" }
            return encode(data).put("schoolManaged", encode(data.copy(courses = snapshot.registry.managed.values.toList())).getJSONArray("courses"))
                .put("schoolProtectedIds", JSONArray(snapshot.registry.protectedIds.sorted()))
        }
        fun decodeSnapshot(json: JSONObject): ScheduleSnapshot {
            val data = decode(json)
            if (!json.has("schoolManaged") && !json.has("schoolProtectedIds")) return ScheduleSnapshot(data, SchoolRegistry())
            require(json.has("schoolManaged") && json.has("schoolProtectedIds")) { "学校来源记录不完整" }
            val raw = json.get("schoolManaged"); require(raw is JSONArray && raw.length() <= 500) { "学校来源格式错误" }
            val clone = JSONObject(json.toString()).put("courses", raw)
            val managed = decode(clone).courses.associateBy { it.id }
            val protected = json.get("schoolProtectedIds"); require(protected is JSONArray && protected.length() <= 10000) { "保护记录格式错误" }
            val ids = (0 until protected.length()).map { index -> protected.get(index).also { require(it is String) } as String }
            require(ids.distinct().size == ids.size) { "保护记录重复" }
            val snapshot = ScheduleSnapshot(data, SchoolRegistry(managed, ids.toSet()))
            encodeSnapshot(snapshot) // Validate consistency and identifiers without writing.
            return snapshot
        }
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
