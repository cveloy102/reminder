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
import androidx.compose.runtime.snapshotFlow
import com.svalka.reminder.sync.SyncEngine
import com.svalka.reminder.sync.SyncHost
import com.svalka.reminder.sync.SyncUi
import kotlinx.coroutines.flow.drop
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
    private var sectionRequest by mutableStateOf<TaskList?>(null)
    private var cloudSavesInFlight = 0
    private lateinit var sync: SyncEngine
    private var syncUi by mutableStateOf(SyncUi())

    private val chooseCloudFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        if (uri.authority?.startsWith("com.google.android.apps.docs.storage") != true) {
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
            writeDocument(uri, data)
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
        tasks = loadedTasks.map { it.normalized() }
        if (tasks != loadedTasks) store.save(tasks)
        reportRecoveredFiles()
        purgeTrash()
        themeMode = ThemeMode.fromValue(getSharedPreferences("settings", MODE_PRIVATE).getString("theme", null))
        val cloudSettings = getSharedPreferences("cloud_backup", MODE_PRIVATE)
        cloudFile = cloudSettings.getString("uri", null)?.let(Uri::parse)
            ?.takeIf { saved -> contentResolver.persistedUriPermissions.any { it.uri == saved && it.isWritePermission } }
        lastCloudSaveAt = cloudSettings.getLong("last_save", 0L)
        reminders.createChannel()
        sync = SyncEngine(this, object : SyncHost {
            override fun snapshot(): List<ReminderTask> = tasks
            override fun replace(tasks: List<ReminderTask>) = applyFromSync(tasks)
        }, lifecycleScope) { syncUi = it }
        // Any change of the records (edits here, notification buttons, other screens) triggers a sync.
        lifecycleScope.launch { snapshotFlow { tasks }.drop(1).collect { sync.requestSync() } }
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
                    sectionRequest = sectionRequest,
                    onSectionRequestHandled = { sectionRequest = null },
                    onAdd = ::addTask,
                    onAddLink = ::addLink,
                    onUpdate = ::save,
                    onToggle = ::toggleDone,
                    onClearCompleted = ::clearCompleted,
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
                    onConfirmImport = ::restoreBackup,
                    syncState = syncUi,
                    onSyncSignIn = { url, email, password, register, done -> sync.signIn(url, email, password, register, done) },
                    onSyncNow = { lifecycleScope.launch { sync.syncNow() } },
                    onSyncSignOut = { sync.signOut() }
                )
            }
        }
        requestNotificationPermission()
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        TaskEvents.onExternalChange = ::reloadFromStore
        sync.onForeground()
    }

    override fun onStop() {
        TaskEvents.onExternalChange = null
        sync.onBackground()
        super.onStop()
    }

    private fun reloadFromStore() {
        tasks = store.load().map { it.normalized() }
    }

    private fun reportRecoveredFiles() {
        if (store.recoveredFiles.isNotEmpty()) {
            Toast.makeText(this,
                "Файл данных был повреждён и отложен в сторону: ${store.recoveredFiles.joinToString()}",
                Toast.LENGTH_LONG).show()
        }
    }

    /** Trash older than 30 days is removed for good. */
    private fun purgeTrash() {
        val kept = purgeExpiredTrash(tasks, System.currentTimeMillis())
        if (kept.size != tasks.size) {
            val removed = tasks.filter { it !in kept }
            removed.forEach { reminders.cancel(it.id) }
            tasks = kept
            store.save(tasks)
            if (::sync.isInitialized) sync.markPurged(removed.map { it.id })
        }
    }

    private fun handleShare(shared: Intent?) {
        if (shared?.action != Intent.ACTION_SEND || shared.type?.startsWith("text/") != true) return
        shared.action = null
        val text = shared.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return
        val subject = (shared.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: shared.getStringExtra(Intent.EXTRA_TITLE))?.trim().orEmpty()
        val url = findUrlInText(text)?.let(::normalizeLinkUrl)
        if (url != null) {
            sectionRequest = TaskList.LINKS
            if (tasks.any { it.list == TaskList.LINKS && it.deletedAt == null && it.url == url }) {
                Toast.makeText(this, "Эта ссылка уже сохранена", Toast.LENGTH_SHORT).show()
            } else {
                val title = subject.takeIf { it.isNotEmpty() && findUrlInText(it) == null } ?: ""
                addLink(url, title)
                Toast.makeText(this, "Ссылка сохранена", Toast.LENGTH_SHORT).show()
            }
        } else {
            sectionRequest = TaskList.TASKS
            addTask(text, TaskList.TASKS)
            Toast.makeText(this, "Добавлено в дела", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleDone(task: ReminderTask) {
        save(task.copy(completed = !task.completed, pinned = false, snoozeUntil = null))
    }

    private fun clearCompleted(list: TaskList) {
        val now = System.currentTimeMillis()
        val moved = tasks.filter { it.list == list && it.completed && it.deletedAt == null }
        if (moved.isEmpty()) return
        val ids = moved.mapTo(HashSet()) { it.id }
        tasks = tasks.map { if (it.id in ids) it.copy(deletedAt = now, updatedAt = now) else it }
        store.save(tasks)
        saveToCloud(manual = false)
        moved.forEach { reminders.cancel(it.id) }
        Toast.makeText(this, "В корзине: ${moved.size}", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post {
            window.decorView.display?.supportedModes?.maxOfOrNull { it.refreshRate }?.let { maximum ->
                window.attributes = window.attributes.apply { preferredRefreshRate = maximum }
            }
        }
        if (::reminders.isInitialized) {
            tasks = reminders.rescheduleAll(store.load().map { it.normalized() })
            purgeTrash()
        }
    }

    /** Records that came from the sync server replace the list; alarms follow the new state. */
    private fun applyFromSync(updated: List<ReminderTask>) {
        val ids = updated.mapTo(HashSet()) { it.id }
        tasks.filter { it.id !in ids }.forEach { reminders.cancel(it.id) }
        tasks = updated.map { it.normalized() }
        store.save(tasks)
        tasks = reminders.rescheduleAll(tasks)
        saveToCloud(manual = false)
    }

    /**
     * Writes a whole document through the storage framework. Some providers ignore "wt" or leave a stale
     * size, which produced empty copies; write through a file descriptor, sync it and check the result.
     */
    private fun writeDocument(uri: Uri, data: ByteArray) {
        val written = runCatching {
            contentResolver.openFileDescriptor(uri, "rwt")?.use { pfd ->
                java.io.FileOutputStream(pfd.fileDescriptor).use { out ->
                    out.channel.truncate(0)
                    out.write(data)
                    out.flush()
                    out.fd.sync()
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!written) {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(data); it.flush() }
                ?: throw IllegalStateException("Не удалось открыть файл")
        }
        val size = runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()
        if (size != null && size >= 0 && size < data.size) throw IllegalStateException("Файл записался не полностью")
    }

    private fun save(task: ReminderTask) {
        val normalized = task.normalized().copy(updatedAt = System.currentTimeMillis())
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

    private fun addTask(title: String, list: TaskList) {
        val clean = title.trim()
        if (clean.isNotEmpty()) save(ReminderTask(UUID.randomUUID().toString(), clean, System.currentTimeMillis(), list = list))
    }

    private fun reorderTasks(fromId: String, toId: String) {
        if (fromId == toId) return
        val source = tasks.firstOrNull { it.id == fromId } ?: return
        if (tasks.firstOrNull { it.id == toId }?.list != source.list) return
        val active = orderedActiveTasks(tasks.filter { it.list == source.list })
        val from = active.indexOfFirst { it.id == fromId }
        val to = active.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0 || active[from].completed != active[to].completed) return
        val reordered = active.toMutableList().apply {
            val moved = removeAt(from).let { t ->
                val pinned = !active[from].completed && active[to].pinned
                if (t.pinned == pinned) t else t.copy(pinned = pinned, updatedAt = System.currentTimeMillis())
            }
            val target = indexOfFirst { it.id == toId }
            add(target + if (from < to) 1 else 0, moved)
        }
        tasks = reordered + tasks.filter { it.list != source.list || it.deletedAt != null }
        store.save(tasks)
        saveToCloud(manual = false)
    }

    private fun startExport() {
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date())
        exportFile.launch("svalka-$date.json")
    }

    private fun addLink(url: String, title: String) {
        val normalizedUrl = normalizeLinkUrl(url) ?: return
        val name = title.trim().ifEmpty { linkDisplayName(normalizedUrl) }
        save(ReminderTask(
            id = UUID.randomUUID().toString(),
            title = name,
            createdAt = System.currentTimeMillis(),
            list = TaskList.LINKS,
            url = normalizedUrl
        ))
    }

    private fun saveToCloud(manual: Boolean) {
        val target = cloudFile ?: return
        pendingAutoSave?.cancel()
        val snapshot = tasks.toList()
        // Never let an automatic save replace a good backup with an empty list (e.g. after a data-file problem).
        if (!manual && snapshot.isEmpty()) return
        val job = lifecycleScope.launch {
            if (!manual) delay(3_000)
            cloudSavesInFlight++
            cloudSaving = true
            cloudWriteMutex.withLock {
                try {
                    val data = TaskBackupCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
                    require(data.size <= TaskBackupCodec.MAX_BYTES) { "Список слишком большой для копии" }
                    withContext(Dispatchers.IO) { writeDocument(target, data) }
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
                    cloudSavesInFlight--
                    cloudSaving = cloudSavesInFlight > 0
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
        // Restored records keep the backup's old edit times; sync would let the server's newer copies win and
        // quietly undo the restore. A restore is a new edit, so changed records are stamped with now.
        val now = System.currentTimeMillis()
        val before = tasks.associateBy { it.id }
        val result = (if (replace) imported else TaskBackupCodec.merge(tasks, imported))
            .map { it.normalized() }
            .map { if (before[it.id] == it) it else it.copy(updatedAt = now) }
        try {
            store.save(result)
            val kept = result.mapTo(HashSet()) { it.id }
            sync.markPurged(before.keys.filter { it !in kept })
            tasks.forEach { reminders.cancel(it.id) }
            tasks = result
            saveToCloud(manual = false)
            tasks = reminders.rescheduleAll(result)
            backupPreview = null
            Toast.makeText(this, "Дела, покупки и ссылки восстановлены", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Не удалось восстановить: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun deletePermanently(task: ReminderTask) {
        tasks = tasks.filterNot { it.id == task.id }
        store.save(tasks)
        sync.markPurged(listOf(task.id))
        saveToCloud(manual = false)
        reminders.cancel(task.id)
    }

    private fun setReminder(task: ReminderTask, at: Long, repeat: RepeatRule) {
        if (at <= System.currentTimeMillis()) return
        save(task.copy(reminderAt = at, repeat = repeat, snoozeUntil = null))
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
            tasks = reminders.rescheduleAll(tasks)
        }
        if (requestCode == 100 && requestExactAfterNotificationPermission &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            reminders.requestExactAlarmAccessIfNeeded(this)
        }
        requestExactAfterNotificationPermission = false
    }
}
