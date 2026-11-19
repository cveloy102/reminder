package com.svalka.reminder.sync

import com.svalka.reminder.RepeatRule
import com.svalka.reminder.ReminderTask
import com.svalka.reminder.TaskList
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SyncException(message: String, val status: Int = 0) : Exception(message)

data class RemoteItem(val recordId: String, val updated: String, val task: ReminderTask, val purged: Boolean)

/** REST client for the СВАЛКА sync server (PocketBase). Blocking: call from Dispatchers.IO. */
class SyncApi(baseUrl: String, var token: String? = null) {
    val baseUrl: String = normalizeUrl(baseUrl) ?: throw SyncException("Проверь адрес сервера")

    fun info(): Boolean {
        val o = try { request("GET", "/api/svalka/info", null, auth = false) } catch (e: SyncException) {
            if (e.status == 404) throw SyncException("По этому адресу нет сервера СВАЛКИ", 404) else throw e
        }
        if (o.optString("app") != "svalka-sync") throw SyncException("По этому адресу нет сервера СВАЛКИ")
        return o.optBoolean("signup")
    }

    /** Server id that changes when the server is restored from a backup ("" on older servers). */
    fun instance(): String = request("GET", "/api/svalka/info", null, auth = false).optString("instance")

    /** Returns user id and email. */
    fun login(email: String, password: String): Pair<String, String> {
        val o = try {
            request("POST", "/api/collections/users/auth-with-password",
                JSONObject().put("identity", email.trim()).put("password", password), auth = false)
        } catch (e: SyncException) {
            if (e.status == 400) throw SyncException("Неверная почта или пароль", 400) else throw e
        }
        return readAuth(o)
    }

    fun register(email: String, password: String) {
        request("POST", "/api/collections/users/records",
            JSONObject().put("email", email.trim()).put("password", password).put("passwordConfirm", password), auth = false)
    }

    fun refresh(): Pair<String, String> = readAuth(request("POST", "/api/collections/users/auth-refresh", null))

    private fun readAuth(o: JSONObject): Pair<String, String> {
        token = o.getString("token")
        val record = o.getJSONObject("record")
        return record.getString("id") to record.optString("email")
    }

    /**
     * Rows changed at or after [cursor], oldest first. Pages are chained by the last row seen (its "updated" and
     * id), not by page number: a row edited elsewhere during the pull moves to the end and would shift numbered
     * pages, so one row would be skipped.
     */
    fun pull(cursor: String): List<RemoteItem> {
        val result = mutableListOf<RemoteItem>()
        var filter = if (cursor.isEmpty()) "" else "updated >= \"${quote(cursor)}\""
        while (true) {
            val query = if (filter.isEmpty()) "" else "&filter=" + enc(filter)
            val items = request("GET", "/api/collections/items/records?perPage=$PAGE&sort=updated,id&skipTotal=1$query", null)
                .getJSONArray("items")
            for (i in 0 until items.length()) result += fromJson(items.getJSONObject(i))
            if (items.length() < PAGE) break
            val last = result.last()
            val at = quote(last.updated)
            filter = "updated > \"$at\" || (updated = \"$at\" && id > \"${quote(last.recordId)}\")"
        }
        return result
    }

    fun create(body: JSONObject): RemoteItem = fromJson(request("POST", "/api/collections/items/records", body))
    fun update(recordId: String, body: JSONObject): RemoteItem =
        fromJson(request("PATCH", "/api/collections/items/records/$recordId", body))

