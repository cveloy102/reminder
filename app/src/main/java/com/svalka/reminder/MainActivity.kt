package com.svalka.reminder

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
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
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.svalka.reminder.ui.theme.SvalkaTheme
import com.svalka.reminder.ui.theme.ThemeMode
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var store: TaskStore
    private lateinit var reminders: ReminderScheduler
    private var tasks by mutableStateOf<List<ReminderTask>>(emptyList())
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    private var backupPreview by mutableStateOf<TaskBackup?>(null)
    private val driveScope = Scope("https://www.googleapis.com/auth/drive.file")
    private var driveConnected by mutableStateOf(false)
    private var driveBusy by mutableStateOf(false)
    private var lastDriveBackupAt by mutableStateOf(0L)
    private var pendingDriveSnapshot: ByteArray? = null
    private var requestExactAfterNotificationPermission = false

    private val driveAuthorization = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            try {
                finishDriveAuthorization(
                    Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data!!)
                )
            } catch (error: Exception) {
                driveFailed("Не удалось подключить Google Диск: ${error.message}")
            }
        } else {
            driveFailed("Подключение Google Диска отменено")
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
        val driveSettings = getSharedPreferences("drive_backup", MODE_PRIVATE)
        driveConnected = driveSettings.getBoolean("connected", false)
        lastDriveBackupAt = driveSettings.getLong("last_backup", 0L)
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
                    driveConnected = driveConnected,
                    driveBusy = driveBusy,
                    lastDriveBackupAt = lastDriveBackupAt,
                    onConnectDrive = { authorizeDrive(null) },
                    onBackupToDrive = ::backupToDrive,
                    onDisconnectDrive = ::disconnectDrive,
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

    private fun backupToDrive() {
        try {
            val snapshot = TaskBackupCodec.encode(tasks).toByteArray(Charsets.UTF_8)
            require(snapshot.size <= TaskBackupCodec.MAX_BYTES) { "Список слишком большой для копии" }
            authorizeDrive(snapshot)
        } catch (error: Exception) {
            Toast.makeText(this, "Не удалось подготовить копию: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun authorizeDrive(snapshot: ByteArray?) {
        if (driveBusy) return
        driveBusy = true
        pendingDriveSnapshot = snapshot
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(driveScope))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        driveFailed("Не удалось открыть вход Google")
                    } else {
                        try {
                            driveAuthorization.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                        } catch (error: Exception) {
                            driveFailed("Не удалось открыть вход Google: ${error.message}")
                        }
                    }
                } else {
                    finishDriveAuthorization(result)
                }
            }
            .addOnFailureListener { driveFailed("Не удалось подключить Google Диск: ${it.message}") }
    }

    private fun finishDriveAuthorization(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank() || driveScope.scopeUri !in result.grantedScopes) {
            driveFailed("Доступ к Google Диску не предоставлен")
            return
        }
        driveConnected = true
        getSharedPreferences("drive_backup", MODE_PRIVATE).edit().putBoolean("connected", true).apply()
        val snapshot = pendingDriveSnapshot
        pendingDriveSnapshot = null
        if (snapshot == null) {
            driveBusy = false
            Toast.makeText(this, "Google Диск подключён", Toast.LENGTH_SHORT).show()
            return
        }
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
        val name = "svalka-$date.json"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { DriveBackup.upload(token, name, snapshot) }
                lastDriveBackupAt = System.currentTimeMillis()
                getSharedPreferences("drive_backup", MODE_PRIVATE).edit()
                    .putLong("last_backup", lastDriveBackupAt).apply()
                Toast.makeText(this@MainActivity, "Копия $name сохранена на Google Диске", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(this@MainActivity, "Не удалось сохранить на Диске: ${error.message}", Toast.LENGTH_LONG).show()
            } finally {
                driveBusy = false
            }
        }
    }

    private fun disconnectDrive() {
        if (driveBusy) return
        driveBusy = true
        val request = RevokeAccessRequest.builder().setScopes(listOf(driveScope)).build()
        Identity.getAuthorizationClient(this).revokeAccess(request)
            .addOnSuccessListener {
                driveConnected = false
                driveBusy = false
                lastDriveBackupAt = 0L
                getSharedPreferences("drive_backup", MODE_PRIVATE).edit()
                    .putBoolean("connected", false).remove("last_backup").apply()
                Toast.makeText(this, "Google Диск отключён", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                driveBusy = false
                Toast.makeText(this, "Не удалось отключить Google Диск: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun driveFailed(message: String) {
        pendingDriveSnapshot = null
        driveBusy = false
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
