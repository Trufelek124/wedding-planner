package io.github.trufelek124.weddingplanner.reminders

import io.github.trufelek124.weddingplanner.data.Entry
import io.github.trufelek124.weddingplanner.data.Planner
import io.github.trufelek124.weddingplanner.money.money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * What is due (S11 section 2), worked out from the planner's copy as the screens see it. Nothing of
 * the phone is in this file: the copy, today's date and how far ahead to look are all it needs.
 */

/**
 * How far ahead a reminder looks, besides what is overdue.
 *
 * @param days how many days after today a day still counts as due: "within 3 days" is today and the three days after it
 */
enum class Ahead(val days: Long) {
    TODAY(0),
    TOMORROW(1),
    THREE_DAYS(3),
    SEVEN_DAYS(7),
}

/** Where a day stands against today. */
sealed interface Day {

    /** Before today. */
    data class Overdue(val since: LocalDate) : Day

    data object Today : Day

    data object Tomorrow : Day

    /** Later than tomorrow. */
    data class On(val day: LocalDate) : Day
}

/**
 * A task or a step that is due. Not a data class: the name is the couple's own and is not to be printed.
 *
 * @param amount a payment's amount, written as the app writes money; null for what is not a payment
 */
class DueThing(val entryId: String, val name: String, val amount: String?, val day: Day, val today: LocalDate) {

    override fun toString(): String = "DueThing[entryId=$entryId]"
}

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DAY_OF_ANOTHER_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** "22 Nov", and "3 Jan 2027" where the year is not the one of [today]: a reminder never leaves a day in doubt. */
fun dayWords(day: LocalDate, today: LocalDate): String = day.format(if (day.year == today.year) DAY else DAY_OF_ANOTHER_YEAR)

/**
 * The open tasks and steps of the plan that are overdue or due within [ahead] days of [today], the overdue
 * first, then by day, then by name. Open is "not done, not dropped"; what waits on the phone to be sent counts as the
 * copy shows it, so a task ticked off here is not named. A step is due on its last day.
 */
fun dueThings(planner: Planner, today: LocalDate, ahead: Ahead): List<DueThing> {
    val last = today.plusDays(ahead.days)
    return planner.entries
        .filter { it.status.open && it.dueDate != null && !it.dueDate.isAfter(last) }
        .sortedWith(compareBy<Entry> { it.dueDate!! }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.id })
        .map { entry ->
            val due = entry.dueDate!!
            val day = when (ChronoUnit.DAYS.between(today, due)) {
                0L -> Day.Today
                1L -> Day.Tomorrow
                else -> if (due.isBefore(today)) Day.Overdue(due) else Day.On(due)
            }
            DueThing(entry.id, entry.title, entry.amount?.let { money(it, planner.currency) }, day, today)
        }
}
