package com.svalka.reminder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import com.svalka.reminder.ui.theme.SvalkaTheme
import com.svalka.reminder.ui.theme.ThemeMode
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var store: TaskStore
    private lateinit var reminders: ReminderScheduler
    private var tasks by mutableStateOf<List<ReminderTask>>(emptyList())
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var backupPreview by mutableStateOf<TaskBackup?>(null)
    private var cloudFile by mutableStateOf<Uri?>(null)
    private var cloudSaving by mutableStateOf(false)
    private var cloudError by mutableStateOf<String?>(null)
    private var lastCloudSaveAt by mutableStateOf(0L)
    private val cloudWriteMutex = Mutex()
    private var pendingAutoSave: Job? = null
    private var requestExactAfterNotificationPermission = false

    private val chooseCloudFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        if (uri.authority != "com.google.android.apps.docs.storage") {
            Toast.makeText(this, "Выбери Google Диск в меню слева", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            cloudFile = uri
            cloudError = null
            lastCloudSaveAt = 0L
            getSharedPreferences("cloud_backup", MODE_PRIVATE).edit()
                .putString("uri", uri.toString()).remove("last_save").apply()
            saveToCloud(manual = true)
        } catch (error: Exception) {
            cloudError = error.message ?: "Не удалось подключить файл"
            Toast.makeText(this, "Не удалось подключить файл: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

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
        val cloudSettings = getSharedPreferences("cloud_backup", MODE_PRIVATE)
        cloudFile = cloudSettings.getString("uri", null)?.let(Uri::parse)
            ?.takeIf { saved -> contentResolver.persistedUriPermissions.any { it.uri == saved && it.isWritePermission } }
        lastCloudSaveAt = cloudSettings.getLong("last_save", 0L)
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
                    onSetReminder = ::setReminder,
                    backupPreview = backupPreview,
                    cloudConnected = cloudFile != null,
                    cloudSaving = cloudSaving,
                    cloudError = cloudError,
                    lastCloudSaveAt = lastCloudSaveAt,
                    onChooseCloudFile = { chooseCloudFile.launch("svalka-backup.json") },
                    onSaveCloudNow = { saveToCloud(manual = true) },
                    onDisconnectCloud = ::disconnectCloud,
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
        saveToCloud(manual = false)
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
        saveToCloud(manual = false)
    }

    private fun startExport() {
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date())
        exportFile.launch("svalka-$date.json")
    }

    private fun saveToCloud(manual: Boolean) {
        val target = cloudFile ?: return
        pendingAutoSave?.cancel()
        val snapshot = tasks.toList()
        val job = lifecycleScope.launch {
            if (!manual) delay(3_000)
            cloudWriteMutex.withLock {
                try {
                    cloudSaving = true
                    val data = TaskBackupCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
                    require(data.size <= TaskBackupCodec.MAX_BYTES) { "Список слишком большой для копии" }
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(target, "wt")?.use { it.write(data) }
                            ?: throw IllegalStateException("Не удалось открыть файл")
                    }
                    lastCloudSaveAt = System.currentTimeMillis()
                    cloudError = null
                    getSharedPreferences("cloud_backup", MODE_PRIVATE).edit()
                        .putLong("last_save", lastCloudSaveAt).apply()
                    if (manual) Toast.makeText(this@MainActivity, "Копия передана Google Диску", Toast.LENGTH_SHORT).show()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    cloudError = error.message ?: "Ошибка сохранения"
                    if (manual) Toast.makeText(this@MainActivity, "Не удалось сохранить: ${error.message}", Toast.LENGTH_LONG).show()
                } finally {
                    cloudSaving = false
                }
            }
        }
        if (!manual) pendingAutoSave = job
    }

    private fun disconnectCloud() {
        pendingAutoSave?.cancel()
        cloudFile?.let { uri ->
            runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        cloudFile = null
        cloudError = null
        lastCloudSaveAt = 0L
        getSharedPreferences("cloud_backup", MODE_PRIVATE).edit().clear().apply()
    }

    private fun restoreBackup(replace: Boolean) {
        val imported = backupPreview?.tasks ?: return
        val result = (if (replace) imported else TaskBackupCodec.merge(tasks, imported))
            .map { it.copy(title = normalizeTaskTitle(it.title)) }
        try {
            store.save(result)
            tasks.forEach { reminders.cancel(it.id) }
            tasks = result
            saveToCloud(manual = false)
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
        saveToCloud(manual = false)
        reminders.cancel(task.id)
    }

    private fun setReminder(task: ReminderTask, at: Long) {
        if (at <= System.currentTimeMillis()) return
        save(task.copy(reminderAt = at))
        requestExactAfterNotificationPermission = true
        if (!requestNotificationPermission()) {
            requestExactAfterNotificationPermission = false
            reminders.requestExactAlarmAccessIfNeeded(this)
        }
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
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            reminders.rescheduleAll(tasks)
        }
        if (requestCode == 100 && requestExactAfterNotificationPermission &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            reminders.requestExactAlarmAccessIfNeeded(this)
        }
        requestExactAfterNotificationPermission = false
    }
}
