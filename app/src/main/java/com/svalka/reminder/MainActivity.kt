package com.svalka.reminder

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var store: TaskStore
    private lateinit var reminders: ReminderScheduler
    private var tasks by mutableStateOf<List<ReminderTask>>(emptyList())
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var backupPreview by mutableStateOf<TaskBackup?>(null)
    private var requestExactAfterNotificationPermission = false

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            val data = TaskBackupCodec.encode(tasks).toByteArray(Charsets.UTF_8)
            require(data.size <= TaskBackupCodec.MAX_BYTES) { "Список слишком большой для копии" }
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                ?: throw IllegalStateException("Не удалось открыть файл")
            Toast.makeText(this, "Копия сохранена", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Не удалось сохранить: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= TaskBackupCodec.MAX_BYTES) { "Файл слишком большой" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw IllegalStateException("Не удалось открыть файл")
            backupPreview = TaskBackupCodec.decode(bytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            Toast.makeText(this, "Не удалось прочитать копию: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = TaskStore(this)
        reminders = ReminderScheduler(this)
        val loadedTasks = store.load()
        tasks = loadedTasks.map { it.copy(title = normalizeTaskTitle(it.title)) }
        if (tasks != loadedTasks) store.save(tasks)
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
                    onReorder = ::reorderTasks,
                    onDelete = ::deletePermanently,
                    onPickReminder = ::pickReminder,
                    backupPreview = backupPreview,
                    onExport = ::startExport,
                    onChooseImport = { importFile.launch(arrayOf("*/*")) },
                    onDismissImport = { backupPreview = null },
                    onConfirmImport = ::restoreBackup
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
        val normalized = task.copy(title = normalizeTaskTitle(task.title))
        val index = tasks.indexOfFirst { it.id == normalized.id }
        tasks = if (index >= 0) {
            tasks.toMutableList().apply { this[index] = normalized }
        } else {
            listOf(normalized) + tasks
        }
        store.save(tasks)
        reminders.cancel(normalized.id)
        reminders.schedule(normalized)
    }

    private fun addTask(title: String) {
        val clean = title.trim()
        if (clean.isNotEmpty()) save(ReminderTask(UUID.randomUUID().toString(), clean, System.currentTimeMillis()))
    }

    private fun reorderTasks(fromId: String, toId: String) {
        if (fromId == toId) return
        val active = orderedActiveTasks(tasks)
        val from = active.indexOfFirst { it.id == fromId }
        val to = active.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0 || active[from].completed != active[to].completed) return
        val reordered = active.toMutableList().apply {
            val moved = removeAt(from).copy(pinned = active[to].pinned)
            val target = indexOfFirst { it.id == toId }
            add(target + if (from < to) 1 else 0, moved)
        }
        tasks = reordered + tasks.filter { it.deletedAt != null }
        store.save(tasks)
    }

    private fun startExport() {
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date())
        exportFile.launch("svalka-$date.json")
    }

    private fun restoreBackup(replace: Boolean) {
        val imported = backupPreview?.tasks ?: return
        val result = (if (replace) imported else TaskBackupCodec.merge(tasks, imported))
            .map { it.copy(title = normalizeTaskTitle(it.title)) }
        try {
            store.save(result)
            tasks.forEach { reminders.cancel(it.id) }
            tasks = result
            reminders.rescheduleAll(result)
            backupPreview = null
            Toast.makeText(this, "Дела восстановлены", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Не удалось восстановить: ${error.message}", Toast.LENGTH_LONG).show()
        }
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
