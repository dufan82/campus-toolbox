package cn.campuslife.assistant.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class SchoolTerm(val id: String, val label: String, val smallTerm: Boolean = false, val current: Boolean = false)

/** Boundary for the school's published H5 fields; malformed results never become an empty week. */
object SchoolPortalJson {
    fun payload(raw: JSONObject): JSONObject {
        checkStatus(raw)
        return (raw.opt("data") as? JSONObject) ?: raw
    }
    private fun checkStatus(raw: JSONObject) {
        if (raw.has("code")) require(raw.opt("code")?.toString()?.toIntOrNull() in 1..200) { "学校平台未返回成功结果，请重新登录或稍后再试" }
    }
    fun semesters(raw: JSONObject): List<SchoolTerm> {
        val list = payload(raw).optJSONArray("semesters") ?: error("未取得学期列表，请先在学校页面打开个人课表")
        require(list.length() in 1..100) { "学校返回的学期列表为空或过长" }
        return (0 until list.length()).flatMap { index ->
            val row = list.getJSONObject(index)
            val id = text(row, "value", 80, false)
            val label = text(row, "label", 100, false)
            val current = truth(row.opt("isCurrent"))
            val small = current && truth(row.opt("isXxq"))
            val term = SchoolTerm(id, label, current = current && !small)
            if (small) listOf(term, term.copy(label = "$label（小学期）", smallTerm = true, current = true)) else listOf(term)
        }.distinctBy { it.id to it.smallTerm }
    }
    fun currentTerm(terms: List<SchoolTerm>): SchoolTerm? = terms.filter { it.current }.singleOrNull()
    fun semester(raw: JSONObject, smallTerm: Boolean): Semester {
        val config = payload(raw).optJSONObject("semestersConfig") ?: error("未取得学期日期配置")
        val start = text(config, if (smallTerm) "xxqStartDate" else "startDate", 40, false)
        val date = if (start.length == 10) LocalDate.parse(start) else runCatching {
            OffsetDateTime.parse(start).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate()
        }.getOrElse { LocalDate.parse(start.substringBefore('T').substringBefore(' ')) }
        return Semester(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), number(config, if (smallTerm) "xxqWeek" else "totalWeeks")).checked()
    }
    fun lessons(raw: JSONObject, week: Int): List<SchoolLesson> {
        require(week in 1..60)
        val list = payload(raw).optJSONArray("list") ?: error("学校没有返回本周课程列表，未保存不完整课表")
        require(list.length() <= 1000) { "单周课程数量异常" }
        return (0 until list.length()).map { index ->
            val row = list.getJSONObject(index)
            SchoolLesson(text(row, "course", 80, false), text(row, "teacherName", 80), text(row, "spaceName", 120),
                number(row, "DayIndex"), number(row, "startNode"), number(row, "endNode"), week).also {
                require(it.weekday in 1..7 && it.startPeriod in 1..24 && it.endPeriod in it.startPeriod..24) { "学校课程星期或节次异常，未保存不完整课表" }
            }
        }
    }
    fun periods(raw: JSONObject): List<SchedulePeriod> {
        checkStatus(raw)
        val root = payload(raw)
        val array = raw.opt("data") as? JSONArray
        val records = if (array != null) (0 until array.length()).map { array.getJSONObject(it) }
            else root.keys().asSequence().mapNotNull { root.opt(it) as? JSONObject }.filter { it.has("jcdm") }.toList()
        require(records.isNotEmpty() && records.size <= 24) { "未取得作息时间" }
        val sorted = records.sortedBy { number(it, "jcdm") }
        require(sorted.map { number(it, "jcdm") } == (1..sorted.size).toList()) { "作息节次不连续" }
        return sorted.map { row ->
            SchedulePeriod(text(row, "jcmc", 24).ifBlank { "第${number(row, "jcdm")}节" },
                time(row, "jcskkssj"), time(row, "jcskjssj"))
        }.also { ScheduleData(periods = it).checked() }
    }
    private fun time(row: JSONObject, key: String): LocalTime = LocalTime.parse(text(row, key, 16, false).take(5))
    private fun truth(value: Any?) = value == true || value == 1 || value == "true" || value == "1"
    private fun number(row: JSONObject, key: String): Int {
        val value = row.opt(key)
        val result = when (value) { is Int -> value; is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt(); is String -> value.toIntOrNull(); else -> null }
        return result ?: throw IllegalArgumentException("学校返回的整数字段不完整")
    }
    private fun text(row: JSONObject, key: String, max: Int, optional: Boolean = true): String {
        val raw = row.opt(key)
        if (raw == null || raw === JSONObject.NULL) { require(optional) { "学校返回的课程字段不完整" }; return "" }
        require(raw is String) { "学校返回的文字格式不受支持" }
        val text = raw.trim()
        require(text.length <= max && text.none { it.isISOControl() } && (optional || text.isNotEmpty())) { "学校返回的课程文字为空、过长或格式异常" }
        return text
    }
}
