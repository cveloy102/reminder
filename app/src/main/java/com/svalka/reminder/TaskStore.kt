package com.svalka.reminder

import android.content.Context
import android.net.Uri
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

enum class TaskList(val storageValue: String) {
    TASKS("tasks"), SHOPPING("shopping"), LINKS("links");

    companion object {
        fun fromStorage(value: String): TaskList = entries.firstOrNull { it.storageValue == value }
            ?: TASKS
    }
}

data class ReminderTask(
    val id: String,
    val title: String,
    val createdAt: Long,
    val completed: Boolean = false,
    val pinned: Boolean = false,
    val reminderAt: Long? = null,
    val deletedAt: Long? = null,
    val list: TaskList = TaskList.TASKS,
    val url: String? = null
)

fun normalizeLinkUrl(value: String): String? {
    val clean = value.trim()
    if (clean.isEmpty() || clean.any { it.isWhitespace() }) return null
    val prefix = clean.substringBefore(':')
    if (':' in clean && "://" !in clean && '.' !in prefix &&
        prefix.matches(Regex("[A-Za-z][A-Za-z0-9+.-]*"))) return null
    val candidate = if (clean.contains("://")) clean else "https://$clean"
    val parsed = Uri.parse(candidate)
    return candidate.takeIf {
        parsed.scheme in listOf("http", "https") && !parsed.host.isNullOrBlank()
    }
}

fun linkDisplayName(url: String): String =
    Uri.parse(url).host?.removePrefix("www.") ?: url

fun ReminderTask.normalized(): ReminderTask = copy(
    title = if (list == TaskList.LINKS && url != null && title == linkDisplayName(url))
        title else normalizeTaskTitle(title),
    pinned = pinned && !completed
)

fun orderedActiveTasks(tasks: List<ReminderTask>): List<ReminderTask> =
    tasks.filter { it.deletedAt == null }
        .sortedWith(compareBy<ReminderTask> { it.completed }.thenByDescending { it.pinned })

class TaskStore(context: Context) {
    private val taskFile = AtomicFile(File(context.filesDir, "tasks.json"))
    private val shoppingFile = AtomicFile(File(context.filesDir, "shopping.json"))
    private val linksFile = AtomicFile(File(context.filesDir, "links.json"))

    fun load(): List<ReminderTask> = loadFrom(taskFile, TaskList.TASKS) +
        loadFrom(shoppingFile, TaskList.SHOPPING) + loadFrom(linksFile, TaskList.LINKS)

    private fun loadFrom(file: AtomicFile, list: TaskList): List<ReminderTask> = try {
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
                    deletedAt = if (item.isNull("deletedAt")) null else item.getLong("deletedAt"),
                    list = list,
                    url = if (item.isNull("url")) null else item.optString("url").takeIf { it.isNotBlank() }
                ))
            }
        }
    } catch (_: java.io.FileNotFoundException) {
        emptyList()
    }

    fun save(tasks: List<ReminderTask>) {
        saveTo(taskFile, tasks.filter { it.list == TaskList.TASKS })
        saveTo(shoppingFile, tasks.filter { it.list == TaskList.SHOPPING })
        saveTo(linksFile, tasks.filter { it.list == TaskList.LINKS })
    }

    private fun saveTo(file: AtomicFile, tasks: List<ReminderTask>) {
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
                put("list", task.list.storageValue)
                put("url", task.url ?: JSONObject.NULL)
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
