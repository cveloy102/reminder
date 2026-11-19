package com.svalka.reminder

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.svalka.reminder.ui.theme.Inter
import com.svalka.reminder.ui.theme.Mono
import com.svalka.reminder.ui.theme.ThemeMode
import com.svalka.reminder.ui.theme.markerColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val RU = Locale.forLanguageTag("ru-RU")

/** Small monospaced caption: "[ ДЕЛА ]", "КОРЗИНА 3", section labels. */
@Composable
private fun Caption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = markerColors().secondary,
    size: TextUnit = 11.sp,
    spacing: TextUnit = 1.sp
) = Text(text, modifier = modifier, color = color, fontFamily = Mono, fontSize = size,
    fontWeight = FontWeight.Bold, letterSpacing = spacing, maxLines = 1)

private fun sectionName(list: TaskList) = when (list) {
    TaskList.TASKS -> "ДЕЛА"
    TaskList.SHOPPING -> "ПОКУПКИ"
    TaskList.LINKS -> "ССЫЛКИ"
    TaskList.THOUGHTS -> "МЫСЛИ"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SvalkaScreen(
    tasks: List<ReminderTask>,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    sectionRequest: TaskList?,
    onSectionRequestHandled: () -> Unit,
    onAdd: (String, TaskList) -> Unit,
    onAddLink: (String, String) -> Unit,
    onUpdate: (ReminderTask) -> Unit,
    onToggle: (ReminderTask) -> Unit,
    onClearCompleted: (TaskList) -> Unit,
    onReorder: (String, String) -> Unit,
    onDelete: (ReminderTask) -> Unit,
    onSetReminder: (ReminderTask, Long, RepeatRule) -> Unit,
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
    onConfirmImport: (Boolean) -> Unit,
    syncState: com.svalka.reminder.sync.SyncUi = com.svalka.reminder.sync.SyncUi(),
    onSyncSignIn: (String, String, String, Boolean, (String?) -> Unit) -> Unit = { _, _, _, _, done -> done("Недоступно") },
    onSyncNow: () -> Unit = {},
    onSyncSignOut: () -> Unit = {}
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
    var thoughtInput by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf(TaskList.TASKS) }
    val input = when (section) {
        TaskList.TASKS -> taskInput
        TaskList.SHOPPING -> shoppingInput
        TaskList.LINKS -> linkInput
        TaskList.THOUGHTS -> thoughtInput
    }
    fun updateInput(value: String) {
        when (section) {
            TaskList.TASKS -> taskInput = value
            TaskList.SHOPPING -> shoppingInput = value
            TaskList.LINKS -> linkInput = value
            TaskList.THOUGHTS -> thoughtInput = value
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
    var syncDialog by remember { mutableStateOf(false) }
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
    val thoughts = section == TaskList.THOUGHTS

    LaunchedEffect(sectionRequest) {
        sectionRequest?.let {
            section = it
            trash = false
            onSectionRequestHandled()
        }
    }

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

    fun selectSection(selected: TaskList) {
        section = selected
        draftDialog = false
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    Surface(color = palette.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                    .padding(horizontal = 20.dp)
                    .testTag("mainCanvas")
            ) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().then(backSwipe).testTag("trashHeader"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Caption(if (trash) "[ КОРЗИНА ]" else "▦ SVLK / PERSONAL",
                        modifier = Modifier.weight(1f), color = if (trash) palette.accent else palette.secondary)
                    if (!trash) TextButton(
                        onClick = { backupDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.testTag("backupMenu")
                    ) { Caption("ДАННЫЕ", color = palette.foreground) }
                    TextButton(onClick = { if (trash) trash = false else themeDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.testTag("styleMenu")) {
                        Caption(if (trash) "НАЗАД" else "ТЕМА ◐", color = palette.foreground)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (trash) "КОРЗИНА" else "СВАЛКА", color = palette.foreground,
                        fontFamily = Inter, fontSize = 44.sp, lineHeight = 46.sp,
                        fontWeight = FontWeight.Black, letterSpacing = (-2.2).sp)
                    Text("_", color = palette.accent, fontFamily = Inter, fontSize = 44.sp,
                        lineHeight = 46.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(16.dp).fillMaxWidth().testTag("emptyTapArea"))
                Caption("[ ${sectionName(section)} ]", color = palette.accent, spacing = 1.5.sp)
                Spacer(Modifier.height(6.dp))
                val count = if (trash) deleted.size else if (thoughts) active.size else active.count { !it.completed }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$count", color = palette.foreground, fontFamily = Inter,
                        fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-1).sp)
                    Text(if (trash) "  можно вернуть · хранится 30 дней" else when (section) {
                        TaskList.TASKS -> "  ${countNoun(count, "дело", "дела", "дел")} на очереди"
                        TaskList.SHOPPING -> "  ${countNoun(count, "покупка", "покупки", "покупок")} в списке"
                        TaskList.LINKS -> "  ${countNoun(count, "ссылка", "ссылки", "ссылок")} в списке"
                        TaskList.THOUGHTS -> "  ${countNoun(count, "мысль записана", "мысли записаны", "мыслей записано")}"
                    }, color = palette.secondary, fontSize = 15.sp, lineHeight = 22.sp,
                        modifier = Modifier.padding(bottom = 3.dp))
                    Text("_", color = palette.accent, fontSize = 15.sp, lineHeight = 22.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 3.dp))
                }
                Spacer(Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Caption(if (trash) "УДАЛЁННЫЕ" else if (thoughts) "ЗАПИСИ" else "СПИСОК",
                        modifier = Modifier.weight(1f))
                    val purchased = active.count { it.completed }
                    if (!trash && section == TaskList.SHOPPING && purchased > 0) {
                        Caption("ОЧИСТИТЬ КУПЛЕННОЕ · $purchased", color = palette.reminderText, spacing = 0.sp,
                            modifier = Modifier.heightIn(min = 44.dp).testTag("clearPurchased")
                                .clickable { onClearCompleted(TaskList.SHOPPING) }
                                .wrapContentHeight(Alignment.CenterVertically)
                                .padding(horizontal = 10.dp))
                    }
                    Caption(if (trash) "${deleted.size}" else "КОРЗИНА ${deleted.size}",
                        modifier = Modifier.heightIn(min = 44.dp)
                            .semantics { contentDescription = if (trash) "Назад к списку" else "Открыть корзину" }
                            .clickable { trash = !trash }
                            .wrapContentHeight(Alignment.CenterVertically))
                }
                HorizontalDivider(color = palette.divider)
                if (visible.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("blankListArea"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            when {
                                trash -> "Здесь пока пусто"
                                section == TaskList.SHOPPING -> "Здесь пока пусто.\nДобавь первую покупку ↓"
                                section == TaskList.LINKS -> "Здесь пока пусто.\nВставь первую ссылку ↓"
                                thoughts -> "Здесь пока пусто.\nЗапиши первую мысль ↓"
                                else -> "Здесь пока пусто.\nЗапиши первое дело ↓"
                            },
                            color = palette.secondary, textAlign = TextAlign.Center,
                            fontSize = 16.sp, lineHeight = 24.sp
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
                                if (!trash && task.completed && (index == 0 || !visible[index - 1].completed)) {
                                    Caption("ГОТОВО / ${visible.count { it.completed }}",
                                        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
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
                                            Caption(if (trash) "ВОССТАНОВИТЬ" else "В КОРЗИНУ",
                                                color = palette.accentText, size = 12.sp)
                                        }
                                    }
                                ) {
                                    TaskRow(
                                        task = task, inTrash = trash,
                                        onOpen = { editing = task },
                                        onToggle = { onToggle(task) },
                                        onPin = {
                                            onUpdate(task.copy(pinned = !task.pinned))
                                            Toast.makeText(context,
                                                if (task.pinned) "Откреплено" else "Закреплено",
                                                Toast.LENGTH_SHORT).show()
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
                            .padding(bottom = 8.dp)
                            .background(palette.sheet).border(1.dp, palette.divider)
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Caption(if (pending.restoring) "ВОССТАНОВЛЕНО" else "В КОРЗИНЕ",
                            modifier = Modifier.weight(1f), color = palette.foreground, size = 10.sp)
                        Caption("$undoSecondsLeft С", modifier = Modifier.testTag("undoCountdown"), size = 10.sp)
                        TextButton(onClick = {
                            pendingSwipe = null
                            onUpdate(pending.task)
                        }, modifier = Modifier.testTag("cancelSwipe")) {
                            Caption("ОТМЕНИТЬ", color = palette.reminderText, size = 10.sp)
                        }
                    }
                }
                if (!trash) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .onSizeChanged { composerHeightPx = it.height }
                            .padding(bottom = 12.dp).heightIn(min = 60.dp)
                            .background(palette.accent)
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (input.isEmpty()) Text(
                                when (section) {
                                    TaskList.TASKS -> "ДОБАВИТЬ ДЕЛО"
                                    TaskList.SHOPPING -> "ДОБАВИТЬ ПОКУПКУ"
                                    TaskList.LINKS -> "ВСТАВИТЬ ССЫЛКУ"
                                    TaskList.THOUGHTS -> "ЗАПИСАТЬ МЫСЛЬ"
                                },
                                color = palette.accentText, fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold, letterSpacing = 0.3.sp
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
                                maxLines = if (thoughts) 10 else 8,
                                textStyle = TextStyle(fontFamily = Inter, color = palette.accentText,
                                    fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
                                cursorBrush = SolidColor(palette.accentText),
                                // Thoughts are often several lines long: Enter adds a line, "+" saves.
                                keyboardOptions = KeyboardOptions(imeAction = if (thoughts) ImeAction.Default else ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() })
                            )
                        }
                        Box(
                            modifier = Modifier.size(44.dp)
                                .semantics { contentDescription = when (section) {
                                    TaskList.TASKS -> "Добавить дело"
                                    TaskList.SHOPPING -> "Добавить покупку"
                                    TaskList.LINKS -> "Добавить ссылку"
                                    TaskList.THOUGHTS -> "Записать мысль"
                                } }
                                .background(palette.accentText).clickable { submit() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+", fontSize = 28.sp, color = palette.accent, lineHeight = 28.sp,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                    SvalkaTabs(section, ::selectSection)
                    Spacer(Modifier.height(8.dp))
                } else Spacer(Modifier.height(20.dp))
            }
            if (inputFocused && !trash) {
                Box(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                        .padding(bottom = with(density) {
                            (composerHeightPx + pendingBannerHeightPx + 56.dp.roundToPx()).toDp()
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
                        fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Можно оставить без названия — покажем адрес сайта.",
                        color = palette.secondary, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(18.dp))
                    Text(url, color = palette.reminderText, fontFamily = Mono, fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(18.dp))
                    BasicTextField(
                        value = linkName, onValueChange = { linkName = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clip(RoundedCornerShape(8.dp)).background(palette.background)
                            .border(1.dp, palette.divider, RoundedCornerShape(8.dp))
                            .padding(16.dp).testTag("linkNameInput"),
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = Inter, color = palette.foreground, fontSize = 17.sp),
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
                    Spacer(Modifier.height(22.dp))
                    Button(onClick = {
                        onAddLink(url, linkName)
                        linkInput = ""
                        linkName = ""
                        pendingLinkUrl = null
                    }, modifier = Modifier.fillMaxWidth().height(54.dp).testTag("saveLink"),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent,
                            contentColor = palette.accentText), shape = RoundedCornerShape(8.dp)) {
                        Text("СОХРАНИТЬ ССЫЛКУ", fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                    }
                }
            }
        }
    }

    if (draftDialog) Dialog(onDismissRequest = { draftDialog = false }) {
        Surface(shape = RoundedCornerShape(16.dp), color = palette.sheet) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Что делать с заметкой?", color = palette.foreground,
                    fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
                Spacer(Modifier.height(10.dp))
                Text("Текст пока не сохранён.", color = palette.secondary, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            draftDialog = false
                            inputFocusRequester.requestFocus()
                            keyboardController?.show()
                        },
                        modifier = Modifier.weight(1f).height(54.dp),
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
                        modifier = Modifier.weight(1f).height(54.dp),
                        border = BorderStroke(1.dp, palette.divider),
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
        val isThought = selected.list == TaskList.THOUGHTS
        ModalBottomSheet(
            onDismissRequest = { editing = null },
            containerColor = palette.sheet,
            sheetState = editorSheetState,
            dragHandle = null
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
                Spacer(Modifier.height(24.dp))
                Caption(
                    when {
                        trash -> "ИЗ КОРЗИНЫ"
                        isThought -> "ТВОЯ МЫСЛЬ · ${formatStamp(selected.createdAt)}"
                        selected.list == TaskList.LINKS -> "ТВОЯ ССЫЛКА"
                        selected.list == TaskList.SHOPPING -> "ТВОЯ ПОКУПКА"
                        else -> "ТВОЯ ЗАМЕТКА"
                    },
                    color = palette.reminderText, spacing = 1.5.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(when {
                    trash -> "Удалённая запись."
                    isThought -> "Редактировать мысль."
                    selected.list == TaskList.LINKS -> "Редактировать ссылку."
                    selected.list == TaskList.SHOPPING -> "Редактировать покупку."
                    else -> "Редактировать дело."
                }, color = palette.foreground, fontSize = 26.sp, lineHeight = 30.sp,
                    fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
                Spacer(Modifier.height(24.dp))
                if (!trash) {
                    Caption(when {
                        isThought -> "ТЕКСТ МЫСЛИ"
                        selected.list == TaskList.LINKS -> "НАЗВАНИЕ ССЫЛКИ"
                        selected.list == TaskList.SHOPPING -> "ТЕКСТ ПОКУПКИ"
                        else -> "ТЕКСТ ДЕЛА"
                    })
                    Spacer(Modifier.height(10.dp))
                    BasicTextField(
                        value = title, onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = if (isThought) 150.dp else 104.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.background)
                            .border(1.dp, palette.divider, RoundedCornerShape(10.dp))
                            .padding(16.dp).testTag("editTitle"),
                        minLines = if (isThought) 4 else 2, maxLines = if (isThought) 16 else 8,
                        textStyle = if (isThought) TextStyle(fontFamily = Inter, color = palette.foreground,
                            fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal)
                        else TextStyle(fontFamily = Inter, color = palette.foreground, fontSize = 18.sp,
                            lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
                        cursorBrush = SolidColor(palette.accent)
                    )
                    Spacer(Modifier.height(22.dp))
                    when {
                        isThought -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Caption(if (selected.pinned) "● ЗАКРЕПЛЕНО" else "ДЕРЖИ ПАЛЕЦ — ЗАКРЕПИТЬ",
                                modifier = Modifier.weight(1f),
                                color = if (selected.pinned) palette.reminderText else palette.secondary)
                            TextButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, title.trim())
                                }
                                runCatching { context.startActivity(Intent.createChooser(send, "Поделиться мыслью")) }
                            }, modifier = Modifier.testTag("shareThought")) {
                                Caption("ПОДЕЛИТЬСЯ ↗", color = palette.reminderText)
                            }
                        }
                        selected.list == TaskList.LINKS -> {
                            Caption("АДРЕС ССЫЛКИ")
                            Spacer(Modifier.height(10.dp))
                            BasicTextField(
                                value = urlText, onValueChange = { urlText = it },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                                    .clip(RoundedCornerShape(10.dp)).background(palette.background)
                                    .border(1.dp, palette.divider, RoundedCornerShape(10.dp))
                                    .padding(16.dp).testTag("editLinkUrl"),
                                textStyle = TextStyle(fontFamily = Mono, color = palette.foreground, fontSize = 14.sp),
                                cursorBrush = SolidColor(palette.accent)
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = {
                                val target = selected.url ?: return@TextButton
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                                    .onFailure { Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show() }
                            }, modifier = Modifier.align(Alignment.End)) {
                                Caption("ОТКРЫТЬ ССЫЛКУ ↗", color = palette.reminderText, size = 12.sp)
                            }
                        }
                        else -> {
                            Caption("НАПОМИНАНИЕ")
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(palette.background)
                                    .border(1.dp, palette.divider, RoundedCornerShape(10.dp))
                                    .clickable {
                                        reminderEditing = selected.copy(title = normalizeTaskTitle(title).ifEmpty { selected.title })
                                        editing = null
                                    }
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("↗", color = palette.reminderText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (selected.reminderAt == null) "  Назначить дату и время"
                                    else "  ${reminderLabel(selected)}",
                                    modifier = Modifier.weight(1f), color = palette.foreground,
                                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                                )
                                Caption("ВЫБРАТЬ", size = 10.sp)
                            }
                            if (selected.reminderAt != null) TextButton(onClick = {
                                onUpdate(selected.copy(title = normalizeTaskTitle(title).ifEmpty { selected.title },
                                    reminderAt = null, repeat = RepeatRule.NONE, snoozeUntil = null))
                                editing = null
                            }, modifier = Modifier.align(Alignment.End)) {
                                Caption("УБРАТЬ НАПОМИНАНИЕ")
                            }
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    HorizontalDivider(color = palette.divider)
                    Spacer(Modifier.height(20.dp))
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
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("СОХРАНИТЬ ИЗМЕНЕНИЯ", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp) }
                    TextButton(onClick = {
                        onUpdate(selected.copy(deletedAt = System.currentTimeMillis()))
                        editing = null
                    }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("В КОРЗИНУ", color = palette.secondary, fontFamily = Mono, fontSize = 12.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                } else {
                    Text(selected.title, color = palette.foreground, fontSize = 19.sp,
                        lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(26.dp))
                    Button(
                        onClick = { onUpdate(selected.copy(deletedAt = null)); editing = null },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("ВОССТАНОВИТЬ", fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp) }
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
            onConfirm = { at, repeat ->
                onSetReminder(task, at, repeat)
                reminderEditing = null
            }
        )
    }

    if (themeDialog) Dialog(onDismissRequest = { themeDialog = false }) {
        Surface(shape = RoundedCornerShape(0.dp), color = palette.sheet,
            modifier = Modifier.testTag("themeDialog")) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Caption("[ НАСТРОЙКИ / 01 ]", color = palette.reminderText)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Тема", color = palette.foreground, fontSize = 28.sp,
                        fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
                    Text("_", color = palette.accent, fontSize = 28.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(20.dp))
                Caption("РЕЖИМ")
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
                            textAlign = TextAlign.Center, fontFamily = Mono, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { themeDialog = false }) {
                    Caption("НАЗАД К СПИСКУ ←")
                }
            }
        }
    }

    if (backupDialog) Dialog(onDismissRequest = { backupDialog = false }) {
        Surface(shape = RoundedCornerShape(16.dp), color = palette.sheet) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text("ТВОИ ДАННЫЕ", color = palette.foreground, fontSize = 24.sp,
                    fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
                Spacer(Modifier.height(10.dp))
                Text("Копия сохраняет дела, покупки, ссылки и мысли. Восстановить её можно из файла в этом меню.",
                    color = palette.secondary, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(20.dp))
                Caption("СИНХРОНИЗАЦИЯ", color = palette.foreground, size = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        !syncState.signedIn -> "Свой сервер: записи будут одинаковыми на телефоне и компьютере."
                        syncState.error != null -> "Ошибка: ${syncState.error}"
                        else -> "Включена · ${syncState.email}"
                    },
                    color = if (syncState.error != null) palette.reminderText else palette.secondary,
                    fontSize = 13.sp, lineHeight = 19.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { backupDialog = false; syncDialog = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("openSync"),
                    border = BorderStroke(1.dp, palette.divider),
                    shape = RoundedCornerShape(8.dp)
                ) { Text(if (syncState.signedIn) "НАСТРОЙКИ СИНХРОНИЗАЦИИ" else "ПОДКЛЮЧИТЬ СЕРВЕР",
                    color = palette.foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = palette.divider)
                Spacer(Modifier.height(18.dp))
                Caption("GOOGLE ДИСК", color = palette.foreground, size = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        cloudError != null -> "Ошибка: $cloudError"
                        cloudSaving -> "Сохраняю копию…"
                        cloudConnected && lastCloudSaveAt > 0L -> "Автокопия включена · сохранено ${SimpleDateFormat("d MMM, HH:mm", RU).format(Date(lastCloudSaveAt))}"
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
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("ПОДКЛЮЧИТЬ GOOGLE ДИСК", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) }
                } else {
                    Button(
                        onClick = onSaveCloudNow, enabled = !cloudSaving,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("СОХРАНИТЬ СЕЙЧАС", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { backupDialog = false; onChooseCloudFile() }, enabled = !cloudSaving) {
                            Caption("СМЕНИТЬ ФАЙЛ")
                        }
                        TextButton(onClick = onDisconnectCloud, enabled = !cloudSaving) {
                            Caption("ОТКЛЮЧИТЬ")
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = palette.divider)
                Spacer(Modifier.height(18.dp))
                Caption("ОТДЕЛЬНЫЙ ФАЙЛ", color = palette.foreground, size = 12.sp)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { backupDialog = false; onExport() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("СОХРАНИТЬ КОПИЮ", fontWeight = FontWeight.ExtraBold) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { backupDialog = false; onChooseImport() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    border = BorderStroke(1.dp, palette.divider),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("ВОССТАНОВИТЬ ИЗ ФАЙЛА", color = palette.foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (syncDialog) SyncDialog(
        state = syncState,
        onDismiss = { syncDialog = false },
        onSignIn = onSyncSignIn,
        onSyncNow = onSyncNow,
        onSignOut = onSyncSignOut
    )

    backupPreview?.let { preview ->
        val currentIds = tasks.mapTo(HashSet<String>()) { it.id }
        val newCount = preview.tasks.count { it.id !in currentIds }
        Dialog(onDismissRequest = onDismissImport) {
            Surface(shape = RoundedCornerShape(16.dp), color = palette.sheet) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("ВОССТАНОВИТЬ КОПИЮ?", color = palette.foreground,
                        fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
                    Spacer(Modifier.height(12.dp))
                    Caption("КОПИЯ ОТ ${SimpleDateFormat("d MMM yyyy, HH:mm", RU).format(Date(preview.exportedAt)).uppercase(RU)}")
                    Spacer(Modifier.height(10.dp))
                    Text("В файле: ${preview.tasks.size} записей, из них ${preview.tasks.count { it.list == TaskList.SHOPPING }} покупок, " +
                        "${preview.tasks.count { it.list == TaskList.LINKS }} ссылок и ${preview.tasks.count { it.list == TaskList.THOUGHTS }} мыслей.\n" +
                        "Сейчас: ${tasks.size} записей. Новых в файле: $newCount.",
                        color = palette.secondary, fontSize = 14.sp, lineHeight = 21.sp)
                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = { onConfirmImport(false) }, enabled = newCount > 0,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("ДОБАВИТЬ НОВЫЕ · $newCount", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { replaceConfirmation = true },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        border = BorderStroke(1.dp, palette.divider),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("ЗАМЕНИТЬ ВЕСЬ СПИСОК", color = palette.foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = onDismissImport, modifier = Modifier.align(Alignment.End)) {
                        Caption("ОТМЕНА")
                    }
                }
            }
        }
    }

    if (replaceConfirmation && backupPreview != null) AlertDialog(
        onDismissRequest = { replaceConfirmation = false },
        title = { Text("Заменить все записи?") },
        text = { Text("Все списки и корзина будут заменены содержимым файла. Сохрани копию текущих данных, если они нужны.") },
        confirmButton = { TextButton(onClick = { replaceConfirmation = false; onConfirmImport(true) }) { Text("ЗАМЕНИТЬ") } },
        dismissButton = { TextButton(onClick = { replaceConfirmation = false }) { Text("ОТМЕНА") } }
    )

    permanentDelete?.let { task -> AlertDialog(
        onDismissRequest = { permanentDelete = null },
        title = { Text("Удалить навсегда?") },
        text = { Text("Эту запись уже нельзя будет восстановить.") },
        confirmButton = { TextButton(onClick = { onDelete(task); permanentDelete = null }) { Text("УДАЛИТЬ") } },
        dismissButton = { TextButton(onClick = { permanentDelete = null }) { Text("ОТМЕНА") } }
    ) }
}

@Composable
private fun SvalkaTabs(section: TaskList, onSelect: (TaskList) -> Unit) {
    val palette = markerColors()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TaskList.entries.forEach { list ->
            Column(Modifier.weight(1f).heightIn(min = 48.dp)
                .clickable { onSelect(list) }.testTag("tab:${list.storageValue}"),
                verticalArrangement = Arrangement.Center) {
                Box(Modifier.fillMaxWidth().height(2.dp)
                    .background(if (section == list) palette.accent else palette.divider))
                Spacer(Modifier.height(9.dp))
                Text(sectionName(list),
                    color = if (section == list) palette.foreground else palette.secondary,
                    fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.2.sp)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: ReminderTask,
    inTrash: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onPin: () -> Unit,
    dragHandle: Modifier,
    highlighted: Boolean
) {
    val palette = markerColors()
    val reminderText = remember(task.reminderAt, task.snoozeUntil, task.repeat) {
        if (task.reminderAt == null) null else reminderLabel(task)
    }
    val isThought = task.list == TaskList.THOUGHTS
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (highlighted) palette.sheet else palette.background)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (isThought) {
            // Thoughts are not ticked off: the date takes the place of the checkbox.
            val dateColor = if (task.pinned && !inTrash) palette.reminderText else palette.secondary
            val (day, month) = dayAndMonth(task.createdAt)
            Column(Modifier.width(48.dp).padding(top = 2.dp)
                .semantics { contentDescription = "Мысль от $day $month" }) {
                Text(day, color = dateColor, fontFamily = Mono,
                    fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                Caption(if (task.pinned && !inTrash) "$month ●" else month, color = dateColor, size = 10.sp, spacing = 1.sp)
            }
        } else Box(
            modifier = Modifier.size(44.dp, 28.dp)
                .semantics { contentDescription = when {
                    task.list == TaskList.LINKS -> "Ссылка: ${task.title}"
                    task.completed -> "Вернуть дело: ${task.title}"
                    else -> "Вычеркнуть дело: ${task.title}"
                } }
                .clickable(enabled = !inTrash && task.list != TaskList.LINKS, onClick = onToggle),
            contentAlignment = Alignment.TopStart
        ) {
            if (task.list == TaskList.LINKS) {
                Text("↗", color = palette.reminderText, fontSize = 22.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold)
            } else Box(
                modifier = Modifier.padding(top = 2.dp).size(22.dp)
                    .background(if (task.completed) palette.accent else Color.Transparent)
                    .border(2.dp, if (task.completed) palette.accent else palette.foreground),
                contentAlignment = Alignment.Center
            ) { if (task.completed) Text("✓", color = palette.accentText, fontSize = 14.sp, lineHeight = 14.sp,
                fontWeight = FontWeight.Bold) }
        }
        Column(modifier = Modifier.weight(1f)
            .combinedClickable(onClick = onOpen, onLongClick = if (inTrash || task.completed) null else onPin)) {
            if (task.pinned && !inTrash && !isThought) {
                Caption("● ЗАКРЕПЛЕНО", color = palette.reminderText, size = 10.sp, spacing = 1.sp)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                task.title,
                color = if (task.completed || inTrash) palette.secondary else palette.foreground,
                fontSize = if (isThought) 16.sp else if (task.completed) 15.sp else 17.sp,
                lineHeight = if (isThought) 24.sp else 23.sp,
                fontWeight = if (isThought) FontWeight.Normal else FontWeight.SemiBold,
                letterSpacing = if (isThought) 0.sp else (-0.2).sp,
                textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None
            )
            if (task.list == TaskList.LINKS && task.url != null) {
                Spacer(Modifier.height(4.dp))
                Text(task.url, color = palette.secondary, fontFamily = Mono, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            reminderText?.let {
                Spacer(Modifier.height(5.dp))
                Caption("↗ $it", color = if (task.completed || inTrash) palette.secondary else palette.reminderText,
                    size = 11.sp, spacing = 0.3.sp)
            }
        }
        Box(
            modifier = Modifier.size(40.dp, 28.dp).then(dragHandle)
                .semantics { contentDescription = "Перетащить: ${task.title}" }
                .testTag("dragHandle:${task.title}"),
            contentAlignment = Alignment.TopEnd
        ) {
            Text(if (inTrash) "⋯" else "⋮", color = palette.secondary,
                fontSize = 22.sp, lineHeight = 22.sp)
        }
    }
}

private data class PendingSwipe(
    val task: ReminderTask,
    val restoring: Boolean
)

private fun reminderLabel(task: ReminderTask): String {
    val at = task.effectiveAt ?: return ""
    val snoozed = task.snoozeUntil != null
    val repeat = if (task.repeat == RepeatRule.NONE) "" else " · ${task.repeat.label}"
    return formatReminder(at) + (if (snoozed) " · ОТЛОЖЕНО" else "") + repeat
}

private fun formatReminder(timestamp: Long): String =
    SimpleDateFormat("d MMM, HH:mm", RU).format(Date(timestamp)).uppercase(RU).replace(".", "")

private val MONTHS = listOf("ЯНВ", "ФЕВ", "МАР", "АПР", "МАЯ", "ИЮН", "ИЮЛ", "АВГ", "СЕН", "ОКТ", "НОЯ", "ДЕК")

private fun dayAndMonth(timestamp: Long): Pair<String, String> {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    return calendar.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0') to
        MONTHS[calendar.get(java.util.Calendar.MONTH)]
}

private fun formatStamp(timestamp: Long): String =
    SimpleDateFormat("d MMM, HH:mm", RU).format(Date(timestamp)).uppercase(RU).replace(".", "")

private fun countNoun(count: Int, one: String, few: String, many: String): String = when {
    count % 100 in 11..14 -> many
    count % 10 == 1 -> one
    count % 10 in 2..4 -> few
    else -> many
}
