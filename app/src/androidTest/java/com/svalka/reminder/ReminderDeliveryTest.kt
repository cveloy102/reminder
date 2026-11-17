package com.svalka.reminder

import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderDeliveryTest {
    @get:Rule val notificationPermission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        else GrantPermissionRule.grant()

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
            if (InstrumentationRegistry.getArguments().getString("manualSoundCheck") == "true") {
                Thread.sleep(30_000)
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
            val gone = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < gone && notifications.activeNotifications.any { it.id == id.hashCode() }) {
                Thread.sleep(100)
            }
            scheduler.rescheduleAll(listOf(task))
            Thread.sleep(500)
            assertTrue("Пропущенное напоминание не должно прийти второй раз",
                notifications.activeNotifications.none { it.id == id.hashCode() })
        } finally {
            scheduler.cancel(id)
            notifications.cancel(id.hashCode())
            store.save(store.load().filterNot { it.id == id })
        }
    }

    @Test fun repeatingReminderMovesToNextDayAfterDelivery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TaskStore(context)
        val scheduler = ReminderScheduler(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val id = UUID.randomUUID().toString()
        val at = System.currentTimeMillis() - 60_000
        val task = ReminderTask(id, "Повтор каждый день", System.currentTimeMillis(),
            reminderAt = at, repeat = RepeatRule.DAILY, completed = true)
        store.save(store.load() + task)
        try {
            val result = scheduler.rescheduleAll(listOf(task)).single()
            assertTrue(notifications.activeNotifications.any { it.id == id.hashCode() })
            assertTrue("Повтор должен уйти в будущее", result.reminderAt!! > System.currentTimeMillis())
            assertFalse("Повторяющееся дело сбрасывается", result.completed)
            assertEquals(result, store.load().first { it.id == id })
        } finally {
            scheduler.cancel(id)
            notifications.cancel(id.hashCode())
            store.save(store.load().filterNot { it.id == id })
        }
    }

    @Test fun notificationButtonsMarkDoneAndSnooze() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TaskStore(context)
        val scheduler = ReminderScheduler(context)
        val id = UUID.randomUUID().toString()
        val task = ReminderTask(id, "Кнопки уведомления", System.currentTimeMillis(),
            reminderAt = System.currentTimeMillis() + 3_600_000)
        store.save(store.load() + task)
        try {
            fun press(action: String, minutes: Int = 0) = ReminderActionReceiver().onReceive(
                context, Intent(action).putExtra("task_id", id).putExtra("minutes", minutes))
            press(ReminderActionReceiver.ACTION_SNOOZE, 15)
            val snoozed = store.load().first { it.id == id }
            assertTrue(snoozed.snoozeUntil!! > System.currentTimeMillis() + 14 * 60_000)
            assertEquals(task.reminderAt, snoozed.reminderAt)
            press(ReminderActionReceiver.ACTION_DONE)
            val done = store.load().first { it.id == id }
            assertTrue(done.completed)
            assertNull(done.snoozeUntil)
        } finally {
            scheduler.cancel(id)
            store.save(store.load().filterNot { it.id == id })
        }
    }
}
