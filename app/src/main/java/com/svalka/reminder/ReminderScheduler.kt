package com.svalka.reminder

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

private const val CHANNEL_ID = "reminders_sound_v3"
private val VIBRATION_PATTERN = longArrayOf(0, 140, 80, 180)
private const val MISSED_REMINDER_WINDOW = 24L * 60 * 60 * 1000

private fun reminderSoundUri(context: Context): Uri {
    val name = context.resources.getResourceEntryName(R.raw.reminder_signal)
    return Uri.parse("android.resource://${context.packageName}/raw/$name")
}

class ReminderScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Напоминания", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Дела, о которых пора вспомнить"
                setSound(reminderSoundUri(context), AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            }
            context.getSystemService(NotificationManager::class.java).apply {
                createNotificationChannel(channel)
                deleteNotificationChannel("reminders")
                deleteNotificationChannel("reminders_sound_v2")
            }
        }
    }

    fun schedule(task: ReminderTask) {
        val at = task.effectiveAt ?: return
        if (!task.remindable() || at <= System.currentTimeMillis()) return
        val pending = pendingIntent(task.id)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(id: String) = alarmManager.cancel(pendingIntent(id))

    /**
     * Re-arms every alarm and delivers reminders that were missed (device off, app updated).
     * Returns the tasks, advanced to their next occurrence where a reminder was handled.
     */
    fun rescheduleAll(tasks: List<ReminderTask>): List<ReminderTask> {
        val now = System.currentTimeMillis()
        pruneDeliveredLog(tasks)
        val updated = tasks.map { task ->
            val at = task.effectiveAt
            if (at == null || !task.remindable()) return@map task
            if (at > now) { schedule(task); return@map task }
            val handled = if (now - at <= MISSED_REMINDER_WINDOW) postReminder(context, task) else true
            if (!handled) return@map task
            // Stamped with the time of the change, so sync orders it correctly against edits on other devices.
            task.afterDelivery(now).let { if (it == task) it else it.copy(updatedAt = now) }.also { schedule(it) }
        }
        if (updated != tasks) TaskStore(context).save(updated)
        return updated
    }

    /** Called after a notification for [task] was shown: moves a repeating task to its next date. */
    fun commitDelivery(task: ReminderTask) {
        val updated = task.afterDelivery(System.currentTimeMillis())
        if (updated == task) return
        replaceInStore(updated)
        schedule(updated)
    }

    /** Saves a change made outside the app's screen (notification buttons, delivery), stamped with its time for sync. */
    fun replaceInStore(task: ReminderTask) {
        val store = TaskStore(context)
        val now = System.currentTimeMillis()
        store.save(store.load().map { if (it.id == task.id) (if (it == task) it else task.copy(updatedAt = now)) else it })
        TaskEvents.notifyChanged()
    }

    private fun pruneDeliveredLog(tasks: List<ReminderTask>) {
        val log = context.getSharedPreferences("delivered_reminders", Context.MODE_PRIVATE)
        val known = tasks.mapTo(HashSet()) { it.id }
        val stale = log.all.keys.filter { it !in known }
        if (stale.isNotEmpty()) log.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    fun requestExactAlarmAccessIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
            Toast.makeText(activity, "Разреши точные напоминания; без доступа они могут прийти позже", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            activity.startActivity(intent)
        }
    }

    private fun pendingIntent(id: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.svalka.reminder.NOTIFY"
            data = Uri.parse("svalka://reminder/$id")
            putExtra("task_id", id)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("task_id") ?: return
        val task = TaskStore(context).load().firstOrNull { it.id == id } ?: return
        val at = task.effectiveAt ?: return
        if (!task.remindable() || at > System.currentTimeMillis() + 60_000L) return
        if (postReminder(context, task)) ReminderScheduler(context).commitDelivery(task)
    }
}

/** Buttons under the notification: "Готово" and snooze. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("task_id") ?: return
        val task = TaskStore(context).load().firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        val updated = when (intent.action) {
            ACTION_DONE -> task.markDone()
            ACTION_SNOOZE -> task.snoozedFor(now, intent.getIntExtra("minutes", 15))
            else -> return
        }
        val scheduler = ReminderScheduler(context)
        scheduler.cancel(id)
        scheduler.replaceInStore(updated)
        scheduler.schedule(updated)
        NotificationManagerCompat.from(context).cancel(id.hashCode())
    }

    companion object {
        const val ACTION_DONE = "com.svalka.reminder.DONE"
        const val ACTION_SNOOZE = "com.svalka.reminder.SNOOZE"
    }
}

private fun actionIntent(context: Context, task: ReminderTask, action: String, minutes: Int = 0): PendingIntent {
    val intent = Intent(context, ReminderActionReceiver::class.java).apply {
        this.action = action
        data = Uri.parse("svalka://reminder/${task.id}/$action/$minutes")
        putExtra("task_id", task.id)
        putExtra("minutes", minutes)
    }
    return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** Returns true when the reminder was shown now or had already been shown before. */
private fun postReminder(context: Context, task: ReminderTask): Boolean {
    val at = task.effectiveAt ?: return false
    val delivered = context.getSharedPreferences("delivered_reminders", Context.MODE_PRIVATE)
    if (delivered.getLong(task.id, Long.MIN_VALUE) == at) return true
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return false
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    ReminderScheduler(context).createChannel()
    if (Build.VERSION.SDK_INT >= 26 && context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
    val open = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("СВАЛКА · ПОРА")
        .setContentText(task.title)
        .setStyle(NotificationCompat.BigTextStyle().bigText(task.title))
        .setContentIntent(open)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setColor(0xFFF0443E.toInt()) // the app's red accent
        .setLargeIcon(notificationLargeIcon(context))
        .setSound(reminderSoundUri(context))
        .setVibrate(VIBRATION_PATTERN)
        .addAction(0, "Готово", actionIntent(context, task, ReminderActionReceiver.ACTION_DONE))
        .addAction(0, "Через 15 мин", actionIntent(context, task, ReminderActionReceiver.ACTION_SNOOZE, 15))
        .addAction(0, "Через 1 час", actionIntent(context, task, ReminderActionReceiver.ACTION_SNOOZE, 60))
        .build()
    return try {
        NotificationManagerCompat.from(context).notify(task.id.hashCode(), notification)
        delivered.edit().putLong(task.id, at).apply()
        true
    } catch (_: SecurityException) {
        // Permission can be revoked between the check and notify; retry on next app start.
        false
    }
}

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            ReminderScheduler(context).rescheduleAll(TaskStore(context).load())
            TaskEvents.notifyChanged()
        }
    }
}

/** Lets receivers tell a running MainActivity that the stored tasks changed behind its back. */
object TaskEvents {
    @Volatile var onExternalChange: (() -> Unit)? = null

    fun notifyChanged() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onExternalChange?.invoke() }
    }
}

/** The app icon (black square, white "СВАЛ" + red "КА") for the notification, scaled once and kept. */
private var cachedLargeIcon: android.graphics.Bitmap? = null

private fun notificationLargeIcon(context: Context): android.graphics.Bitmap? {
    cachedLargeIcon?.let { return it }
    return runCatching {
        val size = (64 * context.resources.displayMetrics.density).toInt()
        val source = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.icon_red_ka)
        android.graphics.Bitmap.createScaledBitmap(source, size, size, true).also {
            if (it !== source) source.recycle()
            cachedLargeIcon = it
        }
    }.getOrNull()
}
