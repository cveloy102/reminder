package com.svalka.reminder.sync

import com.svalka.reminder.ReminderTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private fun remote(t: ReminderTask, purged: Boolean = false) = RemoteItem("r-${t.id}", "2026-01-01 00:00:00.000Z", t, purged)

    @Test fun newerRemoteEditWins() {
        val dirty = mutableSetOf("a")
        val result = SyncMerge.applyRemote(listOf(ReminderTask("a", "Старое", 1, updatedAt = 10)),
            listOf(remote(ReminderTask("a", "Новое", 1, updatedAt = 20))), dirty, mutableSetOf())
        assertEquals("Новое", result.single().title)
        assertTrue(dirty.isEmpty())
    }

    @Test fun newerLocalEditStaysAndIsUploadedAgain() {
        val dirty = mutableSetOf<String>()
        val result = SyncMerge.applyRemote(listOf(ReminderTask("a", "Моё", 1, updatedAt = 30)),
            listOf(remote(ReminderTask("a", "Чужое", 1, updatedAt = 20))), dirty, mutableSetOf())
        assertEquals("Моё", result.single().title)
        assertTrue("a" in dirty)
    }

    @Test fun newRecordsOnTopAndPurgeRemoves() {
        val result = SyncMerge.applyRemote(listOf(ReminderTask("a", "A", 1), ReminderTask("b", "B", 2)),
            listOf(remote(ReminderTask("c", "C", 5, updatedAt = 5)), remote(ReminderTask("b", "", 0), purged = true)),
            mutableSetOf(), mutableSetOf())
        assertEquals(listOf("c", "a"), result.map { it.id })
    }

    @Test fun serverJsonRoundTrip() {
        val task = ReminderTask("x", "Молоко", 5, completed = true, reminderAt = 100, updatedAt = 7)
        val back = SyncApi.fromJson(SyncApi.toJson(task, "owner").put("id", "rec").put("updated", "u"))
        assertEquals(task, back.task)
    }

    @Test fun normalizesServerAddress() {
        assertEquals("https://sync.example.com", SyncApi.normalizeUrl(" sync.example.com/ "))
        assertEquals(null, SyncApi.normalizeUrl("два слова"))
    }
}
