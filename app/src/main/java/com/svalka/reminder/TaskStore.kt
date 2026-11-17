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
    TASKS("tasks"), SHOPPING("shopping"), LINKS("links"), THOUGHTS("thoughts");

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
    val url: String? = null,
    val repeat: RepeatRule = RepeatRule.NONE,
    val snoozeUntil: Long? = null
)

const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

fun purgeExpiredTrash(tasks: List<ReminderTask>, now: Long): List<ReminderTask> =
    tasks.filterNot { it.deletedAt != null && now - it.deletedAt > TRASH_RETENTION_MS }

fun ReminderTask.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("createdAt", createdAt)
    put("completed", completed)
    put("pinned", pinned)
    put("reminderAt", reminderAt ?: JSONObject.NULL)
    put("deletedAt", deletedAt ?: JSONObject.NULL)
    put("list", list.storageValue)
    put("url", url ?: JSONObject.NULL)
    put("repeat", repeat.storageValue)
    put("snoozeUntil", snoozeUntil ?: JSONObject.NULL)
}

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

class TaskStore(private val context: Context) {
    private val taskFile = AtomicFile(File(context.filesDir, "tasks.json"))
    private val shoppingFile = AtomicFile(File(context.filesDir, "shopping.json"))
    private val linksFile = AtomicFile(File(context.filesDir, "links.json"))
    private val thoughtsFile = AtomicFile(File(context.filesDir, "thoughts.json"))

    /** Names of data files that could not be read and were set aside as *.corrupt-*. */
    var recoveredFiles: List<String> = emptyList()
        private set

    fun load(): List<ReminderTask> {
        val problems = mutableListOf<String>()
        val result = loadFrom(taskFile, TaskList.TASKS, problems) +
            loadFrom(shoppingFile, TaskList.SHOPPING, problems) +
            loadFrom(linksFile, TaskList.LINKS, problems) +
            loadFrom(thoughtsFile, TaskList.THOUGHTS, problems)
        if (problems.isNotEmpty()) recoveredFiles = problems
        return result
    }

    private fun loadFrom(file: AtomicFile, list: TaskList, problems: MutableList<String>): List<ReminderTask> = try {
        val json = file.openRead().bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) add(parseTask(array.getJSONObject(i), list))
        }
    } catch (_: java.io.FileNotFoundException) {
        emptyList()
    } catch (_: Exception) {
        // A damaged file must not crash the app on every launch: keep it aside for manual recovery.
        val broken = file.baseFile
        val aside = File(broken.parentFile, "${broken.name}.corrupt-${System.currentTimeMillis()}")
        runCatching { broken.renameTo(aside) }
        problems += broken.name
        emptyList()
    }

    private fun parseTask(item: JSONObject, list: TaskList) = ReminderTask(
        id = item.getString("id"),
        title = item.getString("title"),
        createdAt = item.getLong("createdAt"),
        completed = item.optBoolean("completed"),
        pinned = item.optBoolean("pinned"),
        reminderAt = if (item.isNull("reminderAt")) null else item.getLong("reminderAt"),
        deletedAt = if (item.isNull("deletedAt")) null else item.getLong("deletedAt"),
        list = list,
        url = if (item.isNull("url")) null else item.optString("url").takeIf { it.isNotBlank() },
        repeat = RepeatRule.fromStorage(item.optString("repeat", "none")),
        snoozeUntil = if (item.isNull("snoozeUntil")) null else item.getLong("snoozeUntil")
    )

    fun save(tasks: List<ReminderTask>) {
        saveTo(taskFile, tasks.filter { it.list == TaskList.TASKS })
        saveTo(shoppingFile, tasks.filter { it.list == TaskList.SHOPPING })
        saveTo(linksFile, tasks.filter { it.list == TaskList.LINKS })
        saveTo(thoughtsFile, tasks.filter { it.list == TaskList.THOUGHTS })
    }

    private fun saveTo(file: AtomicFile, tasks: List<ReminderTask>) {
        val array = JSONArray()
        tasks.forEach { array.put(it.toJson()) }
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
