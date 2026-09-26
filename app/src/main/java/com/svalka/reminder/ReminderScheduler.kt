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

private const val CHANNEL_ID = "reminders_sound_v2"
private val VIBRATION_PATTERN = longArrayOf(0, 140, 80, 180)

private fun reminderSoundUri(context: Context): Uri {
    val name = context.resources.getResourceEntryName(R.raw.reminder_chime)
    return Uri.parse("android.resource://${context.packageName}/raw/$name")
}

class ReminderScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Напоминания", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Звуковые напоминания о делах"
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
            }
        }
    }

    fun schedule(task: ReminderTask) {
        val at = task.reminderAt ?: return
        if (task.completed || task.deletedAt != null || at <= System.currentTimeMillis()) return
        val pending = pendingIntent(task.id)
        if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(id: String) = alarmManager.cancel(pendingIntent(id))

    fun rescheduleAll(tasks: List<ReminderTask>) = tasks.forEach { schedule(it) }

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
        if (task.completed || task.deletedAt != null || task.reminderAt == null ||
            task.reminderAt > System.currentTimeMillis() + 60_000L
        ) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ReminderScheduler(context).createChannel()
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Пора вспомнить")
            .setContentText(task.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(task.title))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setSound(reminderSoundUri(context))
            .setVibrate(VIBRATION_PATTERN)
            .build()
        NotificationManagerCompat.from(context).notify(task.id.hashCode(), notification)
    }
}

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            ReminderScheduler(context).rescheduleAll(TaskStore(context).load())
        }
    }
}
