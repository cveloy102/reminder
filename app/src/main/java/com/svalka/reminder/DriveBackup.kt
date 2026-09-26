package com.svalka.reminder

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** One visible JSON file is created for each manual backup. */
object DriveBackup {
    private const val UPLOAD_URL =
        "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name"

    fun upload(accessToken: String, name: String, snapshot: ByteArray): String {
        require(snapshot.size <= TaskBackupCodec.MAX_BYTES) { "Список слишком большой для копии" }
        val boundary = "svalka-${UUID.randomUUID()}"
        val body = multipartBody(boundary, name, snapshot)
        val connection = URL(UPLOAD_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val details = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = runCatching {
                    JSONObject(details).getJSONObject("error").getString("message")
                }.getOrNull()
                throw IOException("Google Диск: ${message ?: "ошибка $code"}")
            }
            val reply = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(reply).getString("id").takeIf { it.isNotBlank() }
                ?: throw IOException("Google Диск не подтвердил сохранение")
        } finally {
            connection.disconnect()
        }
    }

    internal fun multipartBody(boundary: String, name: String, snapshot: ByteArray): ByteArray {
        val metadata = JSONObject().put("name", name).put("mimeType", "application/json").toString()
        return ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n".toByteArray())
            write("--$boundary\r\nContent-Type: application/json\r\n\r\n".toByteArray())
            write(snapshot)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
    }
}
