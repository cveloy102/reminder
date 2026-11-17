package com.svalka.reminder

import org.json.JSONArray
import org.json.JSONObject

data class TaskBackup(val tasks: List<ReminderTask>, val exportedAt: Long)

object TaskBackupCodec {
    const val MAX_BYTES = 5 * 1024 * 1024

    fun encode(tasks: List<ReminderTask>, exportedAt: Long = System.currentTimeMillis()): String =
        JSONObject().apply {
            put("app", "com.svalka.reminder")
            put("version", 4)
            put("exportedAt", exportedAt)
            put("tasks", JSONArray().apply { tasks.forEach { put(it.toJson()) } })
        }.toString(2)

    fun decode(json: String): TaskBackup {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Файл слишком большой" }
        val root = JSONObject(json)
        require(root.opt("app") == "com.svalka.reminder" && root.optInt("version", -1) in 1..4) {
            "Это не копия СВАЛКИ или её версия не поддерживается"
        }
        val exportedAt = root.requiredLong("exportedAt")
        val array = root.optJSONArray("tasks") ?: throw IllegalArgumentException("В копии нет списка дел")
        require(array.length() <= 10_000) { "В копии слишком много дел" }
        val ids = HashSet<String>()
        val tasks = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.opt("id") as? String ?: throw IllegalArgumentException("Повреждён ID дела")
                val title = item.opt("title") as? String ?: throw IllegalArgumentException("Повреждён текст дела")
                require(id.isNotBlank() && ids.add(id)) { "В копии повторяются дела" }
                require(title.isNotBlank() && title.length <= 10_000) { "Повреждён текст дела" }
                val completed = item.opt("completed") as? Boolean
                    ?: throw IllegalArgumentException("Повреждён статус дела")
                val pinned = item.opt("pinned") as? Boolean ?: false
                add(ReminderTask(
                    id = id,
                    title = title,
                    createdAt = item.requiredLong("createdAt"),
                    completed = completed,
                    pinned = pinned,
                    reminderAt = item.optionalLong("reminderAt"),
                    deletedAt = item.optionalLong("deletedAt"),
                    list = TaskList.fromStorage(item.optString("list", "tasks")),
                    url = if (item.isNull("url")) null else item.optString("url").takeIf { it.isNotBlank() },
                    repeat = RepeatRule.fromStorage(item.optString("repeat", "none")),
                    snoozeUntil = item.optionalLong("snoozeUntil")
                ))
            }
        }
        return TaskBackup(tasks, exportedAt)
    }

    fun merge(current: List<ReminderTask>, imported: List<ReminderTask>): List<ReminderTask> {
        val existingIds = current.mapTo(HashSet()) { it.id }
        return current + imported.filter { it.id !in existingIds }
    }

    private fun JSONObject.requiredLong(key: String): Long {
        val value = opt(key) as? Number ?: throw IllegalArgumentException("Повреждено поле $key")
        return value.toString().toLongOrNull() ?: throw IllegalArgumentException("Повреждено поле $key")
    }

    private fun JSONObject.optionalLong(key: String): Long? =
        if (isNull(key)) null else requiredLong(key)
}
