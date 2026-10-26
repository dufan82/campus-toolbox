package cn.campuslife.assistant.planner

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class Repeat(val label: String) { NONE("不重复"), DAILY("每天"), WEEKLY("每周"), MONTHLY("每月") }
enum class Priority(val label: String) { LOW("低"), NORMAL("普通"), HIGH("高") }

private fun identity(value: String) {
    require(runCatching { UUID.fromString(value).toString()==value }.getOrDefault(false)) { "记录标识无效" }
}
private fun title(value: String) {
    require(value.isNotBlank() && value.length<=120 && value.none { it.isISOControl() }) { "标题请填写 1–120 个字符" }
}
internal fun validDate(value: LocalDate) { require(value.year in 1900..2199) { "日期年份需在 1900–2199 之间" } }
private fun validTime(value: LocalTime?) { require(value==null || (value.second==0 && value.nano==0)) { "时间只支持小时与分钟" } }

data class TodoItem(
    val id: String,
    val title: String,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val priority: Priority = Priority.NORMAL,
    val category: String = "",
    val repeat: Repeat = Repeat.NONE,
    val reminder: Boolean = false,
    val completed: Boolean = false,
    val anchorDate: LocalDate? = dueDate,
    val courseId: String? = null,
    val revision: String = PlannerRules.newId()
) {
    init {
        identity(id); identity(revision); title(title)
        dueDate?.let(::validDate); anchorDate?.let(::validDate); validTime(dueTime)
        require(category.length<=40 && category.none { it.isISOControl() }) { "分类最多 40 个字符" }
        require(courseId==null || (courseId.length in 1..100 && courseId.none { it.isISOControl() })) { "课程标识无效" }
        require(dueTime==null || dueDate!=null) { "请先填写截止日期" }
        require(repeat==Repeat.NONE || (dueDate!=null && anchorDate!=null)) { "重复待办需要截止日期" }
        require(!reminder || (dueDate!=null && dueTime!=null)) { "开启提醒需填写日期与时间" }
        require(anchorDate==null || (dueDate!=null && anchorDate<=dueDate)) { "重复起始日期无效" }
        require(repeat!=Repeat.NONE || anchorDate==dueDate) { "截止日期与起始日期不一致" }
        require(repeat==Repeat.NONE || PlannerRules.nextDate(requireNotNull(anchorDate),repeat,requireNotNull(dueDate).minusDays(1))==dueDate) {
            "截止日期不符合原始重复周期"
        }
    }
}

data class CountdownItem(
    val id: String,
    val title: String,
    val date: LocalDate,
    val yearly: Boolean = false,
    val reminder: Boolean = false,
    val reminderTime: LocalTime = LocalTime.of(9,0),
    val revision: String = PlannerRules.newId()
) {
    init { identity(id); identity(revision); title(title); validDate(date); validTime(reminderTime) }
}

data class PlannerState(
    val version: Int = 1,
    val todos: List<TodoItem> = emptyList(),
    val countdowns: List<CountdownItem> = emptyList(),
    val delivered: Map<String,String> = emptyMap()
) {
    init {
        require(version==1) { "本机记录版本不支持，请更新应用；原文件已保留" }
        require(todos.size+countdowns.size<=2000) { "记录最多 2000 项" }
        val ids=todos.map { it.id }+countdowns.map { it.id }
        require(ids.toSet().size==ids.size) { "记录标识重复" }
        require(delivered.size<=2000 && delivered.all { (key,value) ->
            key.length<=100 && value.length<=150 && key.matches(Regex("(todo|countdown)/[0-9a-f-]{36}")) &&
                value.matches(Regex("[0-9a-f-]{36}:[0-9]{1,14}"))
        }) { "提醒状态无效" }
    }
    fun without(id: String) = copy(todos=todos.filterNot { it.id==id }, countdowns=countdowns.filterNot { it.id==id },
        delivered=delivered.filterKeys { !it.endsWith("/$id") })
}

object PlannerRules {
    fun newId(): String = UUID.randomUUID().toString()
    /** Derive every month from the original date, never from February's clamped day. */
    fun nextDate(anchor: LocalDate, repeat: Repeat, after: LocalDate): LocalDate {
        require(repeat!=Repeat.NONE) { "此待办未设置重复" }
        val floor = maxOf(anchor.minusDays(1),after)
        return when(repeat) {
            Repeat.DAILY -> anchor.plusDays(maxOf(0,ChronoUnit.DAYS.between(anchor,floor)+1))
            Repeat.WEEKLY -> anchor.plusWeeks(maxOf(0,Math.floorDiv(ChronoUnit.DAYS.between(anchor,floor),7)+1))
            Repeat.MONTHLY -> {
                val months=maxOf(0,ChronoUnit.MONTHS.between(YearMonth.from(anchor),YearMonth.from(floor)))
                val candidate=anchor.plusMonths(months)
                if(candidate>floor) candidate else anchor.plusMonths(months+1)
            }
            Repeat.NONE -> error("No recurrence")
        }
    }
    fun complete(item: TodoItem, today: LocalDate): TodoItem {
        if(item.repeat==Repeat.NONE) return item.copy(completed=!item.completed,revision=newId())
        val next=nextDate(requireNotNull(item.anchorDate),item.repeat,maxOf(today,requireNotNull(item.dueDate)))
        validDate(next)
        return item.copy(dueDate=next,completed=false,revision=newId())
    }
    fun anniversary(anchor: LocalDate, year: Int): LocalDate = anchor.withYear(year)
    fun countdownDate(item: CountdownItem, today: LocalDate): LocalDate {
        if(!item.yearly || today<item.date) return item.date
        val candidate=anniversary(item.date,today.year)
        return if(candidate<today) anniversary(item.date,today.year+1) else candidate
    }
    fun days(item: CountdownItem, today: LocalDate): Long = ChronoUnit.DAYS.between(today,countdownDate(item,today))
}
