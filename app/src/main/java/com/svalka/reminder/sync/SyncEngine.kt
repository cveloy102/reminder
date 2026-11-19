package com.svalka.reminder.sync

import android.content.Context
import android.util.AtomicFile
import com.svalka.reminder.ReminderTask
import com.svalka.reminder.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Gives the sync engine the records and lets it replace them (always called on the main thread). */
interface SyncHost {
    fun snapshot(): List<ReminderTask>
    fun replace(tasks: List<ReminderTask>)
}

data class SyncUi(
    val signedIn: Boolean = false,
    val server: String = "",
    val email: String = "",
    val syncing: Boolean = false,
    val error: String? = null,
    val lastSyncAt: Long = 0L,
    val live: Boolean = false
)

/** Pure merge rules shared with the Windows app: the newest edit wins, a purge removes the record everywhere. */
object SyncMerge {
    fun applyRemote(local: List<ReminderTask>, remote: List<RemoteItem>, dirty: MutableSet<String>, purged: MutableSet<String>): List<ReminderTask> {
        val result = local.toMutableList()
        val added = mutableListOf<ReminderTask>()
        for (item in remote) {
            val id = item.task.id
            val index = result.indexOfFirst { it.id == id }
            if (item.purged) {
                if (index >= 0) result.removeAt(index)
                dirty.remove(id)
                purged.remove(id)
                continue
            }
            if (id in purged) continue
            if (index < 0) {
                val pending = added.indexOfFirst { it.id == id }
                if (pending < 0) added += item.task
                else if (item.task.editedAt >= added[pending].editedAt) added[pending] = item.task
                continue
            }
            when {
                item.task.editedAt > result[index].editedAt -> { result[index] = item.task; dirty.remove(id) }
                item.task.editedAt < result[index].editedAt -> dirty.add(id) // server is behind: upload ours again
            }
        }
        result.addAll(0, added.sortedByDescending { it.createdAt })
        return result
    }
}

/**
 * Keeps this phone and the sync server in step while the app is open: after each change, on realtime events
 * from other devices, and when the app comes to the front. Changes are found by comparing each record with the
 * version last exchanged with the server, so every place that edits records (including notification buttons) is covered.
 */
