package com.svalka.reminder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.svalka.reminder.ui.theme.Mono
import com.svalka.reminder.ui.theme.markerColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun ReminderPicker(task: ReminderTask, onDismiss: () -> Unit, onConfirm: (Long, RepeatRule) -> Unit) {
    val palette = markerColors()
    val initial = remember(task.id) {
        Calendar.getInstance().apply {
            timeInMillis = task.reminderAt ?: System.currentTimeMillis()
            if (task.reminderAt == null) add(Calendar.HOUR_OF_DAY, 1)
        }
    }
    var chosenDay by remember(task.id) { mutableLongStateOf(dayStart(initial.timeInMillis)) }
    var shownMonth by remember(task.id) { mutableLongStateOf(monthStart(initial.timeInMillis)) }
    var hour by remember(task.id) { mutableStateOf(initial.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')) }
    var minute by remember(task.id) { mutableStateOf(initial.get(Calendar.MINUTE).toString().padStart(2, '0')) }
    var error by remember(task.id) { mutableStateOf<String?>(null) }
    var repeat by remember(task.id) { mutableStateOf(task.repeat) }
    val month = Calendar.getInstance().apply { timeInMillis = shownMonth }
    val offset = (month.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val days = month.getActualMaximum(Calendar.DAY_OF_MONTH)
    val rows = (offset + days + 6) / 7
    val chosen = Calendar.getInstance().apply { timeInMillis = chosenDay }
    val monthLabel = SimpleDateFormat("LLLL yyyy", Locale.forLanguageTag("ru-RU"))
        .format(Date(shownMonth)).uppercase(Locale.forLanguageTag("ru-RU"))

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = palette.sheet,
            modifier = Modifier.testTag("reminderPicker")) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("НАПОМИНАНИЕ", color = palette.reminderText, fontSize = 11.sp, fontFamily = Mono,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(8.dp))
                Text("Когда напомнить?", color = palette.foreground,
                    fontSize = 27.sp, lineHeight = 31.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(monthLabel, modifier = Modifier.weight(1f), color = palette.foreground,
                        fontSize = 16.sp, fontWeight = FontWeight.Black)
                    MonthArrow("‹", "Предыдущий месяц", Modifier.testTag("previousMonth")) {
                        shownMonth = changeMonth(shownMonth, -1)
                    }
                    Spacer(Modifier.width(8.dp))
                    MonthArrow("›", "Следующий месяц", Modifier.testTag("nextMonth")) {
                        shownMonth = changeMonth(shownMonth, 1)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    listOf("ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС").forEach { day ->
                        Box(Modifier.weight(1f).height(29.dp), contentAlignment = Alignment.Center) {
                            Text(day, color = palette.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                repeat(rows) { row ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { column ->
                            val dayNumber = row * 7 + column - offset + 1
                            val valid = dayNumber in 1..days
                            val selected = valid && chosen.get(Calendar.YEAR) == month.get(Calendar.YEAR) &&
                                chosen.get(Calendar.MONTH) == month.get(Calendar.MONTH) &&
                                chosen.get(Calendar.DAY_OF_MONTH) == dayNumber
                            Box(
                                Modifier.weight(1f).height(40.dp).padding(2.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (selected) palette.accent else palette.sheet)
                                    .then(if (valid) Modifier.clickable {
                                        chosenDay = Calendar.getInstance().apply {
                                            set(month.get(Calendar.YEAR), month.get(Calendar.MONTH), dayNumber, 0, 0, 0)
                                            set(Calendar.MILLISECOND, 0)
                                        }.timeInMillis
                                        error = null
                                    }.testTag("reminderDay:$dayNumber") else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                if (valid) Text(dayNumber.toString(),
                                    color = if (selected) palette.accentText else palette.foreground,
                                    fontSize = 14.sp, fontWeight = if (selected) FontWeight.Black else FontWeight.Medium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("ВРЕМЯ · 24 ЧАСА", color = palette.secondary, fontFamily = Mono,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TimeField(hour, { hour = it; error = null }, "reminderHour", Modifier.weight(1f))
                    Text(":", color = palette.foreground, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    TimeField(minute, { minute = it; error = null }, "reminderMinute", Modifier.weight(1f))
                }
                Spacer(Modifier.height(18.dp))
                Text("ПОВТОР", color = palette.secondary, fontFamily = Mono,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).border(1.dp, palette.divider, RoundedCornerShape(9.dp))) {
                    listOf(RepeatRule.NONE to "НЕТ", RepeatRule.DAILY to "ДЕНЬ",
                        RepeatRule.WEEKLY to "НЕДЕЛЯ", RepeatRule.MONTHLY to "МЕСЯЦ").forEach { (rule, label) ->
                        Box(
                            Modifier.weight(1f).height(44.dp)
                                .background(if (repeat == rule) palette.accent else palette.sheet)
                                .clickable { repeat = rule }
                                .testTag("repeat:${rule.storageValue}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1,
                                color = if (repeat == rule) palette.accentText else palette.foreground)
                        }
                    }
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = palette.reminderText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = {
                        val h = hour.toIntOrNull()
                        val m = minute.toIntOrNull()
                        if (h == null || h !in 0..23 || m == null || m !in 0..59) {
                            error = "Укажи время от 00:00 до 23:59"
                        } else {
                            val at = Calendar.getInstance().apply {
                                timeInMillis = chosenDay
                                set(Calendar.HOUR_OF_DAY, h)
                                set(Calendar.MINUTE, m)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            if (at <= System.currentTimeMillis()) error = "Выбери дату и время в будущем"
                            else onConfirm(at, repeat)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp).testTag("saveReminder"),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                    shape = RoundedCornerShape(9.dp)
                ) { Text("ПОСТАВИТЬ НАПОМИНАНИЕ", fontSize = 12.sp, fontWeight = FontWeight.Black) }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("ОТМЕНА", color = palette.secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MonthArrow(symbol: String, description: String, modifier: Modifier, onClick: () -> Unit) {
    val palette = markerColors()
    Box(modifier.size(37.dp).clip(RoundedCornerShape(7.dp))
        .background(palette.background).border(1.dp, palette.divider, RoundedCornerShape(7.dp))
        .clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        Text(symbol, color = palette.foreground, fontSize = 26.sp, lineHeight = 26.sp)
    }
}

@Composable
private fun TimeField(value: String, onChange: (String) -> Unit, tag: String, modifier: Modifier) {
    val palette = markerColors()
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(2)) },
        modifier = modifier.height(58.dp).clip(RoundedCornerShape(9.dp))
            .background(palette.background).border(1.dp, palette.divider, RoundedCornerShape(9.dp))
            .testTag(tag),
        textStyle = TextStyle(fontFamily = Mono, color = palette.foreground, fontSize = 25.sp,
            fontWeight = FontWeight.Black, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        cursorBrush = SolidColor(palette.accent),
        singleLine = true,
        decorationBox = { inner -> Box(Modifier.fillMaxWidth().height(58.dp), contentAlignment = Alignment.Center) { inner() } }
    )
}

private fun dayStart(timestamp: Long): Long = Calendar.getInstance().apply {
    timeInMillis = timestamp
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun monthStart(timestamp: Long): Long = Calendar.getInstance().apply {
    timeInMillis = dayStart(timestamp)
    set(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

private fun changeMonth(timestamp: Long, amount: Int): Long = Calendar.getInstance().apply {
    timeInMillis = timestamp
    add(Calendar.MONTH, amount)
}.timeInMillis
