package com.svalka.reminder

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

fun normalizeTaskTitle(title: String): String {
    val clean = title.trim()
    val firstLetter = clean.indexOfFirst { it.isLetter() }
    return if (firstLetter < 0) clean else clean.replaceRange(
        firstLetter, firstLetter + 1, clean[firstLetter].titlecase(Locale.getDefault())
    )
}

data class ReminderTask(
    val id: String,
    val title: String,
    val createdAt: Long,
    val completed: Boolean = false,
    val pinned: Boolean = false,
    val reminderAt: Long? = null,
    val deletedAt: Long? = null
)

fun ReminderTask.normalized(): ReminderTask = copy(
    title = normalizeTaskTitle(title),
    pinned = pinned && !completed
)

fun orderedActiveTasks(tasks: List<ReminderTask>): List<ReminderTask> =
    tasks.filter { it.deletedAt == null }
        .sortedWith(compareBy<ReminderTask> { it.completed }.thenByDescending { it.pinned })

class TaskStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "tasks.json"))

    fun load(): List<ReminderTask> = try {
        val json = file.openRead().bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(ReminderTask(
                    id = item.getString("id"),
                    title = item.getString("title"),
                    createdAt = item.getLong("createdAt"),
                    completed = item.optBoolean("completed"),
                    pinned = item.optBoolean("pinned"),
                    reminderAt = if (item.isNull("reminderAt")) null else item.getLong("reminderAt"),
                    deletedAt = if (item.isNull("deletedAt")) null else item.getLong("deletedAt")
                ))
            }
        }
    } catch (_: java.io.FileNotFoundException) {
        emptyList()
    }

    fun save(tasks: List<ReminderTask>) {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(JSONObject().apply {
                put("id", task.id)
                put("title", task.title)
                put("createdAt", task.createdAt)
                put("completed", task.completed)
                put("pinned", task.pinned)
                put("reminderAt", task.reminderAt ?: JSONObject.NULL)
                put("deletedAt", task.deletedAt ?: JSONObject.NULL)
            })
        }
        val stream = file.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
