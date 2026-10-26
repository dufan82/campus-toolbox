package cn.campuslife.assistant.schedule

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class SchoolImportResult(val data: ScheduleData, val warnings: List<String>)

/** Sequential, bounded requests to the same authorised school API; never returns a partial term. */
class SchoolPortalClient(private val request: (String, JSONObject?) -> JSONObject) {
    fun terms() = SchoolPortalJson.semesters(request("/semesters", null))
    fun collect(term: SchoolTerm, cancelled: AtomicBoolean, progress: (Int, Int) -> Unit): SchoolImportResult {
        fun checkCancelled() { if (cancelled.get()) throw InterruptedIOException("Import cancelled") }
        checkCancelled()
        val semester = SchoolPortalJson.semester(request("/semesterConfig", JSONObject().put("semester", term.id)), term.smallTerm)
        val lessons = mutableListOf<SchoolLesson>()
        var commonPeriods: List<SchedulePeriod>? = null
        var consistentTimes = true
        for (week in 1..semester.totalWeeks) {
            checkCancelled()
            val rows = SchoolPortalJson.lessons(request("/schedule", JSONObject().put("semester", term.id)
                .put("week", week).put("showxxq", if (term.smallTerm) 1 else 0)), week)
            lessons.addAll(rows)
            require(lessons.size <= 30000) { "课表记录过多，已停止导入" }
            if (consistentTimes) {
                val times = runCatching { SchoolPortalJson.periods(request("/scheduleTime", JSONObject().put("dqz", week))) }.getOrNull()
                if (times == null || (commonPeriods != null && commonPeriods != times)) consistentTimes = false
                else commonPeriods = times
            }
            checkCancelled()
            progress(week, semester.totalWeeks)
        }
        val size = maxOf(lessons.maxOfOrNull { it.endPeriod } ?: 1, commonPeriods?.size ?: 12)
        val periods = if (consistentTimes && commonPeriods != null && commonPeriods.size >= size) commonPeriods
            else (1..size).map { SchedulePeriod("第${it}节") }
        val warnings = if (!consistentTimes || commonPeriods == null || commonPeriods.size < size)
            listOf("学校作息未完整取得或周间有变化。本地保留准确的课程节次，不混用铃声时间；可在课表设置补充作息。") else emptyList()
        checkCancelled()
        return SchoolImportResult(SchoolScheduleConverter.convert(semester, periods, lessons), warnings)
    }

    companion object {
        fun network(authorization: String, cancelled: AtomicBoolean,
            open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) = SchoolPortalClient { path, body ->
            require(path in setOf("/semesters", "/semesterConfig", "/schedule", "/scheduleTime"))
            if (cancelled.get()) throw InterruptedIOException("Import cancelled")
            val token = authorization.removePrefix("Bearer ")
            require(SchoolPortalPolicy.timetableAuthorization("https://jw-api.fjpit.com/api/semesters", mapOf("ba-token" to token)) == authorization) {
                "School authorization is not valid"
            }
            val connection = open(URL("https://jw-api.fjpit.com/api$path"))
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 12000; connection.readTimeout = 15000
                connection.requestMethod = if (body == null) "GET" else "POST"
                connection.setRequestProperty("ba-token", token)
                connection.setRequestProperty("server", "1")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("platform", "h5")
                body?.let {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connection.outputStream.use { output -> output.write(it.toString().toByteArray(Charsets.UTF_8)) }
                }
                if (connection.responseCode != 200) throw IOException("School request failed")
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (cancelled.get()) throw InterruptedIOException("Import cancelled")
                        val count = input.read(buffer); if (count < 0) break
                        if (output.size() + count > 2 * 1024 * 1024) throw IOException("School response exceeds limit")
                        output.write(buffer, 0, count)
                    }
                }
                JSONObject(output.toString("UTF-8"))
            } finally { connection.disconnect() }
        }
    }
}
