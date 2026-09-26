package com.svalka.reminder

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriveBackupTest {
    @Test fun uploadPayloadKeepsBackupAndVisibleFilename() {
        val snapshot = TaskBackupCodec.encode(emptyList()).toByteArray(Charsets.UTF_8)
        val body = DriveBackup.multipartBody("test-boundary", "svalka-2026-09-26.json", snapshot)
            .toString(Charsets.UTF_8)

        assertTrue(body.contains("\"name\":\"svalka-2026-09-26.json\""))
        assertTrue(body.contains("Content-Type: application/json"))
        assertTrue(body.contains(snapshot.toString(Charsets.UTF_8)))
        assertTrue(body.endsWith("\r\n--test-boundary--\r\n"))
        assertEquals(0, TaskBackupCodec.decode(snapshot.toString(Charsets.UTF_8)).tasks.size)
    }
}
