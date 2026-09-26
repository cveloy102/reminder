package com.svalka.reminder

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskBackupTest {
    @Test fun roundTripKeepsRemindersCompletionAndTrash() {
        val original = listOf(
            ReminderTask("one", "Купить молоко", 1000, reminderAt = 4000),
            ReminderTask("two", "Сделано", 2000, completed = true),
            ReminderTask("three", "В корзине", 3000, deletedAt = 3500)
        )
        val decoded = TaskBackupCodec.decode(TaskBackupCodec.encode(original, 5000))
        assertEquals(original, decoded.tasks)
        assertEquals(5000, decoded.exportedAt)
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
