package com.svalka.reminder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.svalka.reminder.sync.SyncUi
import com.svalka.reminder.ui.theme.Inter
import com.svalka.reminder.ui.theme.Mono
import com.svalka.reminder.ui.theme.markerColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sign in to your own СВАЛКА sync server, or see the sync status and sign out. */
@Composable
fun SyncDialog(
    state: SyncUi,
    onDismiss: () -> Unit,
    onSignIn: (url: String, email: String, password: String, register: Boolean, done: (String?) -> Unit) -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit
) {
    val palette = markerColors()
    var server by remember { mutableStateOf(state.server) }
    var email by remember { mutableStateOf(state.email) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    @Composable
    fun Label(text: String, color: androidx.compose.ui.graphics.Color = palette.secondary) =
        Text(text, color = color, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

    @Composable
    fun Field(value: String, onChange: (String) -> Unit, tag: String, password: Boolean = false) = BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
            .background(palette.background).border(1.dp, palette.divider, RoundedCornerShape(10.dp))
            .padding(14.dp).testTag(tag),
        textStyle = TextStyle(fontFamily = Inter, color = palette.foreground, fontSize = 16.sp),
        cursorBrush = SolidColor(palette.accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when {
            password -> KeyboardType.Password
            tag == "syncEmail" -> KeyboardType.Email
            else -> KeyboardType.Uri
        })
    )

    fun go(register: Boolean) {
        val url = com.svalka.reminder.sync.SyncApi.normalizeUrl(server)
        error = when {
            url == null -> "Укажи адрес сервера, например sync.example.com"
            '@' !in email -> "Укажи почту"
            password.length < 8 -> "Пароль — не короче 8 символов"
            else -> null
        }
        if (error != null || url == null) return
        busy = true
        onSignIn(url, email.trim(), password, register) { problem ->
            busy = false
            if (problem == null) onDismiss() else error = problem
        }
    }

    // Typed text must not be lost by an accidental tap next to the window: it closes only with НАЗАД or Back.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = palette.sheet, modifier = Modifier.testTag("syncDialog")) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
                Label(if (state.signedIn) "[ СИНХРОНИЗАЦИЯ ]" else "[ СВОЙ СЕРВЕР ]", palette.reminderText)
                Spacer(Modifier.height(8.dp))
                Text("Синхронизация", color = palette.foreground, fontSize = 26.sp, fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp)
                Spacer(Modifier.height(10.dp))
                if (!state.signedIn) {
                    Text("Записи будут одинаковыми на телефоне и компьютере. Нужен сервер СВАЛКИ — свой или того, кто дал тебе адрес.",
                        color = palette.secondary, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(18.dp)); Label("АДРЕС СЕРВЕРА"); Spacer(Modifier.height(6.dp))
                    Field(server, { server = it }, "syncServer")
                    Spacer(Modifier.height(14.dp)); Label("ПОЧТА"); Spacer(Modifier.height(6.dp))
                    Field(email, { email = it }, "syncEmail")
                    Spacer(Modifier.height(14.dp)); Label("ПАРОЛЬ"); Spacer(Modifier.height(6.dp))
                    Field(password, { password = it }, "syncPassword", password = true)
                    error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = palette.reminderText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = { go(false) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("syncLogin"),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        shape = RoundedCornerShape(8.dp)) {
                        Text(if (busy) "ПОДКЛЮЧАЮСЬ…" else "ВОЙТИ", fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { go(true) }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), border = BorderStroke(1.dp, palette.divider),
                        shape = RoundedCornerShape(8.dp)) {
                        Text("СОЗДАТЬ АККАУНТ", color = palette.foreground, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Записи с телефона объединятся с записями аккаунта, ничего не удалится.",
                        color = palette.secondary, fontSize = 12.sp, lineHeight = 17.sp)
                } else {
                    val status = when {
                        state.syncing -> "Синхронизирую…"
                        state.error != null -> "Ошибка: ${state.error}"
                        state.lastSyncAt > 0 -> "Всё синхронизировано · " +
                            SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru-RU")).format(Date(state.lastSyncAt))
                        else -> "Подключено"
                    }
                    Text(status, color = if (state.error != null) palette.reminderText else palette.foreground,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Аккаунт: ${state.email}\nСервер: ${state.server}\n" +
                        if (state.live) "Изменения приходят сразу." else "Постоянной связи нет — синхронизирую при открытии.",
                        color = palette.secondary, fontSize = 13.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = onSyncNow, enabled = !state.syncing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("syncNow"),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        shape = RoundedCornerShape(8.dp)) {
                        Text("СИНХРОНИЗИРОВАТЬ СЕЙЧАС", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (confirmSignOut) {
                        Text("Синхронизация выключится. Записи останутся на телефоне и на сервере.",
                            color = palette.secondary, fontSize = 13.sp, lineHeight = 19.sp)
                        TextButton(onClick = { onSignOut(); onDismiss() }, modifier = Modifier.align(Alignment.End)) {
                            Label("ДА, ВЫЙТИ", palette.reminderText)
                        }
                    } else TextButton(onClick = { confirmSignOut = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Label("ВЫЙТИ ИЗ АККАУНТА")
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Label("НАЗАД") }
                Box(Modifier.height(2.dp))
            }
        }
    }
}
