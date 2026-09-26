package com.svalka.reminder

import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderDeliveryTest {
    @Test fun scheduledReminderPostsNotification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TaskStore(context)
        val scheduler = ReminderScheduler(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val id = UUID.randomUUID().toString()
        val task = ReminderTask(id, "Проверка доставки", System.currentTimeMillis(),
            reminderAt = System.currentTimeMillis() + 8_000)
        store.save(store.load() + task)
        try {
            scheduler.createChannel()
            scheduler.schedule(task)
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline &&
                notifications.activeNotifications.none { it.id == id.hashCode() }) {
                Thread.sleep(500)
            }
            assertTrue("Запланированное уведомление не появилось",
                notifications.activeNotifications.any { it.id == id.hashCode() })
            val posted = notifications.activeNotifications.first { it.id == id.hashCode() }
            assertEquals("СВАЛКА · ПОРА", posted.notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("Проверка доставки", posted.notification.extras.getString(Notification.EXTRA_TEXT))
            val sound = notifications.getNotificationChannel("reminders_sound_v3").sound
            assertTrue(sound.toString().endsWith("/raw/reminder_signal"))
            context.contentResolver.openInputStream(sound).use { stream ->
                assertTrue("Звуковой файл канала недоступен", stream != null && stream.read() >= 0)
            }
        } finally {
            scheduler.cancel(id)
            notifications.cancel(id.hashCode())
            store.save(store.load().filterNot { it.id == id })
        }
    }

    @Test fun recentlyMissedReminderAppearsOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TaskStore(context)
        val scheduler = ReminderScheduler(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val id = UUID.randomUUID().toString()
        val task = ReminderTask(id, "Пропущенное дело", System.currentTimeMillis(),
            reminderAt = System.currentTimeMillis() - 60_000)
        store.save(store.load() + task)
        try {
            scheduler.rescheduleAll(listOf(task))
            assertTrue(notifications.activeNotifications.any { it.id == id.hashCode() })
            notifications.cancel(id.hashCode())
            scheduler.rescheduleAll(listOf(task))
            assertTrue(notifications.activeNotifications.none { it.id == id.hashCode() })
        } finally {
            scheduler.cancel(id)
            notifications.cancel(id.hashCode())
            store.save(store.load().filterNot { it.id == id })
        }
    }
}
