package com.svalka.reminder

import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecurrenceTest {
    private lateinit var previousZone: TimeZone

    @Before fun fixZone() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restoreZone() = TimeZone.setDefault(previousZone)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 8, minute: Int = 0): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, minute) }.timeInMillis

    private fun task(
        reminderAt: Long? = null, repeat: RepeatRule = RepeatRule.NONE, completed: Boolean = false,
        snoozeUntil: Long? = null, deletedAt: Long? = null
    ) = ReminderTask("id", "t", 0, completed = completed, reminderAt = reminderAt,
        repeat = repeat, snoozeUntil = snoozeUntil, deletedAt = deletedAt)

    @Test fun dailyMovesToNextDayAfterNow() {
        val next = nextOccurrence(at(2026, 11, 1), RepeatRule.DAILY, at(2026, 11, 1, 8, 1))
        assertEquals(at(2026, 11, 2), next)
    }

    @Test fun dailySkipsMissedDays() {
        val next = nextOccurrence(at(2026, 11, 1), RepeatRule.DAILY, at(2026, 11, 10, 9))
        assertEquals(at(2026, 11, 11), next)
    }

    @Test fun weeklyAddsSevenDays() {
        assertEquals(at(2026, 11, 8), nextOccurrence(at(2026, 11, 1), RepeatRule.WEEKLY, at(2026, 11, 1, 9)))
    }

    @Test fun monthlyClampsToShortMonth() {
        assertEquals(at(2026, 2, 28), nextOccurrence(at(2026, 1, 31), RepeatRule.MONTHLY, at(2026, 1, 31, 9)))
    }

    @Test fun noneHasNoNextOccurrence() {
        assertNull(nextOccurrence(at(2026, 11, 1), RepeatRule.NONE, 0))
    }

    @Test fun deliveryAdvancesRepeatingTaskAndResetsDone() {
        val delivered = task(at(2026, 11, 1), RepeatRule.DAILY, completed = true)
            .afterDelivery(at(2026, 11, 1, 8, 0))
        assertEquals(at(2026, 11, 2), delivered.reminderAt)
        assertFalse(delivered.completed)
    }

    @Test fun deliveryOfOneShotReminderChangesNothing() {
        val original = task(at(2026, 11, 1))
        assertEquals(original, original.afterDelivery(at(2026, 11, 1, 8, 1)))
    }

    @Test fun snoozeWinsAndIsClearedAfterDelivery() {
        val snoozed = task(at(2026, 11, 2), RepeatRule.DAILY).snoozedFor(at(2026, 11, 1), 15)
        assertEquals(at(2026, 11, 1) + 15 * 60_000L, snoozed.effectiveAt)
        val after = snoozed.afterDelivery(snoozed.snoozeUntil!!)
        assertNull(after.snoozeUntil)
        assertEquals(at(2026, 11, 2), after.reminderAt)
    }

    @Test fun markDoneClearsSnoozeButKeepsRepeat() {
        val done = task(at(2026, 11, 2), RepeatRule.DAILY, snoozeUntil = 5L).markDone()
        assertTrue(done.completed)
        assertNull(done.snoozeUntil)
        assertEquals(RepeatRule.DAILY, done.repeat)
        assertTrue(done.remindable())
    }

    @Test fun completedOneShotTaskIsNotRemindable() {
        assertFalse(task(at(2026, 11, 2), completed = true).remindable())
        assertFalse(task(at(2026, 11, 2), deletedAt = 1L).remindable())
        assertFalse(task(null).remindable())
    }

    @Test fun trashOlderThanThirtyDaysIsPurged() {
        val now = 100L * 24 * 60 * 60 * 1000
        val day = 24L * 60 * 60 * 1000
        val fresh = ReminderTask("a", "a", 0, deletedAt = now - 29 * day)
        val old = ReminderTask("b", "b", 0, deletedAt = now - 31 * day)
        val active = ReminderTask("c", "c", 0)
        assertEquals(listOf(fresh, active), purgeExpiredTrash(listOf(fresh, old, active), now))
    }

    @Test fun findsUrlInSharedText() {
        assertEquals("https://example.com/a?b=1", findUrlInText("Смотри: https://example.com/a?b=1."))
        assertEquals("https://example.com", findUrlInText("(https://example.com)"))
        assertNull(findUrlInText("просто текст без ссылки"))
    }

    @Test fun backupRoundTripKeepsRepeatAndSnooze() {
        val original = listOf(task(at(2026, 11, 2), RepeatRule.WEEKLY, snoozeUntil = at(2026, 11, 1, 9)))
        val decoded = TaskBackupCodec.decode(TaskBackupCodec.encode(original, 1L))
        assertEquals(original, decoded.tasks)
    }

    @Test fun oldBackupWithoutRepeatStillLoads() {
        val json = """{"app":"com.svalka.reminder","version":3,"exportedAt":1,"tasks":[
            {"id":"x","title":"Молоко","createdAt":1,"completed":false,"list":"shopping"}]}"""
        val decoded = TaskBackupCodec.decode(json)
        assertEquals(RepeatRule.NONE, decoded.tasks.single().repeat)
        assertEquals(TaskList.SHOPPING, decoded.tasks.single().list)
    }

    @Test fun thoughtsSurviveBackup() {
        val thought = ReminderTask("m", "Лучше выкидывать то, чем не пользуешься", 5L, list = TaskList.THOUGHTS)
        assertEquals(TaskList.THOUGHTS, TaskBackupCodec.decode(TaskBackupCodec.encode(listOf(thought), 1L)).tasks.single().list)
        assertEquals(TaskList.THOUGHTS, TaskList.fromStorage("thoughts"))
    }
}
