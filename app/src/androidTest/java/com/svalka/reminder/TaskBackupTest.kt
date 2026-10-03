package com.svalka.reminder

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskBackupTest {
    @Test fun activeTasksKeepCompletionBelowPinnedAndManualOrder() {
        val done = ReminderTask("done", "Готово", 1, completed = true)
        val pinnedDone = ReminderTask("pinned-done", "Готово и закреплено", 2, completed = true, pinned = true)
        val open = ReminderTask("open", "Открыто", 3)
        val pinnedOpen = ReminderTask("pinned-open", "Открыто и закреплено", 4, pinned = true)
        val deleted = ReminderTask("deleted", "Удалено", 5, deletedAt = 6)
        assertEquals(
            listOf(pinnedOpen, open, pinnedDone, done),
            orderedActiveTasks(listOf(done, pinnedDone, open, deleted, pinnedOpen))
        )
        assertEquals(
            listOf(pinnedOpen, open, pinnedDone, done),
            orderedActiveTasks(listOf(pinnedOpen, open, pinnedDone, done))
        )
    }

    @Test fun roundTripKeepsRemindersCompletionAndTrash() {
        val original = listOf(
            ReminderTask("one", "Купить молоко", 1000, pinned = true, reminderAt = 4000),
            ReminderTask("two", "Сделано", 2000, completed = true),
            ReminderTask("three", "В корзине", 3000, deletedAt = 3500)
        )
        val decoded = TaskBackupCodec.decode(TaskBackupCodec.encode(original, 5000))
        assertEquals(original, decoded.tasks)
        assertEquals(5000, decoded.exportedAt)
    }

    @Test fun backupKeepsShoppingSeparateAndReadsOldCopiesAsTasks() {
        val shopping = ReminderTask("milk", "Молоко", 1000, list = TaskList.SHOPPING)
        assertEquals(listOf(shopping), TaskBackupCodec.decode(TaskBackupCodec.encode(listOf(shopping))).tasks)

        val oldCopy = """{"app":"com.svalka.reminder","version":1,"exportedAt":2000,"tasks":[{"id":"old","title":"Старое дело","createdAt":1000,"completed":false,"pinned":false,"reminderAt":null,"deletedAt":null}]}"""
        assertEquals(TaskList.TASKS, TaskBackupCodec.decode(oldCopy).tasks.single().list)
    }

    @Test fun backupKeepsLinksAndOptionalNames() {
        val unnamed = ReminderTask("link-one", "example.com", 1000,
            list = TaskList.LINKS, url = "https://example.com/article")
        val named = ReminderTask("link-two", "Полезная статья", 2000,
            list = TaskList.LINKS, url = "https://example.org/read")
        assertEquals(listOf(unnamed, named),
            TaskBackupCodec.decode(TaskBackupCodec.encode(listOf(unnamed, named))).tasks)
        assertEquals("https://example.com/article", normalizeLinkUrl("example.com/article"))
        assertEquals(null, normalizeLinkUrl("javascript:alert(1)"))
    }

    @Test fun invalidOrDuplicateBackupIsRejected() {
        assertThrows(Exception::class.java) { TaskBackupCodec.decode("[]") }
        val task = ReminderTask("same", "Дело", 1000)
        val duplicate = TaskBackupCodec.encode(listOf(task, task))
        assertThrows(IllegalArgumentException::class.java) { TaskBackupCodec.decode(duplicate) }
    }

    @Test fun mergeKeepsCurrentVersionOfExistingTasks() {
        val current = ReminderTask("same", "Новое название", 1000)
        val imported = listOf(current.copy(title = "Старое название"), ReminderTask("extra", "Из файла", 2000))
        assertEquals(listOf(current, imported[1]), TaskBackupCodec.merge(listOf(current), imported))
    }
}
