package com.svalka.reminder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.testTag
import com.svalka.reminder.ui.theme.ThemeMode
import com.svalka.reminder.ui.theme.markerColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SvalkaScreen(
    tasks: List<ReminderTask>,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onAdd: (String) -> Unit,
    onUpdate: (ReminderTask) -> Unit,
    onDelete: (ReminderTask) -> Unit,
    onPickReminder: (ReminderTask) -> Unit,
    backupPreview: TaskBackup?,
    onExport: () -> Unit,
    onChooseImport: () -> Unit,
    onDismissImport: () -> Unit,
    onConfirmImport: (Boolean) -> Unit
) {
    val palette = markerColors()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { FocusRequester() }
    var input by rememberSaveable { mutableStateOf("") }
    var inputFocused by remember { mutableStateOf(false) }
    var draftDialog by remember { mutableStateOf(false) }
    var trash by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ReminderTask?>(null) }
    var themeDialog by remember { mutableStateOf(false) }
    var backupDialog by remember { mutableStateOf(false) }
    var replaceConfirmation by remember { mutableStateOf(false) }
    var permanentDelete by remember { mutableStateOf<ReminderTask?>(null) }
    val active = remember(tasks) {
        tasks.filter { it.deletedAt == null }
            .sortedWith(compareBy<ReminderTask> { it.completed }.thenByDescending { it.createdAt })
    }
    val deleted = remember(tasks) {
        tasks.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }
    }
    val visible = if (trash) deleted else active
    val listState = rememberLazyListState()
    var previousCount by remember { mutableIntStateOf(tasks.size) }
    var previousTrash by remember { mutableStateOf(trash) }

    LaunchedEffect(trash, tasks.size) {
        if (visible.isNotEmpty() && (trash != previousTrash || tasks.size > previousCount)) {
            listState.scrollToItem(0)
        }
        previousCount = tasks.size
        previousTrash = trash
    }

    BackHandler(enabled = trash) { trash = false }

    val backSwipe = Modifier.pointerInput(trash) {
        if (trash) {
            var distance = 0f
            detectHorizontalDragGestures(
                onHorizontalDrag = { _, amount -> distance += amount },
                onDragEnd = {
                    if (distance > 100.dp.toPx()) trash = false
                    distance = 0f
                },
                onDragCancel = { distance = 0f }
            )
        }
    }

    fun submit() {
        if (input.isNotBlank()) {
            onAdd(input)
            input = ""
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    fun dismissInputFromEmptySpace() {
        if (!inputFocused) return
        focusManager.clearFocus()
        keyboardController?.hide()
        if (input.isNotBlank()) draftDialog = true
    }
    val dismissOnBlank by rememberUpdatedState { dismissInputFromEmptySpace() }

    Surface(color = palette.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { dismissOnBlank() })
                }
                .padding(horizontal = 28.dp)
                .testTag("mainCanvas")
        ) {
            Spacer(Modifier.height(28.dp))
            Row(
                modifier = Modifier.fillMaxWidth().then(backSwipe).testTag("trashHeader"),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = if (trash) "КОРЗИНА." else "СВАЛКА",
                    modifier = Modifier.weight(1f), color = palette.foreground,
                    fontSize = if (trash) 38.sp else 42.sp, lineHeight = 39.sp,
                    fontWeight = FontWeight.Black, letterSpacing = (-2.5).sp
                )
                if (!trash) TextButton(
                    onClick = { backupDialog = true },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.testTag("backupMenu")
                ) {
                    Text("ДАННЫЕ", color = palette.foreground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { if (trash) trash = false else themeDialog = true }) {
                    Text(if (trash) "НАЗАД" else "ТЕМА", color = palette.foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(24.dp).fillMaxWidth().testTag("emptyTapArea"))
            Text(
                text = if (trash) "Удалённое можно вернуть."
                    else "${taskCountLabel(active.count { !it.completed })}. Остальное подождёт.",
                color = palette.secondary, fontSize = 13.sp
            )
            Spacer(Modifier.height(42.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (trash) "УДАЛЁННЫЕ" else "СПИСОК / 001",
                    modifier = Modifier.weight(1f), color = palette.secondary,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                )
                Text(
                    if (trash) "${deleted.size}" else "КОРЗИНА  ${deleted.size}",
                    modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = if (trash) "Назад к списку" else "Открыть корзину" }
                        .clickable { trash = !trash }
                        .padding(vertical = 14.dp),
                    color = palette.secondary, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }
            HorizontalDivider(color = palette.divider)
            if (visible.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .testTag("blankListArea"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (trash) "Здесь пока пусто" else "Здесь пока пусто.\nЗапиши первое дело ↓",
                        color = palette.secondary, textAlign = TextAlign.Center,
                        fontSize = 17.sp, lineHeight = 26.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).testTag("blankListArea")
                ) {
                    items(visible, key = { "${if (trash) "trash" else "active"}:${it.id}" }) { task ->
                        Column(Modifier.animateItem()) {
                            val dismissState = remember(task.id, trash) {
                                SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { it * 0.5f })
                            }
                            var swipeHandled by remember { mutableStateOf(false) }
                            SwipeToDismissBox(
                                state = dismissState,
                                modifier = Modifier.testTag("${if (trash) "restoreTask" else "swipeTask"}:${task.title}"),
                                enableDismissFromStartToEnd = trash,
                                enableDismissFromEndToStart = !trash,
                                onDismiss = { direction ->
                                    if (!swipeHandled) {
                                        swipeHandled = true
                                        if (trash && direction == SwipeToDismissBoxValue.StartToEnd) {
                                            onUpdate(task.copy(deletedAt = null))
                                        } else if (!trash && direction == SwipeToDismissBoxValue.EndToStart) {
                                            onUpdate(task.copy(deletedAt = System.currentTimeMillis()))
                                        }
                                    }
                                },
                                backgroundContent = {
                                    Box(
                                        Modifier.fillMaxSize().background(palette.accent)
                                            .clearAndSetSemantics { }
                                            .padding(horizontal = 18.dp),
                                        contentAlignment = if (trash) Alignment.CenterStart else Alignment.CenterEnd
                                    ) {
                                        Text(if (trash) "ВОССТАНОВИТЬ" else "В КОРЗИНУ",
                                            color = palette.accentText,
                                            fontSize = 12.sp, fontWeight = FontWeight.Black)
                                    }
                                }
                            ) {
                                TaskRow(
                                    task = task, inTrash = trash,
                                    onOpen = { editing = task },
                                    onToggle = { onUpdate(task.copy(completed = !task.completed)) }
                                )
                            }
                            HorizontalDivider(color = palette.divider)
                        }
                    }
                }
            }
            if (!trash) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).height(64.dp)
                        .clip(RoundedCornerShape(8.dp)).background(palette.accent)
                        .padding(start = 18.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (input.isEmpty()) Text(
                            "ДОБАВИТЬ ДЕЛО", color = palette.accentText,
                            fontSize = 16.sp, fontWeight = FontWeight.ExtraBold
                        )
                        BasicTextField(
                            value = input, onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(inputFocusRequester)
                                .onFocusChanged { inputFocused = it.isFocused }
                                .testTag("newTaskInput"), singleLine = true,
                            textStyle = TextStyle(color = palette.accentText, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() })
                        )
                    }
                    Box(
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(5.dp))
                            .semantics { contentDescription = "Добавить дело" }
                            .background(palette.accentText).clickable { submit() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", fontSize = 29.sp, color = palette.accent, lineHeight = 29.sp)
                    }
                }
            } else Spacer(Modifier.height(20.dp))
        }
    }

    if (draftDialog) Dialog(onDismissRequest = { draftDialog = false }) {
        Surface(shape = RoundedCornerShape(16.dp), color = palette.sheet) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Что делать с заметкой?", color = palette.foreground,
                    fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(12.dp))
                Text("Текст пока не сохранён.", color = palette.secondary, fontSize = 14.sp)
                Spacer(Modifier.height(26.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            draftDialog = false
                            inputFocusRequester.requestFocus()
                            keyboardController?.show()
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Продолжить", modifier = Modifier.fillMaxWidth(),
                            autoSize = TextAutoSize.StepBased(10.sp, 16.sp, 0.5.sp),
                            maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { input = ""; draftDialog = false },
                        modifier = Modifier.weight(1f).height(56.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Удаляй", modifier = Modifier.fillMaxWidth(), color = palette.foreground,
                            autoSize = TextAutoSize.StepBased(10.sp, 16.sp, 0.5.sp),
                            maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    editing?.let { selected ->
        var title by remember(selected.id) { mutableStateOf(selected.title) }
        ModalBottomSheet(onDismissRequest = { editing = null }, containerColor = palette.sheet) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 34.dp)) {
                Text(
                    if (trash) "УДАЛЁННОЕ ДЕЛО" else "РЕДАКТИРОВАТЬ ДЕЛО",
                    color = palette.secondary, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
                )
                Spacer(Modifier.height(20.dp))
                if (!trash) {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth().testTag("editTitle"), minLines = 2, maxLines = 8,
                        label = { Text("Название") }
                    )
                    Spacer(Modifier.height(14.dp))
                    TextButton(onClick = {
                        editing = null
                        onPickReminder(selected.copy(title = title.trim().ifEmpty { selected.title }))
                    }) {
                        Text(
                            if (selected.reminderAt == null) "+ НАЗНАЧИТЬ НАПОМИНАНИЕ"
                            else "ИЗМЕНИТЬ ВРЕМЯ · ${formatReminder(selected.reminderAt)}",
                            color = palette.foreground
                        )
                    }
                    if (selected.reminderAt != null) TextButton(onClick = {
                        onUpdate(selected.copy(title = title.trim().ifEmpty { selected.title }, reminderAt = null))
                        editing = null
                    }) { Text("УБРАТЬ НАПОМИНАНИЕ", color = palette.secondary) }
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = { if (title.isNotBlank()) onUpdate(selected.copy(title = title.trim())); editing = null },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        shape = RoundedCornerShape(7.dp)
                    ) { Text("СОХРАНИТЬ", fontWeight = FontWeight.Black) }
                    TextButton(onClick = {
                        onUpdate(selected.copy(deletedAt = System.currentTimeMillis()))
                        editing = null
                    }) { Text("В КОРЗИНУ", color = palette.secondary) }
                } else {
                    Text(selected.title, color = palette.foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(25.dp))
                    Button(
                        onClick = { onUpdate(selected.copy(deletedAt = null)); editing = null },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        shape = RoundedCornerShape(7.dp)
                    ) { Text("ВОССТАНОВИТЬ", fontWeight = FontWeight.Black) }
                    TextButton(onClick = { permanentDelete = selected; editing = null }) {
                        Text("УДАЛИТЬ НАВСЕГДА", color = palette.secondary)
                    }
                }
            }
        }
    }

    if (themeDialog) Dialog(onDismissRequest = { themeDialog = false }) {
        Surface(shape = RoundedCornerShape(16.dp), color = palette.sheet,
            modifier = Modifier.testTag("themeDialog")) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("ТЕМА", color = palette.foreground, fontSize = 25.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                Text("Как будет выглядеть СВАЛКА", color = palette.secondary, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                ThemeMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ThemeMode.SYSTEM -> "Как в системе"
                        ThemeMode.LIGHT -> "Светлая"
                        ThemeMode.DARK -> "Тёмная"
                    }
                    val selected = themeMode == mode
                    val shape = RoundedCornerShape(8.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth().height(56.dp).clip(shape)
                            .background(if (selected) palette.accent else palette.background)
                            .border(1.dp, if (selected) palette.accent else palette.divider, shape)
                            .clickable { onThemeChange(mode); themeDialog = false }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, modifier = Modifier.weight(1f),
                            color = if (selected) palette.accentText else palette.foreground,
                            fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (selected) Text("✓", color = palette.accentText,
                            fontSize = 21.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (backupDialog) Dialog(onDismissRequest = { backupDialog = false }) {
        Surface(shape = RoundedCornerShape(16.dp), color = palette.sheet) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("ТВОИ ДЕЛА", color = palette.foreground, fontSize = 25.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                Text("Сохрани файл на телефоне или в облаке. Его можно открыть здесь позже.",
                    color = palette.secondary, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { backupDialog = false; onExport() },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("СОХРАНИТЬ КОПИЮ", fontWeight = FontWeight.Black) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { backupDialog = false; onChooseImport() },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("ВОССТАНОВИТЬ ИЗ ФАЙЛА", color = palette.foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }

    backupPreview?.let { preview ->
        val currentIds = tasks.mapTo(HashSet<String>()) { it.id }
        val newCount = preview.tasks.count { it.id !in currentIds }
        Dialog(onDismissRequest = onDismissImport) {
            Surface(shape = RoundedCornerShape(16.dp), color = palette.sheet) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("ВОССТАНОВИТЬ КОПИЮ?", color = palette.foreground,
                        fontSize = 23.sp, lineHeight = 27.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(12.dp))
                    Text("Копия от ${SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru-RU")).format(Date(preview.exportedAt))}",
                        color = palette.secondary, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("В файле: ${preview.tasks.size} дел, из них ${preview.tasks.count { it.deletedAt != null }} в корзине.\nСейчас: ${tasks.size} дел. Новых в файле: $newCount.",
                        color = palette.secondary, fontSize = 14.sp, lineHeight = 21.sp)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { onConfirmImport(false) }, enabled = newCount > 0,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("ДОБАВИТЬ НОВЫЕ · $newCount", fontSize = 13.sp, fontWeight = FontWeight.Black) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { replaceConfirmation = true },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("ЗАМЕНИТЬ ВЕСЬ СПИСОК", color = palette.foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = onDismissImport, modifier = Modifier.align(Alignment.End)) {
                        Text("ОТМЕНА", color = palette.secondary)
                    }
                }
            }
        }
    }

    if (replaceConfirmation && backupPreview != null) AlertDialog(
        onDismissRequest = { replaceConfirmation = false },
        title = { Text("Заменить все дела?") },
        text = { Text("Текущий список и корзина будут заменены содержимым файла. Сохрани копию текущих дел, если они нужны.") },
        confirmButton = { TextButton(onClick = { replaceConfirmation = false; onConfirmImport(true) }) { Text("ЗАМЕНИТЬ") } },
        dismissButton = { TextButton(onClick = { replaceConfirmation = false }) { Text("ОТМЕНА") } }
    )

    permanentDelete?.let { task -> AlertDialog(
        onDismissRequest = { permanentDelete = null },
        title = { Text("Удалить навсегда?") },
        text = { Text("Это дело уже нельзя будет восстановить.") },
        confirmButton = { TextButton(onClick = { onDelete(task); permanentDelete = null }) { Text("УДАЛИТЬ") } },
        dismissButton = { TextButton(onClick = { permanentDelete = null }) { Text("ОТМЕНА") } }
    ) }
}

@Composable
private fun TaskRow(task: ReminderTask, inTrash: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val palette = markerColors()
    val reminderText = remember(task.reminderAt) { task.reminderAt?.let { formatReminder(it) } }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .background(palette.background).clickable(onClick = onOpen)
            .padding(vertical = 19.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.size(48.dp)
                .semantics { contentDescription = if (task.completed) "Вернуть дело: ${task.title}" else "Вычеркнуть дело: ${task.title}" }
                .clickable(enabled = !inTrash, onClick = onToggle),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier.size(23.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (task.completed) palette.accent else Color.Transparent)
                    .border(2.dp, if (task.completed) palette.accent else palette.foreground, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) { if (task.completed) Text("✓", color = palette.accentText, fontSize = 16.sp, lineHeight = 16.sp) }
        }
        Column(modifier = Modifier.weight(1f).padding(top = 3.dp)) {
            Text(
                task.title,
                color = if (task.completed || inTrash) palette.secondary else palette.foreground,
                fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold,
                textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None
            )
            reminderText?.let {
                Spacer(Modifier.height(5.dp))
                Text(
                    "↗  $it",
                    color = if (task.completed || inTrash) palette.secondary else palette.reminderText,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
        }
        Text("⋯", color = palette.secondary, fontSize = 24.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun formatReminder(timestamp: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru-RU"))
        .format(Date(timestamp)).uppercase(Locale.forLanguageTag("ru-RU"))

private fun taskCountLabel(count: Int): String {
    val word = when {
        count % 100 in 11..14 -> "дел"
        count % 10 == 1 -> "дело"
        count % 10 in 2..4 -> "дела"
        else -> "дел"
    }
    return "$count $word"
}
