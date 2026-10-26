package cn.campuslife.assistant.planner

import android.content.Context
import cn.campuslife.assistant.data.AtomicJsonFile
import cn.campuslife.assistant.reminder.ReminderScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** The controller's AtomicJsonFile owns replacement/rollback. No fallback clears a bad document. */
class PlannerStore(context: Context) {
    private val file=AtomicJsonFile(context,"planner-v1.json")
    fun read(): PlannerState {
        val json=file.read() ?: return PlannerState()
        return try { decode(json) } catch(error: Exception) {
            throw IOException("待办记录无法读取，原文件已保留。当前只读，请保留应用数据并联系维护者。",error)
        }
    }
    fun write(state: PlannerState) { file.write(encode(state)) }
    companion object {
        private fun JSONObject.string(key: String): String = (get(key) as? String) ?: throw IllegalArgumentException("Invalid string field")
        private fun JSONObject.bool(key: String): Boolean = (get(key) as? Boolean) ?: throw IllegalArgumentException("Invalid boolean field")
        private fun JSONObject.optional(key: String): String? = if(isNull(key)) null else string(key)
        private fun JSONObject.array(key: String): JSONArray = get(key) as? JSONArray ?: throw IllegalArgumentException("Invalid list field")
        private fun date(value: String?) = value?.let { LocalDate.parse(it) }
        private fun time(value: String?) = value?.let { LocalTime.parse(it) }
        fun decode(json: JSONObject): PlannerState {
            require(json.get("version") is Int && json.getInt("version")==1) { "Unsupported planner version" }
            val todoJson=json.array("todos")
            val countdownJson=json.array("countdowns")
            require(todoJson.length()+countdownJson.length()<=2000)
            val todos=(0 until todoJson.length()).map { index ->
                val item=todoJson.getJSONObject(index)
                TodoItem(item.string("id"),item.string("title"),date(item.optional("dueDate")),time(item.optional("dueTime")),
                    Priority.valueOf(item.string("priority")),item.string("category"),Repeat.valueOf(item.string("repeat")),
                    item.bool("reminder"),item.bool("completed"),date(item.optional("anchorDate")),item.optional("courseId"),item.string("revision"))
            }
            val countdowns=(0 until countdownJson.length()).map { index ->
                val item=countdownJson.getJSONObject(index)
                CountdownItem(item.string("id"),item.string("title"),LocalDate.parse(item.string("date")),item.bool("yearly"),
                    item.bool("reminder"),LocalTime.parse(item.string("reminderTime")),item.string("revision"))
            }
            val delivered=json.getJSONObject("delivered")
            require(delivered.length()<=2000)
            return PlannerState(todos=todos,countdowns=countdowns,delivered=delivered.keys().asSequence().associateWith { delivered.string(it) })
        }
        fun encode(state: PlannerState): JSONObject = JSONObject().put("version",state.version)
            .put("todos",JSONArray().apply { state.todos.forEach { item -> put(JSONObject()
                .put("id",item.id).put("title",item.title).put("dueDate",item.dueDate?.toString() ?: JSONObject.NULL)
                .put("dueTime",item.dueTime?.toString() ?: JSONObject.NULL).put("priority",item.priority.name)
                .put("category",item.category).put("repeat",item.repeat.name).put("reminder",item.reminder)
                .put("completed",item.completed).put("anchorDate",item.anchorDate?.toString() ?: JSONObject.NULL)
                .put("courseId",item.courseId ?: JSONObject.NULL).put("revision",item.revision)) } })
            .put("countdowns",JSONArray().apply { state.countdowns.forEach { item -> put(JSONObject()
                .put("id",item.id).put("title",item.title).put("date",item.date.toString()).put("yearly",item.yearly)
                .put("reminder",item.reminder).put("reminderTime",item.reminderTime.toString()).put("revision",item.revision)) } })
            .put("delivered",JSONObject(state.delivered))
    }
}

/** One process-wide worker serializes UI mutations, broadcast delivery and alarm reconciliation. */
object PlannerRuntime {
    private val executor=Executors.newSingleThreadExecutor { runnable -> Thread(runnable,"planner-io") }
    fun execute(block: ()->Unit) { executor.execute(block) }
}

class PlannerRepository private constructor(context: Context) {
    private val context=context.applicationContext
    private val store=PlannerStore(this.context)
    /** Callbacks execute on planner-io; Activities explicitly post to the main thread. */
    fun load(callback: (Result<PlannerState>)->Unit) = PlannerRuntime.execute {
        val result=runCatching { store.read() }
        result.getOrNull()?.let { runCatching { ReminderScheduler(context).reconcile(it,it) } }
        callback(result)
    }
    fun change(transform: (PlannerState)->PlannerState, callback: (Result<PlannerState>)->Unit) = PlannerRuntime.execute {
        callback(runCatching {
            val old=store.read()
            val changed=transform(old)
            val identities=changed.todos.map { "todo/${it.id}" }.toSet()+changed.countdowns.map { "countdown/${it.id}" }
            val next=changed.copy(delivered=changed.delivered.filterKeys { it in identities })
            store.write(next)
            // A scheduling/permission problem must never turn a successful save into an apparent failure.
            runCatching { ReminderScheduler(context).reconcile(old,next) }
            next
        })
    }
    companion object {
        private val instances=ConcurrentHashMap<String,PlannerRepository>()
        fun get(context: Context): PlannerRepository = instances.getOrPut(context.applicationContext.filesDir.absolutePath) { PlannerRepository(context) }
    }
}