class SyncEngine(context: Context, private val host: SyncHost, private val scope: CoroutineScope, private val onUi: (SyncUi) -> Unit) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val stateFile = AtomicFile(File(context.filesDir, "sync_state.json"))
    private val mutex = Mutex()
    private var api: SyncApi? = null
    private var pending: Job? = null
    private var listener: Job? = null
    private var ticker: Job? = null
    private var again = false

    private var cursor = ""
    private var lastSyncAt = 0L
    private val known = HashMap<String, String>()
    private val recordIds = HashMap<String, String>()
    private val dirty = HashSet<String>()
    private val purged = HashSet<String>()
    private var ui = SyncUi()

    init {
        loadState()
        val server = prefs.getString("server", null)
        val token = prefs.getString("token", null)
        ui = SyncUi(signedIn = server != null && token != null, server = server.orEmpty(),
            email = prefs.getString("email", "").orEmpty(), lastSyncAt = lastSyncAt)
        onUi(ui)
        if (server != null && token != null) api = runCatching { SyncApi(server, token) }.getOrNull()
    }

    val signedIn get() = api != null && ui.signedIn

    private fun publish(change: SyncUi.() -> SyncUi) {
        ui = ui.change()
        onUi(ui)
    }

    fun signIn(url: String, email: String, password: String, register: Boolean, done: (String?) -> Unit) {
        scope.launch {
            try {
                val client = withContext(Dispatchers.IO) {
                    val c = SyncApi(url)
                    c.info()
                    if (register) c.register(email, password)
                    c
                }
                val (userId, mail) = withContext(Dispatchers.IO) { client.login(email, password) }
                val sameAccount = prefs.getString("userId", null) == userId && prefs.getString("server", null) == client.baseUrl
                if (!sameAccount) resetAccountData()
                prefs.edit().putString("server", client.baseUrl).putString("email", mail)
                    .putString("userId", userId).putString("token", client.token).apply()
                api = client
                publish { copy(signedIn = true, server = client.baseUrl, email = mail, error = null) }
                startLive()
                syncNow()
                done(null)
            } catch (e: Exception) {
                done(e.message ?: "Не удалось подключиться")
            }
        }
    }

    fun signOut() {
        stopLive()
        api = null
        resetAccountData()
        prefs.edit().remove("token").remove("userId").apply()
        publish { copy(signedIn = false, syncing = false, error = null, lastSyncAt = 0L, live = false) }
    }

    /** Call from onStart: refresh the login, open the realtime stream and sync. */
    fun onForeground() {
        val client = api ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { client.refresh() }
                prefs.edit().putString("token", client.token).apply()
            }
            startLive()
            startTicker()
            syncNow()
        }
    }

    fun onBackground() {
        stopLive()
        ticker?.cancel()
        ticker = null
    }

    /** A light check every minute while the app is open, in case a realtime event was lost. */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(60_000)
                if (signedIn) syncNow()
            }
        }
    }

    /**
     * Call after any local change; several quick changes become one sync. Only the waiting is restarted:
     * a sync already under way is never cancelled (records it writes would otherwise cancel its own upload).
     */
    fun requestSync(delayMs: Long = 1500) {
        if (!signedIn) return
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            scope.launch { syncNow() }
        }
    }

    suspend fun syncNow() {
        val client = api ?: return
        if (!mutex.tryLock()) { again = true; return }
        try {
            do {
                again = false
                publish { copy(syncing = true) }
                runOnce(client)
                publish { copy(syncing = false, error = null, lastSyncAt = this@SyncEngine.lastSyncAt) }
            } while (again)
        } catch (e: kotlinx.coroutines.CancellationException) {
            publish { copy(syncing = false) }
            throw e
        } catch (e: SyncException) {
            publish { copy(syncing = false, error = if (e.status == 401) "Нужно войти снова" else e.message) }
        } catch (e: Exception) {
            publish { copy(syncing = false, error = e.message ?: "Ошибка синхронизации") }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runOnce(client: SyncApi) {
        val owner = prefs.getString("userId", null) ?: throw SyncException("Нужно войти снова", 401)

        // 0. Restored from a backup since the last visit? The server may miss edits newer than that backup,
        //    so compare everything once: forget what was exchanged and pull all records again.
        val instance = withContext(Dispatchers.IO) { client.instance() }
        val seen = prefs.getString("instance", null)
        if (instance.isNotEmpty() && instance != seen) {
            if (seen != null) {
                cursor = ""
                known.clear()
            }
            prefs.edit().putString("instance", instance).apply()
        }

        // 1. Find local edits by comparing with what was last exchanged with the server.
        var local = host.snapshot()
        val now = System.currentTimeMillis()
        val bump = HashSet<String>()
        for (t in local) {
            val json = t.toJson().toString()
            val previous = known[t.id]
            if (previous == json) continue
            dirty += t.id
            if (previous != null && JSONObject(previous).optLong("updatedAt") >= t.updatedAt) bump += t.id
        }
        val localIds = local.mapTo(HashSet()) { it.id }
        for (id in known.keys.toList()) if (id !in localIds) { purged += id; dirty -= id; known.remove(id) }
        if (bump.isNotEmpty()) {
            local = local.map { if (it.id in bump) it.copy(updatedAt = now) else it }
            host.replace(local)
        }

        // 2. Pull and merge what changed on other devices.
        val pulled = withContext(Dispatchers.IO) { client.pull(cursor) }
        if (pulled.isNotEmpty()) {
            val merged = SyncMerge.applyRemote(host.snapshot(), pulled, dirty, purged)
            host.replace(merged)
            val byId = merged.associateBy { it.id }
            for (r in pulled) {
                if (r.purged) { recordIds.remove(r.task.id); known.remove(r.task.id); continue }
                recordIds[r.task.id] = r.recordId
                byId[r.task.id]?.takeIf { it == r.task }?.let { known[it.id] = it.toJson().toString() }
            }
            pulled.maxOf { it.updated }.takeIf { it > cursor }?.let { cursor = it }
        }

        // 3. Push local edits and permanent deletes.
        val current = host.snapshot().associateBy { it.id }
        for (id in dirty.toList()) {
            val task = current[id] ?: run { dirty.remove(id); null } ?: continue
            if (upload(client, task, owner, purge = false)) known[id] = task.toJson().toString()
            dirty.remove(id)
        }
        for (id in purged.toList()) {
            upload(client, ReminderTask(id, "", 0L, updatedAt = System.currentTimeMillis()), owner, purge = true)
            purged.remove(id)
            recordIds.remove(id)
        }
        lastSyncAt = System.currentTimeMillis()
        saveState()
    }

    /** Returns false when the server kept a newer version (which is then brought here). */
    private suspend fun upload(client: SyncApi, task: ReminderTask, owner: String, purge: Boolean): Boolean {
        val body = SyncApi.toJson(task, owner, purge)
        val saved: RemoteItem = withContext(Dispatchers.IO) {
            try {
                recordIds[task.id]?.let { id ->
                    try { return@withContext client.update(id, body) } catch (e: SyncException) { if (e.status != 404) throw e }
                }
                try { client.create(body) } catch (e: SyncException) {
                    if (e.status != 400) throw e
                    val existing = client.find(task.id) ?: throw e
                    client.update(existing.recordId, body)
                }
            } catch (e: SyncException) {
                if (e.status != 409) throw e
                client.find(task.id) ?: throw e
            }
        }
        recordIds[task.id] = saved.recordId
        if (saved.task.editedAt > task.editedAt && !purge) {
            val merged = SyncMerge.applyRemote(host.snapshot(), listOf(saved), dirty, purged)
            host.replace(merged)
            known[task.id] = saved.task.toJson().toString()
            return false
        }
        return true
    }

    private fun startLive() {
        if (listener?.isActive == true) return
        val client = api ?: return
        listener = scope.launch(Dispatchers.IO) {
            var pause = 2_000L
            while (isActive) {
                try {
                    client.listen { event, data ->
                        if (event == "PB_CONNECT") {
                            val clientId = runCatching { JSONObject(data).getString("clientId") }.getOrNull() ?: return@listen
                            client.subscribe(clientId)
                            pause = 2_000L
                            scope.launch { publish { copy(live = true) }; requestSync(0) }
                        } else if (event.startsWith("items")) {
                            scope.launch { requestSync(400) }
                        }
                    }
                } catch (_: Exception) { /* reconnect below */ }
                scope.launch { publish { copy(live = false) } }
                delay(pause)
                pause = (pause * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun stopLive() {
        listener?.cancel()
        listener = null
        publish { copy(live = false) }
    }

    private fun resetAccountData() {
        prefs.edit().remove("instance").apply()
        cursor = ""
        lastSyncAt = 0L
        known.clear()
        recordIds.clear()
        dirty.clear()
        purged.clear()
        saveState()
    }

    private fun loadState() {
        runCatching {
            val o = JSONObject(stateFile.openRead().bufferedReader().use { it.readText() })
            cursor = o.optString("cursor")
            lastSyncAt = o.optLong("lastSyncAt")
            o.optJSONObject("known")?.let { k -> k.keys().forEach { known[it] = k.getString(it) } }
            o.optJSONObject("recordIds")?.let { r -> r.keys().forEach { recordIds[it] = r.getString(it) } }
            o.optJSONArray("dirty")?.let { a -> for (i in 0 until a.length()) dirty += a.getString(i) }
            o.optJSONArray("purged")?.let { a -> for (i in 0 until a.length()) purged += a.getString(i) }
        }
    }

    private fun saveState() {
        val o = JSONObject().apply {
            put("cursor", cursor)
            put("lastSyncAt", lastSyncAt)
            put("known", JSONObject(known as Map<*, *>))
            put("recordIds", JSONObject(recordIds as Map<*, *>))
            put("dirty", JSONArray(dirty.toList()))
            put("purged", JSONArray(purged.toList()))
        }
        val stream = stateFile.startWrite()
        try {
            stream.write(o.toString().toByteArray(Charsets.UTF_8))
            stateFile.finishWrite(stream)
        } catch (e: Exception) {
            stateFile.failWrite(stream)
        }
    }
}
