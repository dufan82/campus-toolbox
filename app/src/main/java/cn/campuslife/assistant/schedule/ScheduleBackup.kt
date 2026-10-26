package cn.campuslife.assistant.schedule

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Only the checked local timetable is serialised. Web storage and credentials never enter it. */
object ScheduleBackup {
    const val MAX_BYTES = 1024 * 1024
    fun write(output: OutputStream, data: ScheduleData) {
        val bytes = JSONObject().put("format", "campus-toolbox-schedule").put("version", 1)
            .put("schedule", ScheduleStore.encode(data)).toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "课表备份过大" }
        output.write(bytes); output.flush()
    }
    fun read(input: InputStream): ScheduleData {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            require(output.size() + count <= MAX_BYTES) { "请选择1MB以内的校园工具箱课表备份" }
            output.write(buffer, 0, count)
        }
        val json = JSONObject(output.toString("UTF-8"))
        require(json.optString("format") == "campus-toolbox-schedule" && json.optInt("version") == 1) { "这不是支持的校园工具箱课表备份" }
        return ScheduleStore.decode(json.getJSONObject("schedule"))
    }
}
