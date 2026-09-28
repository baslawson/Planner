package com.example.itinerary.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.util.UUID

enum class TaskPriority(val label: String, val rank: Int) { LOW("Low", 0), NORMAL("Normal", 1), HIGH("High", 2) }

@Entity(tableName = "tasks")
data class PlannerTask(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val dueDate: LocalDate? = null,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val notes: String = "",
    val done: Boolean = false,
    val reminderAt: Long? = null,
    @ColumnInfo(defaultValue = "'NONE'") val repeat: String = "NONE",
    @ColumnInfo(defaultValue = "7") val repeatDays: Int = 7,
    @ColumnInfo(defaultValue = "0") val repeatAnchorDay: Int = 0,
    val nextTaskId: String? = null,
    @ColumnInfo(defaultValue = "'[]'") val checklist: List<ChecklistEntry> = emptyList(),
    @ColumnInfo(defaultValue = "'[]'") val attachments: List<Attachment> = emptyList(),
    @ColumnInfo(defaultValue = "'[]'") val prerequisiteIds: List<String> = emptyList(),
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY id") fun observe(): Flow<List<PlannerTask>>
    @Query("SELECT * FROM tasks ORDER BY id") suspend fun all(): List<PlannerTask>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun byId(id: String): PlannerTask?
    @Insert suspend fun insert(task: PlannerTask)
    @Insert suspend fun insertAll(tasks: List<PlannerTask>)
    @Update suspend fun update(task: PlannerTask)
    @Query("UPDATE tasks SET done = :done WHERE id = :id") suspend fun setDone(id: String, done: Boolean)
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM tasks") suspend fun deleteAll()
}

object Tasks {
    val order: Comparator<PlannerTask> = compareBy<PlannerTask> { it.done }
        .thenByDescending { it.priority.rank }.thenBy { Search.normalize(it.title) }.thenBy { it.id }

    fun visible(tasks: List<PlannerTask>, range: AgendaRange, query: String, today: LocalDate, showCompleted: Boolean): List<PlannerTask> {
        val needle = Search.normalize(query.trim())
        val weekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        return tasks.filter { task ->
            val due = task.dueDate
            (showCompleted || !task.done) && (needle.isEmpty() || Search.normalize(task.title).contains(needle)) &&
                (due == null || when (range) {
                    AgendaRange.ALL -> true
                    AgendaRange.TODAY -> due <= today
                    AgendaRange.THIS_WEEK -> due <= weekEnd
                    AgendaRange.UPCOMING -> true
                })
        }.sortedWith(order)
    }
    fun validate(task: PlannerTask) {
        require(task.id.isNotBlank() && task.id.length <= 100)
        require(task.title.isNotBlank() && task.title.length <= 500)
        require(task.notes.length <= 20_000)
        require(TaskRepeat.valid(task.repeat))
        require(task.repeatDays in 1..3650 && task.repeatAnchorDay in 0..31)
        require(task.nextTaskId == null || task.nextTaskId.isNotBlank() && task.nextTaskId.length <= 100 && task.nextTaskId != task.id)
        require(task.prerequisiteIds.size <= 100 && task.prerequisiteIds.distinct().size == task.prerequisiteIds.size)
        require(task.prerequisiteIds.all { it.isNotBlank() && it.length <= 100 && it != task.id })
        ChecklistCodec.validate(task.checklist)
        require(task.attachments.size <= 100)
        require(task.attachments.map { it.fileName }.distinct().size == task.attachments.size)
        require(task.attachments.all { it.url == null && Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(it.fileName) && it.name.isNotBlank() })
        require(task.reminderAt == null || task.reminderAt in 1..253402300799999L)
    }
}

object TaskCodec {
    fun encode(tasks: List<PlannerTask>): JSONArray = JSONArray().apply {
        tasks.forEach { task -> put(JSONObject().put("id", task.id).put("title", task.title)
            .put("dueDate", task.dueDate?.toString() ?: JSONObject.NULL).put("priority", task.priority.name)
            .put("notes", task.notes).put("done", task.done)
            .put("reminderAt", task.reminderAt ?: JSONObject.NULL)
            .put("repeat", task.repeat).put("repeatDays", task.repeatDays).put("repeatAnchorDay", task.repeatAnchorDay)
            .put("nextTaskId", task.nextTaskId ?: JSONObject.NULL)
            .put("prerequisiteIds", JSONArray(task.prerequisiteIds))
            .put("checklist", JSONArray(ChecklistCodec.encode(task.checklist)))
            .put("attachments", DraftCodec.attachments(task.attachments))) }
    }
    fun decode(array: JSONArray): List<PlannerTask> = List(array.length()) { index ->
        val value = array.getJSONObject(index)
        PlannerTask(value.getString("id"), value.getString("title"),
            if (value.isNull("dueDate")) null else LocalDate.parse(value.getString("dueDate")),
            TaskPriority.valueOf(value.getString("priority")), value.getString("notes"), value.getBoolean("done"),
            if (value.isNull("reminderAt")) null else {
                val timestamp = value.get("reminderAt")
                require(timestamp is Long || timestamp is Int) { "Invalid task reminder time" }
                (timestamp as Number).toLong()
            }, repeat = value.optString("repeat", "NONE"), repeatDays = value.optInt("repeatDays", 7),
            repeatAnchorDay = value.optInt("repeatAnchorDay", 0),
            nextTaskId = if (value.isNull("nextTaskId")) null else value.getString("nextTaskId"),
            checklist = ChecklistCodec.decode(value.optJSONArray("checklist")?.toString() ?: "[]"),
            attachments = DraftCodec.attachments(value.optJSONArray("attachments")),
            prerequisiteIds = StringListCodec.decode(value.optJSONArray("prerequisiteIds") ?: JSONArray()))
            .also(Tasks::validate)
    }.also { tasks -> require(tasks.map { it.id }.distinct().size == tasks.size) }
}
