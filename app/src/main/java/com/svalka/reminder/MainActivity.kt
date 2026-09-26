package com.svalka.reminder

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.svalka.reminder.ui.theme.SvalkaTheme
import com.svalka.reminder.ui.theme.ThemeMode
import java.util.Calendar
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var store: TaskStore
    private lateinit var reminders: ReminderScheduler
    private var tasks by mutableStateOf<List<ReminderTask>>(emptyList())
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var requestExactAfterNotificationPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TaskStore(this)
        reminders = ReminderScheduler(this)
        tasks = store.load()
        themeMode = ThemeMode.fromValue(getSharedPreferences("settings", MODE_PRIVATE).getString("theme", null))
        reminders.createChannel()
        enableEdgeToEdge()
        setContent {
            val dark = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            SvalkaTheme(themeMode) {
                SvalkaScreen(
                    tasks = tasks,
                    themeMode = themeMode,
                    onThemeChange = { mode ->
                        themeMode = mode
                        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("theme", mode.name).apply()
                    },
                    onAdd = ::addTask,
                    onUpdate = ::save,
                    onDelete = ::deletePermanently,
                    onPickReminder = ::pickReminder
                )
            }
        }
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post {
            window.decorView.display?.supportedModes?.maxOfOrNull { it.refreshRate }?.let { maximum ->
                window.attributes = window.attributes.apply { preferredRefreshRate = maximum }
            }
        }
        if (::reminders.isInitialized) reminders.rescheduleAll(store.load())
    }

    private fun save(task: ReminderTask) {
        val index = tasks.indexOfFirst { it.id == task.id }
        tasks = if (index >= 0) {
            tasks.toMutableList().apply { this[index] = task }
        } else {
            listOf(task) + tasks
        }
        store.save(tasks)
        reminders.cancel(task.id)
        reminders.schedule(task)
    }

    private fun addTask(title: String) {
        val clean = title.trim()
        if (clean.isNotEmpty()) save(ReminderTask(UUID.randomUUID().toString(), clean, System.currentTimeMillis()))
    }

    private fun deletePermanently(task: ReminderTask) {
        tasks = tasks.filterNot { it.id == task.id }
        store.save(tasks)
        reminders.cancel(task.id)
    }

    private fun pickReminder(task: ReminderTask) {
        val initial = Calendar.getInstance().apply { timeInMillis = task.reminderAt ?: System.currentTimeMillis() }
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val at = Calendar.getInstance().apply {
                    set(year, month, day, hour, minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (at <= System.currentTimeMillis()) {
                    Toast.makeText(this, "Выбери время в будущем", Toast.LENGTH_SHORT).show()
                } else {
                    save(task.copy(reminderAt = at))
                    requestExactAfterNotificationPermission = true
                    if (!requestNotificationPermission()) {
                        requestExactAfterNotificationPermission = false
                        reminders.requestExactAlarmAccessIfNeeded(this)
                    }
                }
            }, initial.get(Calendar.HOUR_OF_DAY), initial.get(Calendar.MINUTE), true).show()
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun requestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            return true
        }
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && requestExactAfterNotificationPermission &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            reminders.requestExactAlarmAccessIfNeeded(this)
        }
        requestExactAfterNotificationPermission = false
    }
}