    fun find(itemId: String): RemoteItem? {
        val filter = enc("itemId = \"${itemId.replace("\"", "")}\"")
        val items = request("GET", "/api/collections/items/records?perPage=1&skipTotal=1&filter=$filter", null).getJSONArray("items")
        return if (items.length() == 0) null else fromJson(items.getJSONObject(0))
    }

    fun subscribe(clientId: String) {
        request("POST", "/api/realtime", JSONObject().put("clientId", clientId).put("subscriptions", JSONArray().put("items/*")))
    }

    private fun request(method: String, path: String, body: JSONObject?, auth: Boolean = true): JSONObject {
        val payload = body?.toString()?.toRequestBody(JSON)
        val builder = Request.Builder().url(baseUrl + path).header("Accept", "application/json")
            .method(method, payload ?: if (method == "POST" || method == "PATCH") ByteArray(0).toRequestBody(JSON) else null)
        if (auth) token?.let { builder.header("Authorization", it) }
        try {
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { if (text.isBlank()) JSONObject() else JSONObject(text) }.getOrNull()
                if (!response.isSuccessful) {
                    val details = json?.optJSONObject("data")?.let { data ->
                        data.keys().asSequence().joinToString("; ") { "$it: ${data.optJSONObject(it)?.optString("message").orEmpty()}" }
                    }?.takeIf { it.isNotBlank() }
                    val message = if (response.code == 401) "Нужно войти снова"
                        else json?.optString("message")?.takeIf { it.isNotBlank() }?.let { if (details != null) "$it ($details)" else it }
                    throw SyncException(message ?: "Ошибка сервера ${response.code}", response.code)
                }
                return json ?: JSONObject()
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw SyncException("Сервер не отвечает")
        } catch (e: IOException) {
            throw SyncException("Нет связи с сервером")
        }
    }

    /** Opens the realtime event stream; blocks while it is open, calling [onEvent] with (event, data). [isOpen] false stops it. */
    fun listen(isOpen: () -> Boolean, onEvent: (String, String) -> Unit) {
        val request = Request.Builder().url("$baseUrl/api/realtime").header("Accept", "text/event-stream").build()
        val call = streamHttp.newCall(request)
        stream = call
        if (!isOpen()) call.cancel() // stopped between the caller's check and here
        call.execute().use { response ->
            if (!response.isSuccessful) throw SyncException("Ошибка сервера ${response.code}", response.code)
            val source = response.body?.source() ?: return
            var event: String? = null
            val data = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: return
                when {
                    line.isEmpty() -> {
                        event?.let { onEvent(it, data.toString()) }
                        event = null
                        data.setLength(0)
                    }
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> data.append(line.substring(5).trim())
                }
            }
        }
    }

    @Volatile private var stream: Call? = null

    /** Closes the realtime stream opened by [listen], which then returns or throws at once. */
    fun closeStream() {
        stream?.cancel()
        stream = null
    }

    companion object {
        private const val PAGE = 200
        private fun quote(value: String) = value.replace("\\", "").replace("\"", "")

        fun normalizeUrl(value: String?): String? {
            var clean = value.orEmpty().trim().trimEnd('/')
            if (clean.isEmpty() || clean.any { it.isWhitespace() }) return null
            if ("://" !in clean) clean = "https://$clean"
            val url = runCatching { URL(clean) }.getOrNull() ?: return null
            if (url.protocol != "https" && url.protocol != "http" || url.host.isNullOrBlank()) return null
            return clean
        }

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        // The server closes an idle stream after 5 minutes, so 6 minutes of silence means the connection died
        // on the way without anyone being told; the read then fails and the stream is reopened.
        private val streamHttp = http.newBuilder().readTimeout(6, TimeUnit.MINUTES).build()

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun fromJson(o: JSONObject): RemoteItem {
            fun ms(key: String): Long? = o.optDouble(key, 0.0).toLong().takeIf { it > 0 }
            fun str(key: String): String? = o.optString(key, "").takeIf { it.isNotEmpty() && !o.isNull(key) }
            val task = ReminderTask(
                id = str("itemId") ?: throw SyncException("Сервер вернул запись без ID"),
                title = o.optString("title"),
                createdAt = ms("createdAt") ?: 0L,
                completed = o.optBoolean("completed"),
                pinned = o.optBoolean("pinned"),
                reminderAt = ms("reminderAt"),
                deletedAt = ms("deletedAt"),
                list = TaskList.fromStorage(o.optString("list", "tasks")),
                url = str("url"),
                repeat = RepeatRule.fromStorage(o.optString("repeat", "none")),
                snoozeUntil = ms("snoozeUntil"),
                updatedAt = ms("updatedAt") ?: 0L
            )
            return RemoteItem(o.getString("id"), o.optString("updated"), task, o.optBoolean("purged"))
        }

        fun toJson(t: ReminderTask, owner: String, purged: Boolean = false): JSONObject = JSONObject().apply {
            put("owner", owner)
            put("itemId", t.id)
            put("title", t.title)
            put("list", t.list.storageValue)
            put("createdAt", t.createdAt)
            put("updatedAt", t.editedAt)
            put("completed", t.completed)
            put("pinned", t.pinned)
            put("reminderAt", t.reminderAt ?: 0L)
            put("deletedAt", t.deletedAt ?: 0L)
            put("snoozeUntil", t.snoozeUntil ?: 0L)
            put("url", t.url ?: "")
            put("repeat", t.repeat.storageValue)
            put("purged", purged)
        }
    }
}
