package com.svalka.reminder

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
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

    @Test fun backupActionsAreAvailable() {
        rule.onNodeWithTag("backupMenu").performClick()
        rule.onNodeWithText("СОХРАНИТЬ КОПИЮ").assertIsDisplayed()
        rule.onNodeWithText("ВОССТАНОВИТЬ ИЗ ФАЙЛА").assertIsDisplayed()
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
}
