package com.svalka.reminder

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.app.NotificationManager
import android.os.Build
import java.util.Calendar
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private lateinit var originalIds: Set<String>

    @Before fun rememberExistingTasks() {
        originalIds = TaskStore(rule.activity).load().mapTo(HashSet()) { it.id }
    }

    @After fun removeTestTasks() {
        val store = TaskStore(rule.activity)
        val saved = store.load()
        val generated = saved.filter { it.id !in originalIds }
        generated.forEach { ReminderScheduler(rule.activity).cancel(it.id) }
        store.save(saved - generated.toSet())
    }

    @Test fun addCompleteTrashRestoreAndRemove() {
        val title = "Проверка ${System.currentTimeMillis()}"
        rule.onNodeWithTag("newTaskInput").performTextInput(title)
        rule.onNodeWithTag("newTaskInput").assertIsDisplayed()
        rule.onNodeWithContentDescription("Добавить дело").performClick()
        rule.onNodeWithText(title).assertIsDisplayed()

        rule.onNodeWithContentDescription("Вычеркнуть дело: $title").performClick()
        rule.onNodeWithTag("blankListArea").performScrollToNode(hasText(title))
        rule.onNodeWithContentDescription("Вернуть дело: $title").assertExists()

        rule.onNodeWithText(title).performClick()
        rule.onNodeWithText("В КОРЗИНУ").performClick()
        rule.onNodeWithContentDescription("Открыть корзину").performClick()
        rule.onNodeWithText(title).performClick()
        rule.onNodeWithText("ВОССТАНОВИТЬ").performClick()
        rule.onNodeWithContentDescription("Назад к списку").performClick()
        rule.onNodeWithTag("blankListArea").performScrollToNode(hasText(title))
        rule.onNodeWithText(title).assertIsDisplayed()

        rule.onNodeWithText(title).performClick()
        rule.onNodeWithText("В КОРЗИНУ").performClick()
        rule.onNodeWithContentDescription("Открыть корзину").performClick()
        rule.onNodeWithText(title).performClick()
        rule.onNodeWithText("УДАЛИТЬ НАВСЕГДА").performClick()
        rule.onNodeWithText("УДАЛИТЬ").performClick()
        rule.onNodeWithText(title).assertDoesNotExist()
    }

    @Test fun themeChoiceIsSaved() {
        rule.onNodeWithText("ТЕМА").performClick()
        rule.onNodeWithText("Светлая", substring = true).performClick()
        rule.runOnUiThread {
            val value = rule.activity.getSharedPreferences("settings", 0).getString("theme", null)
            check(value == "LIGHT")
        }
        rule.onNodeWithText("ТЕМА").performClick()
        rule.onNodeWithText("Как в системе", substring = true).performClick()
    }

    @Test fun styledReminderPickerSavesDateAndTime() {
        val title = "Напомнить ${System.currentTimeMillis()}"
        rule.onNodeWithTag("newTaskInput").performTextInput(title)
        rule.onNodeWithContentDescription("Добавить дело").performClick()
        rule.onNodeWithText(title).performClick()
        rule.onNodeWithText("Назначить дату и время", substring = true).performClick()
        rule.onNodeWithTag("reminderPicker").assertIsDisplayed()
        rule.onNodeWithTag("nextMonth").performClick()
        rule.onNodeWithTag("reminderDay:15").performClick()
        rule.onNodeWithTag("reminderHour").performTextClearance()
        rule.onNodeWithTag("reminderHour").performTextInput("12")
        rule.onNodeWithTag("reminderMinute").performTextClearance()
        rule.onNodeWithTag("reminderMinute").performTextInput("30")
        rule.onNodeWithTag("saveReminder").performClick()
        rule.waitUntil(3_000) {
            TaskStore(rule.activity).load().firstOrNull { it.title == title }?.reminderAt != null
        }
        val saved = TaskStore(rule.activity).load().first { it.title == title }
        val time = Calendar.getInstance().apply { timeInMillis = saved.reminderAt!! }
        check(time.get(Calendar.DAY_OF_MONTH) == 15)
        check(time.get(Calendar.HOUR_OF_DAY) == 12)
        check(time.get(Calendar.MINUTE) == 30)
    }

    @Test fun backupActionsAreAvailable() {
        rule.onNodeWithTag("backupMenu").performClick()
        rule.onNodeWithText("СОХРАНИТЬ КОПИЮ").assertIsDisplayed()
        rule.onNodeWithText("ВОССТАНОВИТЬ ИЗ ФАЙЛА").assertIsDisplayed()
        check(
            rule.onAllNodesWithText("ПОДКЛЮЧИТЬ GOOGLE ДИСК").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("СОХРАНИТЬ СЕЙЧАС").fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test fun shoppingItemsStayInTheirOwnListAndBackup() {
        val title = "Молоко ${System.currentTimeMillis()}"
        rule.onNodeWithTag("tab:shopping").performClick()
        rule.onNodeWithTag("newTaskInput").performTextInput(title)
        rule.onNodeWithContentDescription("Добавить покупку").performClick()
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.waitUntil(3_000) {
            TaskStore(rule.activity).load().firstOrNull { it.title == title }?.list == TaskList.SHOPPING
        }
        rule.onNodeWithTag("tab:tasks").performClick()
        rule.onNodeWithText(title).assertDoesNotExist()
        rule.onNodeWithTag("tab:shopping").performClick()
        rule.onNodeWithText(title).assertIsDisplayed()
        val saved = TaskStore(rule.activity).load()
        check(TaskBackupCodec.decode(TaskBackupCodec.encode(saved)).tasks.first { it.title == title }.list == TaskList.SHOPPING)
    }

    @Test fun connectedCloudFileCanBeSavedAgain() {
        val settings = rule.activity.getSharedPreferences("cloud_backup", android.content.Context.MODE_PRIVATE)
        assumeTrue(settings.contains("uri"))
        val previousSave = settings.getLong("last_save", 0L)
        rule.onNodeWithTag("backupMenu").performClick()
        rule.onNodeWithText("СОХРАНИТЬ СЕЙЧАС").performClick()
        rule.waitUntil(15_000) { settings.getLong("last_save", 0L) > previousSave }
    }

    @Test fun longTaskIsFullyVisibleAndEditable() {
        val title = "Длинное дело: " + (1..12).joinToString(" ") { "важная подробность $it" }
        rule.onNodeWithTag("newTaskInput").performTextInput(title)
        rule.onNodeWithContentDescription("Добавить дело").performClick()
        val row = rule.onNodeWithTag("swipeTask:$title").fetchSemanticsNode()
        check(row.boundsInRoot.height > 500f) { "Длинный текст обрезан до нескольких строк" }
        rule.onNodeWithText(title).performClick()
        rule.onNodeWithTag("editTitle").assertTextContains(title)
    }

    @Test fun swipeMovesTaskToTrash() {
        val title = "Свайп ${System.currentTimeMillis()}"
        rule.onNodeWithTag("newTaskInput").performTextInput(title)
        rule.onNodeWithContentDescription("Добавить дело").performClick()
        rule.onNodeWithTag("swipeTask:$title").performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) {
            TaskStore(rule.activity).load().firstOrNull { it.title == title }?.deletedAt != null
        }
        rule.onNodeWithContentDescription("Открыть корзину").performClick()
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithText(title).performClick()
        rule.onNodeWithText("УДАЛИТЬ НАВСЕГДА").performClick()
        rule.onNodeWithText("УДАЛИТЬ").performClick()
    }

    @Test fun swipeRestoresTaskFromTrash() {
        val title = "Свайп ${System.currentTimeMillis()}"
        rule.onNodeWithTag("newTaskInput").performTextInput(title)
        rule.onNodeWithContentDescription("Добавить дело").performClick()
        rule.onNodeWithTag("swipeTask:$title").performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) {
            TaskStore(rule.activity).load().firstOrNull { it.title == title }?.deletedAt != null
        }
        rule.onNodeWithContentDescription("Открыть корзину").performClick()
        rule.onNodeWithTag("restoreTask:$title").performTouchInput { swipeRight() }
        rule.waitUntil(timeoutMillis = 5_000) {
            TaskStore(rule.activity).load().first { it.title == title }.deletedAt == null
        }
        rule.onNodeWithContentDescription("Назад к списку").performClick()
        rule.onNodeWithTag("blankListArea").performScrollToNode(hasText(title))
        rule.onNodeWithText(title).assertIsDisplayed()
    }

    @Test fun swipeBackFromTrashToList() {
        rule.onNodeWithContentDescription("Открыть корзину").performClick()
        rule.onNodeWithTag("trashHeader").performTouchInput {
            swipe(Offset(30f, center.y), Offset(right - 30f, center.y), 300)
        }
        rule.onNodeWithText("СВАЛКА").assertIsDisplayed()
    }

    @Test fun requestsHighestAvailableRefreshRate() {
        rule.runOnUiThread {
            val display = rule.activity.window.decorView.display
            val maximum = display.supportedModes.maxOf { it.refreshRate }
            val requested = rule.activity.window.attributes.preferredRefreshRate
            check(kotlin.math.abs(requested - maximum) < 0.1f) {
                "Expected $maximum Hz, requested $requested Hz"
            }
        }
    }

    @Test fun tappingEmptySpaceHandlesDraft() {
        val field = rule.onNodeWithTag("newTaskInput")
        val blankArea = rule.onNodeWithTag("emptyTapArea")
        field.performClick()
        blankArea.performTouchInput { click() }
        field.assertIsNotFocused()

        field.performClick()
        field.performTextInput("Черновик")
        field.assertIsFocused()
        blankArea.performTouchInput { click() }
        rule.onNodeWithText("Что делать с заметкой?").assertIsDisplayed()
        rule.onNodeWithText("Продолжить").performClick()
        field.assertIsFocused()

        blankArea.performTouchInput { click(Offset(center.x, bottom - 20f)) }
        rule.onNodeWithText("Удаляй").performClick()
        field.assertIsNotFocused()
        field.assertTextEquals("")
    }

    @Test fun tappingTaskWhileWritingKeepsDraftInFront() {
        val title = "Проверка черновика ${System.currentTimeMillis()}"
        val field = rule.onNodeWithTag("newTaskInput")
        field.performTextInput(title)
        rule.onNodeWithContentDescription("Добавить дело").performClick()

        field.performClick()
        field.performTextInput("Новая заметка")
        rule.onNodeWithText(title).performTouchInput { click() }

        rule.onNodeWithText("Что делать с заметкой?").assertIsDisplayed()
        rule.onNodeWithTag("editTitle").assertDoesNotExist()
        rule.onNodeWithText("Продолжить").performClick()
        field.assertIsFocused()
    }

    @Test fun newAndEditedTasksStartWithCapitalLetter() {
        val suffix = System.currentTimeMillis()
        rule.onNodeWithTag("newTaskInput").performTextInput("купить хлеб $suffix")
        rule.onNodeWithContentDescription("Добавить дело").performClick()
        rule.onNodeWithText("Купить хлеб $suffix").performClick()
        rule.onNodeWithText("Редактировать дело.").assertIsDisplayed()
        rule.onNodeWithTag("editTitle").assertTextContains("Купить хлеб $suffix")
        rule.onNodeWithTag("editTitle").performTextClearance()
        rule.onNodeWithTag("editTitle").performTextInput("позвонить домой $suffix")
        rule.onNodeWithText("СОХРАНИТЬ ИЗМЕНЕНИЯ").performClick()
        rule.onNodeWithText("Позвонить домой $suffix").assertIsDisplayed()
    }

    @Test fun reminderChannelHasPlayableSound() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = rule.activity.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel("reminders_sound_v2")
        check(channel != null && channel.importance >= NotificationManager.IMPORTANCE_DEFAULT)
        check(channel.shouldVibrate())
        val sound = requireNotNull(channel.sound)
        rule.activity.contentResolver.openInputStream(sound).use { stream ->
            check(stream != null && stream.read() >= 0)
        }
    }

    @Test fun inputGrowsWhenTextWraps() {
        val field = rule.onNodeWithTag("newTaskInput")
        val initialHeight = field.fetchSemanticsNode().boundsInRoot.height
        field.performTextInput("Это длинная запись " .repeat(16))
        check(field.fetchSemanticsNode().boundsInRoot.height > initialHeight * 2) {
            "Поле не расширилось для нескольких строк"
        }
    }

    @Test fun longPressPinsAndUnpinsTask() {
        val title = "Закрепить ${System.currentTimeMillis()}"
        rule.onNodeWithTag("newTaskInput").performTextInput(title)
        rule.onNodeWithContentDescription("Добавить дело").performClick()
        rule.onNodeWithText(title).performTouchInput { longClick() }
        rule.waitUntil(3_000) {
            TaskStore(rule.activity).load().first { it.title == title }.pinned
        }
        rule.onNodeWithText("ЗАКРЕПЛЕНО").assertIsDisplayed()
        rule.onNodeWithText(title).performTouchInput { longClick() }
        rule.waitUntil(3_000) {
            !TaskStore(rule.activity).load().first { it.title == title }.pinned
        }
    }

    @Test fun completingTaskRemovesPinAndReturningItDoesNotRestorePin() {
        val title = "Снять закреп ${System.currentTimeMillis()}"
        rule.onNodeWithTag("newTaskInput").performTextInput(title)
        rule.onNodeWithContentDescription("Добавить дело").performClick()
        rule.onNodeWithText(title).performTouchInput { longClick() }
        rule.waitUntil(3_000) {
            TaskStore(rule.activity).load().first { it.title == title }.pinned
        }

        rule.onNodeWithContentDescription("Вычеркнуть дело: $title").performClick()
        rule.waitUntil(3_000) {
            TaskStore(rule.activity).load().first { it.title == title }.let { it.completed && !it.pinned }
        }

        rule.onNodeWithTag("blankListArea").performScrollToNode(hasText(title))
        rule.onNodeWithContentDescription("Вернуть дело: $title").performClick()
        rule.waitUntil(3_000) {
            TaskStore(rule.activity).load().first { it.title == title }.let { !it.completed && !it.pinned }
        }
    }

    @Test fun dragHandleSavesManualOrder() {
        val suffix = System.currentTimeMillis()
        val first = "Первое $suffix"
        val second = "Второе $suffix"
        rule.onNodeWithTag("newTaskInput").performTextInput(first)
        rule.onNodeWithContentDescription("Добавить дело").performClick()
        rule.onNodeWithTag("newTaskInput").performTextInput(second)
        rule.onNodeWithContentDescription("Добавить дело").performClick()
        val firstY = rule.onNodeWithTag("dragHandle:$first").fetchSemanticsNode().boundsInRoot.center.y
        val secondY = rule.onNodeWithTag("dragHandle:$second").fetchSemanticsNode().boundsInRoot.center.y
        val distance = firstY - secondY
        rule.onNodeWithTag("dragHandle:$second").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, 12f))
            moveBy(Offset(0f, distance - 12f))
            up()
        }
        rule.waitUntil(3_000) {
            val titles = TaskStore(rule.activity).load().map { it.title }
            titles.indexOf(first) < titles.indexOf(second)
        }
    }

    @Test fun swipeMovesImmediatelyAndCanBeCancelled() {
        val title = "Отмена свайпа ${System.currentTimeMillis()}"
        rule.onNodeWithTag("newTaskInput").performTextInput(title)
        rule.onNodeWithContentDescription("Добавить дело").performClick()
        rule.onNodeWithTag("swipeTask:$title").performTouchInput { swipeLeft() }
        rule.waitUntil(3_000) {
            TaskStore(rule.activity).load().first { it.title == title }.deletedAt != null
        }
        rule.onNodeWithTag("undoCountdown").assertIsDisplayed()
        rule.onNodeWithTag("cancelSwipe").performClick()
        Thread.sleep(2_000)
        check(TaskStore(rule.activity).load().first { it.title == title }.deletedAt == null)
        rule.onNodeWithTag("blankListArea").performScrollToNode(hasText(title))
        rule.onNodeWithText(title).assertIsDisplayed()
    }
}
