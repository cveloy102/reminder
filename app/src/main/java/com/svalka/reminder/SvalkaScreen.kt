package com.svalka.reminder

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import com.svalka.reminder.ui.theme.ThemeMode
import com.svalka.reminder.ui.theme.VisualStyle
import com.svalka.reminder.ui.theme.markerColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SvalkaScreen(
    tasks: List<ReminderTask>,
    themeMode: ThemeMode,
    visualStyle: VisualStyle,
    onStyleChange: (VisualStyle) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onAdd: (String, TaskList) -> Unit,
    onAddLink: (String, String) -> Unit,
    onUpdate: (ReminderTask) -> Unit,
    onReorder: (String, String) -> Unit,
    onDelete: (ReminderTask) -> Unit,
    onSetReminder: (ReminderTask, Long) -> Unit,
    backupPreview: TaskBackup?,
    cloudConnected: Boolean,
    cloudSaving: Boolean,
    cloudError: String?,
    lastCloudSaveAt: Long,
    onChooseCloudFile: () -> Unit,
    onSaveCloudNow: () -> Unit,
    onDisconnectCloud: () -> Unit,
    onExport: () -> Unit,
    onChooseImport: () -> Unit,
    onDismissImport: () -> Unit,
    onConfirmImport: (Boolean) -> Unit
) {
    val palette = markerColors()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { FocusRequester() }
    var taskInput by rememberSaveable { mutableStateOf("") }
    var shoppingInput by rememberSaveable { mutableStateOf("") }
    var linkInput by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf(TaskList.TASKS) }
    val input = when (section) {
        TaskList.TASKS -> taskInput
        TaskList.SHOPPING -> shoppingInput
        TaskList.LINKS -> linkInput
    }
    fun updateInput(value: String) {
        when (section) {
            TaskList.TASKS -> taskInput = value
            TaskList.SHOPPING -> shoppingInput = value
            TaskList.LINKS -> linkInput = value
        }
    }
    var pendingLinkUrl by remember { mutableStateOf<String?>(null) }
    var linkName by remember { mutableStateOf("") }
    var inputFocused by remember { mutableStateOf(false) }
    var draftDialog by remember { mutableStateOf(false) }
    var trash by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ReminderTask?>(null) }
    var reminderEditing by remember { mutableStateOf<ReminderTask?>(null) }
    var themeDialog by remember { mutableStateOf(false) }
    var backupDialog by remember { mutableStateOf(false) }
    var replaceConfirmation by remember { mutableStateOf(false) }
    var permanentDelete by remember { mutableStateOf<ReminderTask?>(null) }
    var pendingSwipe by remember { mutableStateOf<PendingSwipe?>(null) }
    var undoSecondsLeft by remember { mutableIntStateOf(10) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var draggedOffset by remember { mutableStateOf(0f) }
    var dragTargetId by remember { mutableStateOf<String?>(null) }
    var composerHeightPx by remember { mutableIntStateOf(with(density) { 78.dp.roundToPx() }) }
    var pendingBannerHeightPx by remember { mutableIntStateOf(0) }
    val active = remember(tasks, section) { orderedActiveTasks(tasks.filter { it.list == section }) }
    val currentActive by rememberUpdatedState(active)
    val deleted = remember(tasks, section) {
        tasks.filter { it.list == section && it.deletedAt != null }.sortedByDescending { it.deletedAt }
    }
    val visible = if (trash) deleted else active
    val listState = rememberLazyListState()

    LaunchedEffect(pendingSwipe) {
        pendingSwipe ?: run {
            pendingBannerHeightPx = 0
            return@LaunchedEffect
        }
        undoSecondsLeft = 10
        repeat(10) {
            delay(1_000)
            undoSecondsLeft = 9 - it
        }
        pendingSwipe = null
    }
    var previousCount by remember { mutableIntStateOf(tasks.size) }
    var previousTrash by remember { mutableStateOf(trash) }
    var previousSection by remember { mutableStateOf(section) }

    LaunchedEffect(section, trash, tasks.size) {
        if (visible.isNotEmpty() && (section != previousSection || trash != previousTrash || tasks.size > previousCount)) {
            listState.scrollToItem(0)
        }
        previousCount = tasks.size
        previousTrash = trash
        previousSection = section
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
            if (section == TaskList.LINKS) {
                val url = normalizeLinkUrl(input)
                if (url == null) {
                    Toast.makeText(context, "Вставь адрес сайта или ссылку", Toast.LENGTH_SHORT).show()
                    return
                }
                pendingLinkUrl = url
                focusManager.clearFocus()
                keyboardController?.hide()
                return
            }
            onAdd(input, section)
            updateInput("")
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
    Surface(color = palette.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                    .padding(horizontal = if (visualStyle == VisualStyle.LEGACY) 28.dp else 20.dp)
                    .testTag("mainCanvas")
            ) {
            Spacer(Modifier.height(if (visualStyle == VisualStyle.LEGACY) 28.dp else 14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().then(backSwipe).testTag("trashHeader"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (visualStyle == VisualStyle.LEGACY) {
                    Text(
                        text = if (trash) "КОРЗИНА." else "СВАЛКА",
                        modifier = Modifier.weight(1f), color = palette.foreground,
                        fontSize = if (trash) 38.sp else 42.sp, lineHeight = 39.sp,
                        fontWeight = FontWeight.Black, letterSpacing = (-2.5).sp
                    )
                } else {
                    Text(
                        text = if (trash) "[ КОРЗИНА ]" else "▦  SVLK / PERSONAL",
                        modifier = Modifier.weight(1f), color = if (trash) palette.accent else palette.secondary,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                }
                if (!trash) TextButton(
                    onClick = { backupDialog = true },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.testTag("backupMenu")
                ) {
                    Text("ДАННЫЕ", color = palette.foreground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { if (trash) trash = false else themeDialog = true },
                    modifier = Modifier.testTag("styleMenu")) {
                    Text(if (trash) "НАЗАД" else if (visualStyle == VisualStyle.LEGACY) "СТИЛЬ" else "СТИЛЬ ◐",
                        color = palette.foreground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (visualStyle != VisualStyle.LEGACY) {
                Spacer(Modifier.height(if (visualStyle == VisualStyle.SIGNAL) 24.dp else 20.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (trash) "КОРЗИНА" else "СВАЛКА", color = palette.foreground,
                        fontSize = if (visualStyle == VisualStyle.SIGNAL) 48.sp else 40.sp,
                        lineHeight = 48.sp, fontWeight = FontWeight.Black,
                        letterSpacing = (-3).sp)
                    Text("_", color = palette.accent, fontSize = 42.sp, lineHeight = 46.sp,
                        fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(20.dp).fillMaxWidth().testTag("emptyTapArea"))
                Text("[ ${when (section) { TaskList.TASKS -> "01 / ДЕЛА"; TaskList.SHOPPING -> "02 / ПОКУПКИ"; TaskList.LINKS -> "03 / ССЫЛКИ" }} ]",
                    color = palette.accent, fontSize = 11.sp, letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
            } else {
                Spacer(Modifier.height(22.dp))
                SvalkaTabs(section, visualStyle) { selected ->
                    section = selected
                    draftDialog = false
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
                Spacer(Modifier.height(24.dp).fillMaxWidth().testTag("emptyTapArea"))
            }
            if (visualStyle == VisualStyle.LEGACY) {
                Text(
                    text = if (trash) "Удалённое можно вернуть."
                        else if (section == TaskList.SHOPPING) "Покупок осталось: ${active.count { !it.completed }}."
                        else if (section == TaskList.LINKS) "Ссылок сохранено: ${active.size}."
                        else "${taskCountLabel(active.count { !it.completed })}. Остальное подождёт.",
                    color = palette.secondary, fontSize = 13.sp
                )
            } else {
                val count = if (trash) deleted.size else active.count { !it.completed }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$count",
                        color = palette.foreground, fontSize = 34.sp, lineHeight = 38.sp,
                        fontWeight = FontWeight.Bold)
                    Text(if (trash) "  можно вернуть" else when (section) {
                        TaskList.TASKS -> "  ${countNoun(count, "дело", "дела", "дел")} на очереди"
                        TaskList.SHOPPING -> "  ${countNoun(count, "покупка", "покупки", "покупок")} в списке"
                        TaskList.LINKS -> "  ${countNoun(count, "ссылка", "ссылки", "ссылок")} в списке"
                    }, color = palette.secondary, fontSize = 15.sp)
                    Text("_", color = palette.accent, fontSize = 21.sp)
                }
            }
            Spacer(Modifier.height(if (visualStyle == VisualStyle.LEGACY) 42.dp else 22.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (trash) "УДАЛЁННЫЕ" else when (section) {
                        TaskList.TASKS -> if (visualStyle == VisualStyle.LEGACY) "СПИСОК / 001" else "СПИСОК / 01"
                        TaskList.SHOPPING -> if (visualStyle == VisualStyle.LEGACY) "СПИСОК / 002" else "СПИСОК / 02"
                        TaskList.LINKS -> if (visualStyle == VisualStyle.LEGACY) "СПИСОК / 003" else "СПИСОК / 03"
                    },
                    modifier = Modifier.weight(1f), color = palette.secondary,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = if (visualStyle == VisualStyle.LEGACY) 2.sp else 1.sp
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
                        if (trash) "Здесь пока пусто"
                        else if (section == TaskList.SHOPPING) "Здесь пока пусто.\nДобавь первую покупку ↓"
                        else if (section == TaskList.LINKS) "Здесь пока пусто.\nВставь первую ссылку ↓"
                        else "Здесь пока пусто.\nЗапиши первое дело ↓",
                        color = palette.secondary, textAlign = TextAlign.Center,
                        fontSize = 17.sp, lineHeight = 26.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).testTag("blankListArea")
                ) {
                    itemsIndexed(visible, key = { _, task -> "${if (trash) "trash" else "active"}:${task.id}" }) { index, task ->
                        Column(Modifier.animateItem()
                            .zIndex(if (draggedId == task.id) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (draggedId == task.id) draggedOffset else 0f
                            }) {
                            if (!trash && task.completed && visualStyle != VisualStyle.LEGACY &&
                                (index == 0 || !visible[index - 1].completed)) {
                                Text("ГОТОВО / ${visible.count { it.completed }}", modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                                    color = palette.secondary, fontSize = 11.sp, letterSpacing = 1.sp)
                            }
                            val dismissState = remember(task.id, trash) {
                                SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { it * 0.72f })
                            }
                            var swipeHandled by remember { mutableStateOf(false) }
                            LaunchedEffect(dismissState.currentValue) {
                                if (dismissState.currentValue == SwipeToDismissBoxValue.Settled) swipeHandled = false
                            }
                            val dragHandle = if (trash) Modifier else Modifier.pointerInput(task.id, task.pinned, task.completed) {
                                var targetId: String? = null
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedId = task.id
                                        draggedOffset = 0f
                                        dragTargetId = null
                                        targetId = null
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        draggedOffset += amount.y
                                        val info = listState.layoutInfo.visibleItemsInfo
                                        val source = info.firstOrNull { it.key == "active:${task.id}" }
                                        if (source != null) {
                                            val center = source.offset + source.size / 2f + draggedOffset
                                            targetId = info.filter { it.key != source.key }
                                                .mapNotNull { candidate ->
                                                    val id = (candidate.key as? String)?.removePrefix("active:") ?: return@mapNotNull null
                                                    val candidateTask = currentActive.firstOrNull { it.id == id }
                                                        ?: return@mapNotNull null
                                                    if (candidateTask.completed != task.completed) return@mapNotNull null
                                                    id to kotlin.math.abs(center - candidate.offset - candidate.size / 2f)
                                                }
                                                .minByOrNull { it.second }?.takeIf { it.second < source.size * 0.7f }?.first
                                            dragTargetId = targetId
                                        }
                                    },
                                    onDragEnd = {
                                        targetId?.let { onReorder(task.id, it) }
                                        draggedId = null
                                        draggedOffset = 0f
                                        dragTargetId = null
                                    },
                                    onDragCancel = {
                                        draggedId = null
                                        draggedOffset = 0f
                                        dragTargetId = null
                                    }
                                )
                            }
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
                                            pendingSwipe = PendingSwipe(task, restoring = true)
                                        } else if (!trash && direction == SwipeToDismissBoxValue.EndToStart) {
                                            onUpdate(task.copy(deletedAt = System.currentTimeMillis()))
                                            pendingSwipe = PendingSwipe(task, restoring = false)
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
                                    task = task, inTrash = trash, visualStyle = visualStyle, index = index,
                                    onOpen = { editing = task },
                                    onToggle = {
                                        onUpdate(task.copy(completed = !task.completed, pinned = false))
                                    },
                                    onPin = {
                                        onUpdate(task.copy(pinned = !task.pinned))
                                        android.widget.Toast.makeText(context,
                                            if (task.pinned) "Откреплено" else "Закреплено",
                                            android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    dragHandle = dragHandle,
                                    highlighted = dragTargetId == task.id
                                )
                            }
                            HorizontalDivider(color = palette.divider)
                        }
                    }
                }
            }
            pendingSwipe?.let { pending ->
                Row(
                    modifier = Modifier.fillMaxWidth().onSizeChanged { pendingBannerHeightPx = it.height }
                        .padding(bottom = 8.dp).clip(RoundedCornerShape(8.dp))
                        .background(palette.sheet).border(1.dp, palette.divider, RoundedCornerShape(8.dp))
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (pending.restoring) "ВОССТАНОВЛЕНО" else "В КОРЗИНЕ",
                        modifier = Modifier.weight(1f), color = palette.foreground,
                        fontSize = 10.sp, fontWeight = FontWeight.Black)
                    Text("${undoSecondsLeft} С", modifier = Modifier.testTag("undoCountdown"),
                        color = palette.secondary, fontSize = 10.sp, fontWeight = FontWeight.Black)
                    TextButton(onClick = {
                        pendingSwipe = null
                        onUpdate(pending.task)
                    }, modifier = Modifier.testTag("cancelSwipe")) {
                        Text("ОТМЕНИТЬ", color = palette.reminderText,
                            fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            if (!trash && visualStyle == VisualStyle.SIGNAL) {
                SvalkaTabs(section, visualStyle) { selected ->
                    section = selected
                    draftDialog = false
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
                Spacer(Modifier.height(10.dp))
            }
            if (!trash) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .onSizeChanged { composerHeightPx = it.height }
                        .padding(bottom = 14.dp).heightIn(min = 64.dp)
                        .clip(RoundedCornerShape(if (visualStyle == VisualStyle.LEGACY) 8.dp else 0.dp))
                        .background(palette.accent)
                        .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (input.isEmpty()) Text(
                            when (section) {
                                TaskList.TASKS -> "ДОБАВИТЬ ДЕЛО"
                                TaskList.SHOPPING -> "ДОБАВИТЬ ПОКУПКУ"
                                TaskList.LINKS -> "ВСТАВИТЬ ССЫЛКУ"
                            },
                            color = palette.accentText,
                            fontSize = if (visualStyle == VisualStyle.LEGACY) 16.sp else 14.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        BasicTextField(
                            value = input, onValueChange = { value ->
                                val pastedLink = section == TaskList.LINKS && value.length - input.length > 1
                                    && normalizeLinkUrl(value) != null
                                updateInput(value)
                                if (pastedLink) {
                                    pendingLinkUrl = normalizeLinkUrl(value)
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().focusRequester(inputFocusRequester)
                                .onFocusChanged { inputFocused = it.isFocused }
                                .testTag("newTaskInput"),
                            minLines = 1,
                            maxLines = 8,
                            textStyle = TextStyle(color = palette.accentText, fontSize = 16.sp,
                                lineHeight = 21.sp, fontWeight = FontWeight.Bold),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() })
                        )
                    }
                    Box(
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(if (visualStyle == VisualStyle.LEGACY) 5.dp else 0.dp))
                            .semantics { contentDescription = when (section) {
                                TaskList.TASKS -> "Добавить дело"
                                TaskList.SHOPPING -> "Добавить покупку"
                                TaskList.LINKS -> "Добавить ссылку"
                            } }
                            .background(palette.accentText).clickable { submit() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", fontSize = 29.sp, color = palette.accent, lineHeight = 29.sp)
                    }
                }
                if (visualStyle == VisualStyle.TERMINAL) {
                    SvalkaTabs(section, visualStyle) { selected ->
                        section = selected
                        draftDialog = false
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                    Spacer(Modifier.height(10.dp))
                }
            } else Spacer(Modifier.height(20.dp))
            }
            if (inputFocused && !trash) {
                Box(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                        .padding(bottom = with(density) {
                            (composerHeightPx + pendingBannerHeightPx +
                                if (visualStyle == VisualStyle.TERMINAL) 60.dp.roundToPx() else 0).toDp()
                        })
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = ::dismissInputFromEmptySpace
                        )
                        .testTag("draftDismissLayer")
                )
            }
        }
    }

    pendingLinkUrl?.let { url ->
        Dialog(onDismissRequest = { pendingLinkUrl = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = palette.sheet) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("КАК НАЗВАТЬ ССЫЛКУ?", color = palette.foreground,
                        fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(10.dp))
                    Text("Можно оставить без названия — покажем адрес сайта.",
                        color = palette.secondary, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(20.dp))
                    Text(url, color = palette.reminderText, fontSize = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(20.dp))
                    BasicTextField(
                        value = linkName, onValueChange = { linkName = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clip(RoundedCornerShape(8.dp)).background(palette.background)
                            .border(1.dp, palette.divider, RoundedCornerShape(8.dp))
                            .padding(16.dp).testTag("linkNameInput"),
                        singleLine = true,
                        textStyle = TextStyle(color = palette.foreground, fontSize = 17.sp),
                        cursorBrush = SolidColor(palette.accent),
                        decorationBox = { inner ->
                            Box {
                                if (linkName.isEmpty()) Text("Название (необязательно)",
                                    color = palette.secondary, fontSize = 17.sp)
                                inner()
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            onAddLink(url, linkName)
                            linkInput = ""
                            linkName = ""
                            pendingLinkUrl = null
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        })
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        onAddLink(url, linkName)
                        linkInput = ""
                        linkName = ""
                        pendingLinkUrl = null
                    }, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("saveLink"),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent,
                            contentColor = palette.accentText), shape = RoundedCornerShape(8.dp)) {
                        Text("СОХРАНИТЬ ССЫЛКУ", fontWeight = FontWeight.Black)
                    }
                }
            }
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
                        onClick = { updateInput(""); draftDialog = false },
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
        var urlText by remember(selected.id) { mutableStateOf(selected.url.orEmpty()) }
        val editorSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { editing = null },
            containerColor = palette.sheet,
            sheetState = editorSheetState,
            dragHandle = null
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 34.dp)) {
                Spacer(Modifier.height(28.dp))
                Text(
                    if (trash) "ИЗ КОРЗИНЫ" else if (section == TaskList.LINKS) "ТВОЯ ССЫЛКА"
                    else if (section == TaskList.SHOPPING) "ТВОЯ ПОКУПКА" else "ТВОЯ ЗАМЕТКА",
                    color = palette.reminderText, fontSize = 11.sp,
                    fontWeight = FontWeight.Black, letterSpacing = 2.sp
                )
                Spacer(Modifier.height(9.dp))
                Text(if (trash) "Удалённая запись." else if (section == TaskList.LINKS) "Редактировать ссылку."
                    else if (section == TaskList.SHOPPING) "Редактировать покупку." else "Редактировать дело.",
                    color = palette.foreground, fontSize = 27.sp, lineHeight = 31.sp,
                    fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
                Spacer(Modifier.height(28.dp))
                if (!trash) {
                    Text(if (section == TaskList.LINKS) "НАЗВАНИЕ ССЫЛКИ"
                        else if (section == TaskList.SHOPPING) "ТЕКСТ ПОКУПКИ" else "ТЕКСТ ДЕЛА", color = palette.secondary, fontSize = 11.sp,
                        fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(10.dp))
                    BasicTextField(
                        value = title, onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.background)
                            .border(1.dp, palette.divider, RoundedCornerShape(12.dp))
                            .padding(18.dp).testTag("editTitle"),
                        minLines = 2, maxLines = 8,
                        textStyle = TextStyle(color = palette.foreground, fontSize = 19.sp,
                            lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
                        cursorBrush = SolidColor(palette.accent)
                    )
                    Spacer(Modifier.height(25.dp))
                    if (selected.list == TaskList.LINKS) {
                        Text("АДРЕС ССЫЛКИ", color = palette.secondary, fontSize = 11.sp,
                            fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(10.dp))
                        BasicTextField(
                            value = urlText, onValueChange = { urlText = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clip(RoundedCornerShape(12.dp)).background(palette.background)
                                .border(1.dp, palette.divider, RoundedCornerShape(12.dp))
                                .padding(18.dp).testTag("editLinkUrl"),
                            textStyle = TextStyle(color = palette.foreground, fontSize = 16.sp),
                            cursorBrush = SolidColor(palette.accent)
                        )
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = {
                            val target = selected.url ?: return@TextButton
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                                .onFailure { Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show() }
                        }, modifier = Modifier.align(Alignment.End)) {
                            Text("ОТКРЫТЬ ССЫЛКУ ↗", color = palette.reminderText,
                                fontSize = 12.sp, fontWeight = FontWeight.Black)
                        }
                    } else {
                    Text("НАПОМИНАНИЕ", color = palette.secondary, fontSize = 11.sp,
                        fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(palette.background)
                            .border(1.dp, palette.divider, RoundedCornerShape(12.dp))
                            .clickable {
                                reminderEditing = selected.copy(title = normalizeTaskTitle(title).ifEmpty { selected.title })
                                editing = null
                            }
                            .padding(horizontal = 17.dp, vertical = 17.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("↗", color = palette.reminderText, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (selected.reminderAt == null) "  Назначить дату и время"
                            else "  ${formatReminder(selected.reminderAt)}",
                            modifier = Modifier.weight(1f), color = palette.foreground,
                            fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                        Text("ВЫБРАТЬ", color = palette.secondary, fontSize = 10.sp,
                            fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    }
                    if (selected.reminderAt != null) TextButton(onClick = {
                        onUpdate(selected.copy(title = normalizeTaskTitle(title).ifEmpty { selected.title }, reminderAt = null))
                        editing = null
                    }, modifier = Modifier.align(Alignment.End)) {
                        Text("УБРАТЬ НАПОМИНАНИЕ", color = palette.secondary, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold)
                    }
                    }
                    Spacer(Modifier.height(26.dp))
                    HorizontalDivider(color = palette.divider)
                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = {
                            if (selected.list == TaskList.LINKS) {
                                val normalizedUrl = normalizeLinkUrl(urlText)
                                if (normalizedUrl == null) {
                                    Toast.makeText(context, "Проверь адрес ссылки", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                onUpdate(selected.copy(title = title.trim().ifEmpty { linkDisplayName(normalizedUrl) }, url = normalizedUrl))
                            } else if (title.isNotBlank()) onUpdate(selected.copy(title = title))
                            editing = null
                        },
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("СОХРАНИТЬ ИЗМЕНЕНИЯ", fontSize = 13.sp, fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp) }
                    TextButton(onClick = {
                        onUpdate(selected.copy(deletedAt = System.currentTimeMillis()))
                        editing = null
                    }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("В КОРЗИНУ", color = palette.secondary, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                } else {
                    Text(selected.title, color = palette.foreground, fontSize = 20.sp,
                        lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = { onUpdate(selected.copy(deletedAt = null)); editing = null },
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("ВОССТАНОВИТЬ", fontWeight = FontWeight.Black) }
                    OutlinedButton(
                        onClick = { permanentDelete = selected; editing = null },
                        modifier = Modifier.fillMaxWidth().height(54.dp).padding(top = 8.dp),
                        border = BorderStroke(1.dp, palette.divider),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("УДАЛИТЬ НАВСЕГДА", color = palette.secondary, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    reminderEditing?.let { task ->
        ReminderPicker(
            task = task,
            onDismiss = { reminderEditing = null },
            onConfirm = { at ->
                onSetReminder(task, at)
                reminderEditing = null
            }
        )
    }

    if (themeDialog) Dialog(onDismissRequest = { themeDialog = false }) {
        Surface(shape = RoundedCornerShape(if (visualStyle == VisualStyle.LEGACY) 16.dp else 0.dp),
            color = palette.sheet,
            modifier = Modifier.testTag("themeDialog")) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(if (visualStyle == VisualStyle.LEGACY) "ОФОРМЛЕНИЕ" else "[ НАСТРОЙКИ / 01 ]",
                    color = palette.reminderText, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(12.dp))
                Text(if (visualStyle == VisualStyle.LEGACY) "ТВОЙ СТИЛЬ" else "Оформление_",
                    color = palette.foreground, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(22.dp))
                Text("СТИЛЬ", color = palette.secondary, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                VisualStyle.entries.forEach { style ->
                    val selected = visualStyle == style
                    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp)
                        .clickable { onStyleChange(style) }
                        .testTag("style:${style.name.lowercase()}")
                        .padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when (style) {
                            VisualStyle.TERMINAL -> "ТЕРМИНАЛ"
                            VisualStyle.SIGNAL -> "СИГНАЛ"
                            VisualStyle.LEGACY -> "ЛЕГАСИ"
                        }, modifier = Modifier.weight(1f), color = palette.foreground,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(if (selected) "●" else "↗", color = palette.reminderText, fontSize = 17.sp)
                    }
                    HorizontalDivider(color = palette.divider)
                }
                Spacer(Modifier.height(20.dp))
                Text("РЕЖИМ", color = palette.secondary, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth().border(1.dp, palette.divider),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    ThemeMode.entries.forEach { mode ->
                        Text(when (mode) {
                            ThemeMode.SYSTEM -> "СИСТЕМА"
                            ThemeMode.LIGHT -> "СВЕТЛАЯ"
                            ThemeMode.DARK -> "ТЁМНАЯ"
                        }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .background(if (themeMode == mode) palette.accent else palette.sheet)
                            .clickable { onThemeChange(mode) }
                            .wrapContentHeight(Alignment.CenterVertically)
                            .testTag("mode:${mode.name.lowercase()}")
                            .padding(horizontal = 2.dp),
                            color = if (themeMode == mode) palette.accentText else palette.foreground,
                            textAlign = TextAlign.Center, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { themeDialog = false }) {
                    Text("НАЗАД К СПИСКУ ←", color = palette.secondary, fontSize = 11.sp)
                }
            }
        }
    }

    if (backupDialog) Dialog(onDismissRequest = { backupDialog = false }) {
        Surface(shape = RoundedCornerShape(16.dp), color = palette.sheet) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text("ТВОИ ДАННЫЕ", color = palette.foreground, fontSize = 25.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                Text("Копия сохраняет дела и покупки. Восстановить её можно из файла в этом меню.",
                    color = palette.secondary, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(20.dp))
                Text("GOOGLE ДИСК", color = palette.foreground, fontSize = 14.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        cloudError != null -> "Ошибка: $cloudError"
                        cloudSaving -> "Сохраняю копию…"
                        cloudConnected && lastCloudSaveAt > 0L -> "Автокопия включена · сохранено ${SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru-RU")).format(Date(lastCloudSaveAt))}"
                        cloudConnected -> "Автокопия включена. Изменения сохраняются после редактирования."
                        else -> "Один раз выбери файл на Google Диске. Потом копия будет обновляться сама."
                    },
                    color = palette.secondary, fontSize = 13.sp, lineHeight = 19.sp
                )
                if (cloudConnected) {
                    Spacer(Modifier.height(5.dp))
                    Text("Google Диск завершит загрузку при наличии сети.",
                        color = palette.secondary, fontSize = 11.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(12.dp))
                if (!cloudConnected) {
                    Button(
                        onClick = { backupDialog = false; onChooseCloudFile() },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("ПОДКЛЮЧИТЬ GOOGLE ДИСК", fontSize = 13.sp, fontWeight = FontWeight.Black) }
                } else {
                    Button(
                        onClick = onSaveCloudNow, enabled = !cloudSaving,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("СОХРАНИТЬ СЕЙЧАС", fontSize = 13.sp, fontWeight = FontWeight.Black) }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { backupDialog = false; onChooseCloudFile() }, enabled = !cloudSaving) {
                            Text("СМЕНИТЬ ФАЙЛ", color = palette.secondary, fontSize = 11.sp)
                        }
                        TextButton(onClick = onDisconnectCloud, enabled = !cloudSaving) {
                            Text("ОТКЛЮЧИТЬ", color = palette.secondary, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = palette.secondary.copy(alpha = 0.35f))
                Spacer(Modifier.height(18.dp))
                Text("ОТДЕЛЬНЫЙ ФАЙЛ", color = palette.foreground, fontSize = 14.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(12.dp))
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
                    Text("В файле: ${preview.tasks.size} записей, из них ${preview.tasks.count { it.list == TaskList.SHOPPING }} покупок и ${preview.tasks.count { it.list == TaskList.LINKS }} ссылок.\nСейчас: ${tasks.size} записей. Новых в файле: $newCount.",
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
        title = { Text("Заменить все записи?") },
        text = { Text("Дела, покупки и обе корзины будут заменены содержимым файла. Сохрани копию текущих данных, если они нужны.") },
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
private fun SvalkaTabs(section: TaskList, style: VisualStyle, onSelect: (TaskList) -> Unit) {
    val palette = markerColors()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(TaskList.TASKS to "ДЕЛА", TaskList.SHOPPING to "ПОКУПКИ", TaskList.LINKS to "ССЫЛКИ")
            .forEachIndexed { index, (list, label) ->
                Column(Modifier.weight(1f).heightIn(min = 48.dp)
                    .clickable { onSelect(list) }.testTag("tab:${list.storageValue}"),
                    verticalArrangement = Arrangement.Center) {
                    if (style == VisualStyle.TERMINAL) Box(Modifier.fillMaxWidth().height(2.dp)
                        .background(if (section == list) palette.accent else palette.divider))
                    Spacer(Modifier.height(if (style == VisualStyle.TERMINAL) 9.dp else 4.dp))
                    Text(if (style == VisualStyle.LEGACY) label else "0${index + 1} $label",
                        color = if (section == list) palette.foreground else palette.secondary,
                        fontSize = if (style == VisualStyle.LEGACY) 12.sp else 11.sp,
                        maxLines = 1, fontWeight = FontWeight.Black,
                        letterSpacing = if (style == VisualStyle.LEGACY) 1.3.sp else 0.sp)
                    Spacer(Modifier.height(if (style == VisualStyle.LEGACY) 10.dp else 6.dp))
                    if (style != VisualStyle.TERMINAL) Box(Modifier.fillMaxWidth().height(2.dp)
                        .background(if (section == list) palette.accent else palette.divider))
                }
            }
    }
}

@Composable
private fun TaskRow(
    task: ReminderTask,
    inTrash: Boolean,
    visualStyle: VisualStyle,
    index: Int,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onPin: () -> Unit,
    dragHandle: Modifier,
    highlighted: Boolean
) {
    val palette = markerColors()
    val reminderText = remember(task.reminderAt) { task.reminderAt?.let { formatReminder(it) } }
    val featured = visualStyle == VisualStyle.SIGNAL && task.pinned && !task.completed && !inTrash
    val rowForeground = if (featured) palette.accentText else palette.foreground
    val rowSecondary = if (featured) palette.accentText.copy(alpha = 0.78f) else palette.secondary
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = if (featured) 9.dp else 0.dp)
            .heightIn(min = if (featured) 128.dp else 72.dp)
            .background(if (featured) palette.accent else if (highlighted) palette.sheet else palette.background)
            .padding(horizontal = if (featured) 14.dp else 0.dp,
                vertical = if (featured) 18.dp else 19.dp),
        verticalAlignment = if (featured) Alignment.CenterVertically else Alignment.Top
    ) {
        if (visualStyle == VisualStyle.TERMINAL && !inTrash) {
            Text("%02d".format(index + 1), color = palette.secondary, fontSize = 11.sp,
                modifier = Modifier.padding(top = 5.dp, end = 9.dp))
        }
        Box(
            modifier = Modifier.size(48.dp)
                .semantics { contentDescription = when {
                    task.list == TaskList.LINKS -> "Ссылка: ${task.title}"
                    task.completed -> "Вернуть дело: ${task.title}"
                    else -> "Вычеркнуть дело: ${task.title}"
                } }
                .clickable(enabled = !inTrash && task.list != TaskList.LINKS, onClick = onToggle),
            contentAlignment = Alignment.CenterStart
        ) {
            if (task.list == TaskList.LINKS) {
                Text("↗", color = if (featured) rowForeground else palette.reminderText,
                    fontSize = 26.sp, fontWeight = FontWeight.Bold)
            } else Box(
                modifier = Modifier.size(23.dp).clip(RoundedCornerShape(if (visualStyle == VisualStyle.LEGACY) 4.dp else 0.dp))
                    .background(if (task.completed) palette.accent else Color.Transparent)
                    .border(2.dp, if (task.completed) palette.accent else rowForeground,
                        RoundedCornerShape(if (visualStyle == VisualStyle.LEGACY) 4.dp else 0.dp)),
                contentAlignment = Alignment.Center
            ) { if (task.completed) Text("✓", color = palette.accentText, fontSize = 16.sp, lineHeight = 16.sp) }
        }
        Column(modifier = Modifier.weight(1f)
            .combinedClickable(onClick = onOpen, onLongClick = if (inTrash || task.completed) null else onPin)
            .padding(top = 3.dp)) {
            if (task.pinned && !inTrash) {
                Row {
                    Text("● ", color = if (featured) rowForeground else palette.reminderText,
                        fontSize = 10.sp, fontWeight = FontWeight.Black)
                    Text("ЗАКРЕПЛЕНО", color = if (featured) rowForeground else palette.reminderText,
                        fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                    if (featured) Text(" / 01", color = rowForeground,
                        fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(if (featured) 12.dp else 4.dp))
            }
            Text(
                task.title,
                color = if (task.completed || inTrash) palette.secondary else rowForeground,
                fontSize = if (featured) 24.sp else if (task.completed && visualStyle != VisualStyle.LEGACY) 15.sp else 18.sp,
                lineHeight = if (featured) 29.sp else 23.sp, fontWeight = FontWeight.SemiBold,
                textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None
            )
            if (task.list == TaskList.LINKS && task.url != null) {
                Spacer(Modifier.height(5.dp))
                Text(task.url, color = rowSecondary, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            reminderText?.let {
                Spacer(Modifier.height(5.dp))
                Text(
                    "↗  $it",
                    color = if (task.completed || inTrash) palette.secondary
                        else if (featured) rowForeground else palette.reminderText,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold
                )
            }
        }
        Box(
            modifier = Modifier.size(44.dp).then(dragHandle)
                .semantics { contentDescription = "Перетащить: ${task.title}" }
                .testTag("dragHandle:${task.title}"),
            contentAlignment = Alignment.TopEnd
        ) {
            Text(if (inTrash) "⋯" else "⋮", color = rowSecondary,
                fontSize = 25.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private data class PendingSwipe(
    val task: ReminderTask,
    val restoring: Boolean
)

private fun formatReminder(timestamp: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru-RU"))
        .format(Date(timestamp)).uppercase(Locale.forLanguageTag("ru-RU"))

private fun taskCountLabel(count: Int): String {
    return "$count ${countNoun(count, "дело", "дела", "дел") }"
}

private fun countNoun(count: Int, one: String, few: String, many: String): String = when {
        count % 100 in 11..14 -> many
        count % 10 == 1 -> one
        count % 10 in 2..4 -> few
        else -> many
    }
