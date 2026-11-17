package com.svalka.reminder

import java.util.Calendar

enum class RepeatRule(val storageValue: String, val label: String) {
    NONE("none", "НЕ ПОВТОРЯТЬ"),
    DAILY("daily", "КАЖДЫЙ ДЕНЬ"),
    WEEKLY("weekly", "КАЖДУЮ НЕДЕЛЮ"),
    MONTHLY("monthly", "КАЖДЫЙ МЕСЯЦ");

    companion object {
        fun fromStorage(value: String?): RepeatRule =
            entries.firstOrNull { it.storageValue == value } ?: NONE
    }
}

/** First occurrence of [from] + k * [rule] (k >= 1) that is strictly later than [after]. */
fun nextOccurrence(from: Long, rule: RepeatRule, after: Long): Long? {
    if (rule == RepeatRule.NONE) return null
    val base = Calendar.getInstance().apply { timeInMillis = from }
    var step = 1
    while (step <= 20_000) {
        val candidate = (base.clone() as Calendar).apply {
            when (rule) {
                RepeatRule.DAILY -> add(Calendar.DAY_OF_YEAR, step)
                RepeatRule.WEEKLY -> add(Calendar.WEEK_OF_YEAR, step)
                RepeatRule.MONTHLY -> add(Calendar.MONTH, step)
                RepeatRule.NONE -> Unit
            }
        }.timeInMillis
        if (candidate > after) return candidate
        step++
    }
    return null
}

/** The moment the next notification is due: a snooze wins over the regular reminder. */
val ReminderTask.effectiveAt: Long? get() = snoozeUntil ?: reminderAt

/** Repeating tasks keep reminding even when ticked off; they are reset at the next occurrence. */
fun ReminderTask.remindable(): Boolean =
    deletedAt == null && effectiveAt != null && (!completed || repeat != RepeatRule.NONE)

/** State of the task right after the notification for [effectiveAt] has been shown. */
fun ReminderTask.afterDelivery(now: Long): ReminderTask {
    if (snoozeUntil != null) return copy(snoozeUntil = null)
    val at = reminderAt ?: return this
    if (repeat == RepeatRule.NONE) return this
    return copy(reminderAt = nextOccurrence(at, repeat, now) ?: at, completed = false)
}

fun ReminderTask.markDone(): ReminderTask = copy(completed = true, pinned = false, snoozeUntil = null)

fun ReminderTask.snoozedFor(now: Long, minutes: Int): ReminderTask =
    copy(snoozeUntil = now + minutes * 60_000L)
